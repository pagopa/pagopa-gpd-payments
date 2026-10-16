package it.gov.pagopa.payments.logging;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.elastic.logging.logback.EcsEncoder;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import feign.Request;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class LogEventWriterTest {
  private final LoggerContext context = new LoggerContext();
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>() {
    @Override
    protected void append(ILoggingEvent event) {
      event.prepareForDeferredProcessing();
      super.append(event);
    }
  };
  private Map<String, String> originalContext;
  private Logger logger;
  private LogEventWriter writer;

  @BeforeEach
  void setUp() {
    originalContext = MDC.getCopyOfContextMap();
    MDC.clear();
    logger = context.getLogger("test.event.writer");
    logger.setLevel(Level.DEBUG);
    logger.setAdditive(false);
    appender.setContext(context);
    appender.start();
    logger.addAppender(appender);
    writer = new LogEventWriter(logger);
  }

  @AfterEach
  void tearDown() {
    context.stop();
    if (originalContext == null) {
      MDC.clear();
    } else {
      MDC.setContextMap(originalContext);
    }
  }

  @Test
  void bothHttpSummariesAttachArgsOnlyDuringEmissionAndRestorePriorMdc() {
    for (LogEvent summary : List.of(
        LogEvent.SOAP_OPERATION_COMPLETED, LogEvent.REST_OPERATION_COMPLETED)) {
      try (OperationLogScope scope = ContextSnapshot.capture().openOperation("operation")) {
        scope.summaryArgs("{\"approved\":\"0001\"}");
        MDC.put("args", "previous");
        Map<String, String> previous = MDC.getCopyOfContextMap();
        writer.technicalFailure();
        assertNull(last().getMDCPropertyMap().get("args"));
        assertEquals(previous, MDC.getCopyOfContextMap());
        writer.logEvent(summary);
        assertEquals("{\"approved\":\"0001\"}", last().getMDCPropertyMap().get("args"));
        assertEquals(previous, MDC.getCopyOfContextMap());
        scope.summaryArgs(null);
        writer.logEvent(summary);
        assertNull(last().getMDCPropertyMap().get("args"));
        assertEquals(previous, MDC.getCopyOfContextMap());
      }
    }
  }

  @Test
  void summaryArgsRestoreMdcWhenTheLoggerThrows() {
    org.slf4j.Logger failingLogger = mock(org.slf4j.Logger.class);
    when(failingLogger.isInfoEnabled()).thenReturn(true);
    RuntimeException failure = new IllegalStateException("logging failed");
    doThrow(failure).when(failingLogger).info("REST operation completed");
    try (OperationLogScope scope = OperationLogScope.open("request", "operation")) {
      scope.summaryArgs("{\"iuv\":\"0001\"}");
      MDC.put("args", "previous");
      Map<String, String> previous = MDC.getCopyOfContextMap();
      assertSame(failure, assertThrows(RuntimeException.class,
          () -> new LogEventWriter(failingLogger).logEvent(LogEvent.REST_OPERATION_COMPLETED)));
      assertEquals(previous, MDC.getCopyOfContextMap());
    }
  }

  @Test
  void catalogExactlyMatchesApprovedCodesMessagesAndDefaultLevels() {
    String[][] expected = {
        {"SOAP_OPERATION_COMPLETED", "SOAP operation completed", "INFO"},
        {"REST_OPERATION_COMPLETED", "REST operation completed", "INFO"},
        {"PAYMENT_ELIGIBILITY_REJECTED", "Payment eligibility rejected", "INFO"},
        {"SOAP_REQUEST_VALIDATION_FAILED", "SOAP request XSD validation failed", "WARN"},
        {"DEPENDENCY_CALL_COMPLETED", "Dependency call completed", "DEBUG"},
        {"DEPENDENCY_CALL_FAILED", "Dependency call failed", "ERROR"},
        {"RECEIPT_RETRY_SCHEDULED", "Receipt retry scheduled", "INFO"},
        {"RECEIPT_PROCESSING_COMPLETED", "Receipt processing completed", "INFO"},
        {"RECEIPT_DEAD_LETTERED", "Receipt moved to dead letter", "WARN"},
        {"SCHEDULED_JOB_COMPLETED", "Scheduled job completed", "INFO"},
        {"OPERATION_TECHNICAL_FAILED", "Operation failed technically", "ERROR"}
    };
    assertEquals(expected.length, LogEvent.values().length);
    for (String[] entry : expected) {
      LogEvent event = LogEvent.valueOf(entry[0]);
      assertEquals(entry[0], event.code());
      assertEquals(entry[1], event.message());
      assertEquals(entry[2], event.defaultLevel().name());
    }
  }

  @Test
  void emitsCatalogSeverityAndMessageWithoutThrowableOrArguments() {
    for (LogEvent event : LogEvent.values()) {
      if (event == LogEvent.OPERATION_TECHNICAL_FAILED) {
        writer.technicalFailure(new IOException("secret"));
      } else if (event == LogEvent.DEPENDENCY_CALL_FAILED) {
        writer.dependencyFailed(dependency(EventDetails.Outcome.KO),
            LogEventWriter.FailureDisposition.TERMINAL, new IOException("secret"));
      } else {
        writer.logEvent(event);
      }
      ILoggingEvent logged = last();
      assertEquals(event.defaultLevel().name(), logged.getLevel().toString());
      assertEquals(event.message(), logged.getFormattedMessage());
      assertEquals(event.code(), logged.getMDCPropertyMap().get("event.code"));
      assertNull(logged.getThrowableProxy());
      assertNull(logged.getArgumentArray());
    }
    assertTrue(MDC.getCopyOfContextMap() == null || MDC.getCopyOfContextMap().isEmpty());
  }

  @Test
  void dependencyFailuresHaveOnlyControlledRecoverableAndTerminalSeverities() {
    writer.dependencyFailed(dependency(EventDetails.Outcome.KO),
        LogEventWriter.FailureDisposition.RECOVERABLE, new IOException("secret"));
    assertEquals(Level.WARN, last().getLevel());
    writer.dependencyFailed(dependency(EventDetails.Outcome.KO),
        LogEventWriter.FailureDisposition.TERMINAL, new IOException("secret"));
    assertEquals(Level.ERROR, last().getLevel());
    assertEquals("secret", last().getMDCPropertyMap().get("error.message"));
  }

  @Test
  void restoresAllPreviousMdcIncludingOverlappingDiagnosticFields() {
    Map<String, String> before = previousContext();
    MDC.setContextMap(before);
    writer.technicalFailure(new IOException("secret"));

    Map<String, String> emitted = last().getMDCPropertyMap();
    assertEquals(IOException.class.getName(), emitted.get("error.type"));
    assertEquals("secret", emitted.get("error.message"));
    assertFalse(emitted.containsKey("ctx_details"));
    assertFalse(emitted.containsKey("soapTechnicalErrorLogged"));
    assertEquals("request-1", emitted.get("requestId"));
    assertEquals("OK", emitted.get("status"));
    assertEquals("200", emitted.get("httpCode"));
    assertEquals(before, MDC.getCopyOfContextMap());
  }

  @Test
  void followingSummaryDoesNotInheritDetailsOrDiagnostics() {
    Map<String, String> before = previousContext();
    MDC.setContextMap(before);
    writer.dependencyFailed(dependency(EventDetails.Outcome.KO),
        LogEventWriter.FailureDisposition.RECOVERABLE, new IOException("secret"));
    assertTrue(last().getMDCPropertyMap().containsKey("ctx_details"));
    writer.logEvent(LogEvent.SOAP_OPERATION_COMPLETED);
    Map<String, String> summary = last().getMDCPropertyMap();
    for (String key : new String[] {
        "ctx_details", "error.type", "error.message", "error.stack_trace", "soapTechnicalErrorLogged"}) {
      assertFalse(summary.containsKey(key), key);
    }
    assertEquals(before, MDC.getCopyOfContextMap());
  }

  @Test
  void restoresMdcWhenLoggerThrows() {
    org.slf4j.Logger failingLogger = mock(org.slf4j.Logger.class);
    when(failingLogger.isErrorEnabled()).thenReturn(true);
    RuntimeException failure = new RuntimeException("logger failure");
    doThrow(failure).when(failingLogger).error("Operation failed technically");
    Map<String, String> before = previousContext();
    MDC.setContextMap(before);

    assertSame(failure, assertThrows(RuntimeException.class,
        () -> new LogEventWriter(failingLogger).technicalFailure(new IOException("secret"))));
    assertEquals(before, MDC.getCopyOfContextMap());
  }

  @Test
  void restoresMdcWhenDiagnosticConstructionThrows() {
    RuntimeException failure = new RuntimeException("broken throwable");
    Throwable broken = new RuntimeException("secret") {
      @Override
      public synchronized Throwable getCause() {
        throw failure;
      }
    };
    Map<String, String> before = previousContext();
    MDC.setContextMap(before);

    assertSame(failure, assertThrows(RuntimeException.class, () -> writer.technicalFailure(broken)));
    assertEquals(before, MDC.getCopyOfContextMap());
    assertTrue(appender.list.isEmpty());
  }

  @Test
  void restoresMdcWhenOriginalMessageAccessorThrows() {
    RuntimeException failure = new RuntimeException("broken message accessor");
    Throwable broken = new RuntimeException() {
      @Override
      public String getMessage() {
        throw failure;
      }
    };
    Map<String, String> before = previousContext();
    MDC.setContextMap(before);

    assertSame(failure, assertThrows(RuntimeException.class, () -> writer.technicalFailure(broken)));
    assertEquals(before, MDC.getCopyOfContextMap());
    assertTrue(appender.list.isEmpty());
  }

  @Test
  void disabledEventsDoNotInspectThrowablesOrAlterContext() {
    logger.setLevel(Level.OFF);
    Map<String, String> before = previousContext();
    MDC.setContextMap(before);
    Throwable unreadable = new RuntimeException("secret") {
      @Override
      public StackTraceElement[] getStackTrace() {
        throw new AssertionError("Disabled event must not construct diagnostics");
      }
    };
    writer.technicalFailure(unreadable);
    assertTrue(appender.list.isEmpty());
    assertEquals(before, MDC.getCopyOfContextMap());
  }

  @Test
  void rejectsMismatchedDetailsAndFailureOutcomesWithoutChangingContext() {
    Map<String, String> before = previousContext();
    MDC.setContextMap(before);
    var retry = new EventDetails.Retry(null, 2, null, null, null);
    assertThrows(IllegalArgumentException.class,
        () -> writer.logEvent(LogEvent.SOAP_OPERATION_COMPLETED, retry));
    assertThrows(IllegalArgumentException.class,
        () -> writer.logEvent(LogEvent.SCHEDULED_JOB_COMPLETED, retry));
    assertThrows(IllegalArgumentException.class,
        () -> writer.logEvent(LogEvent.DEPENDENCY_CALL_FAILED));
    assertThrows(IllegalArgumentException.class,
        () -> writer.logEvent(LogEvent.OPERATION_TECHNICAL_FAILED));
    assertThrows(IllegalArgumentException.class,
        () -> writer.logEvent(LogEvent.DEPENDENCY_CALL_COMPLETED, dependency(EventDetails.Outcome.KO)));
    assertThrows(IllegalArgumentException.class,
        () -> writer.dependencyFailed(dependency(EventDetails.Outcome.OK),
            LogEventWriter.FailureDisposition.TERMINAL, new IOException("secret")));
    assertEquals(before, MDC.getCopyOfContextMap());
    assertTrue(appender.list.isEmpty());
  }

  @Test
  void realEcsEncoderPreservesFullOriginalMessagesAndJsonStringDetailsWithoutDuplicateKeys()
      throws Exception {
    Map<String, String> before = previousContext();
    MDC.setContextMap(before);
    String rootMessage = "root \"quoted\"\r\n" + "root-secret".repeat(10_000);
    String causeMessage = "cause \"quoted\"\n" + "cause-secret".repeat(10_000);
    String suppressedMessage = "suppressed \"quoted\"\r\n" + "suppressed-secret".repeat(10_000);
    Throwable root = new IllegalStateException(rootMessage, new IOException(causeMessage));
    root.addSuppressed(new IllegalArgumentException(suppressedMessage));
    writer.dependencyFailed(dependency(EventDetails.Outcome.KO),
        LogEventWriter.FailureDisposition.TERMINAL, root);

    String encoded = encode(last());
    ObjectMapper mapper = new ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    JsonNode json = mapper.readTree(encoded);
    assertEquals("ERROR", json.get("log.level").textValue());
    assertEquals("Dependency call failed", json.get("message").textValue());
    assertEquals(IllegalStateException.class.getName(), json.get("error.type").textValue());
    assertEquals(rootMessage, json.get("error.message").textValue());
    String trace = json.get("error.stack_trace").textValue();
    assertTrue(trace.startsWith(root.getClass().getName() + ": " + rootMessage + "\n"));
    assertTrue(trace.contains("Caused by: java.io.IOException: " + causeMessage + "\n"));
    assertTrue(trace.contains(
        "Suppressed: java.lang.IllegalArgumentException: " + suppressedMessage + "\n"));
    assertEquals(last().getMDCPropertyMap().get("error.stack_trace"), trace);
    assertTrue(trace.contains("LogEventWriterTest.java:"));
    assertTrue(json.get("ctx_details").isTextual());
    JsonNode details = mapper.readTree(json.get("ctx_details").textValue());
    assertEquals("KO", details.get("dependencyOutcome").textValue());
    assertEquals(503, details.get("dependencyHttpCode").intValue());
    assertTrue(details.get("dependencyHttpCode").isIntegralNumber());
    assertFalse(encoded.contains("previous-"));
    assertFalse(encoded.contains("soapTechnicalErrorLogged"));
    assertNull(last().getThrowableProxy());
    assertNull(last().getArgumentArray());
    assertEquals(rootMessage, root.getMessage());
    assertEquals(causeMessage, root.getCause().getMessage());
    assertEquals(suppressedMessage, root.getSuppressed()[0].getMessage());
    assertEquals(before, MDC.getCopyOfContextMap());
  }

  @Test
  void realEcsEncoderPreservesBoundedRelevantFramesAndActualRootWithoutCallingToString()
      throws Exception {
    Throwable failure = new ToStringForbiddenException("initial message");
    Throwable first = new ToStringForbiddenException("first cause message");
    Throwable skipped = new ToStringForbiddenException("omitted cause message");
    Throwable deepest = new ToStringForbiddenException("deepest cause message");
    failure.initCause(first);
    first.initCause(skipped);
    skipped.initCause(deepest);
    failure.addSuppressed(new ToStringForbiddenException("omitted suppressed message"));
    StackTraceElement[] original = new StackTraceElement[8];
    for (int i = 0; i < original.length; i++) {
      original[i] = new StackTraceElement(
          i < 2 ? "library.Client" : "it.gov.pagopa.payments.Service" + i,
          "call", "Source.java", i);
    }
    for (Throwable node : new Throwable[] {failure, first, skipped, deepest}) {
      node.setStackTrace(original);
    }
    deepest.setStackTrace(new StackTraceElement[] {
        new StackTraceElement("it.gov.pagopa.payments.Root", "call", "Root.java", 42)});

    writer.technicalFailure(failure);
    String encoded = encode(last());
    JsonNode json = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .readTree(encoded);
    assertEquals(failure.getClass().getName(), json.get("error.type").textValue());
    assertEquals("Operation failed technically", json.get("message").textValue());
    assertEquals("initial message", json.get("error.message").textValue());
    String trace = json.get("error.stack_trace").textValue();
    assertTrue(trace.startsWith(failure.getClass().getName() + ": initial message\n"));
    assertTrue(trace.contains("Caused by: " + first.getClass().getName() + ": first cause message\n"));
    assertTrue(trace.contains(
        "Caused by: " + deepest.getClass().getName() + ": deepest cause message\n"));
    assertFalse(trace.contains("omitted cause message"));
    assertFalse(trace.contains("omitted suppressed message"));
    assertEquals(3, trace.lines().filter(line -> !line.startsWith("\t")).count());
    assertEquals(7, trace.lines().filter(line -> line.startsWith("\tat ")).count());
    assertEquals(2, trace.lines().filter(line -> line.contains("5 frames omitted")).count());
    assertTrue(trace.contains("2 exceptions omitted"));
    assertTrue(trace.contains("it.gov.pagopa.payments.Root.call(Root.java:42)"));
    assertFalse(trace.contains("library.Client"));
    assertFalse(trace.contains("Service7"));
    assertFalse(trace.contains("SafeDiagnostic"));
    assertFalse(trace.contains("LogEventWriter.java"));
    assertSame(first, failure.getCause());
    assertArrayEquals(original, failure.getStackTrace());
    assertNull(last().getThrowableProxy());
    assertNull(last().getArgumentArray());
  }

  @Test
  void realEcsEncoderOmitsNullMessageButPreservesEmptyMessageAndRestoresPreviousMdc()
      throws Exception {
    Map<String, String> before = previousContext();
    for (String message : new String[] {null, ""}) {
      MDC.setContextMap(before);
      Throwable failure = new IOException(message);
      Throwable cause = new IllegalStateException(message);
      Throwable suppressed = new IllegalArgumentException(message);
      failure.initCause(cause);
      failure.addSuppressed(suppressed);
      for (Throwable node : new Throwable[] {failure, cause, suppressed}) {
        node.setStackTrace(new StackTraceElement[0]);
      }
      writer.technicalFailure(failure);
      JsonNode json = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .readTree(encode(last()));
      if (message == null) {
        assertFalse(last().getMDCPropertyMap().containsKey("error.message"));
        assertFalse(json.has("error.message"));
      } else {
        assertEquals("", last().getMDCPropertyMap().get("error.message"));
        assertEquals("", json.get("error.message").textValue());
      }
      String suffix = message == null ? "\n" : ": \n";
      assertEquals(failure.getClass().getName() + suffix
          + "Caused by: " + cause.getClass().getName() + suffix
          + "Suppressed: " + suppressed.getClass().getName() + suffix,
          json.get("error.stack_trace").textValue());
      assertEquals(IOException.class.getName(), json.get("error.type").textValue());
      assertEquals("Operation failed technically", json.get("message").textValue());
      assertNull(last().getThrowableProxy());
      assertEquals(before, MDC.getCopyOfContextMap());
    }
  }

  @Test
  void realEcsEncoderRetainsFeignMessageWithoutDumpingRequestResponseOrHeaders() throws Exception {
    String message = "original response text: token=retained-value\n\"quoted\"";
    Request request = Request.create(Request.HttpMethod.GET,
        "https://example.invalid/private?token=request-url-secret",
        Map.of("Authorization", List.of("Bearer request-header-secret")),
        "request-body-secret".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8, null);
    FeignException failure = new FeignException.BadRequest(message, request,
        "response-body-secret".getBytes(StandardCharsets.UTF_8),
        Map.of("Set-Cookie", List.of("response-header-secret")));
    failure.setStackTrace(new StackTraceElement[0]);

    writer.dependencyFailed(dependency(EventDetails.Outcome.KO),
        LogEventWriter.FailureDisposition.RECOVERABLE, failure);
    String encoded = encode(last());
    JsonNode json = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .readTree(encoded);
    assertEquals("Dependency call failed", json.get("message").textValue());
    assertEquals("WARN", json.get("log.level").textValue());
    assertEquals(FeignException.BadRequest.class.getName(), json.get("error.type").textValue());
    assertEquals(message, json.get("error.message").textValue());
    assertEquals(failure.getClass().getName() + ": " + message + "\n",
        json.get("error.stack_trace").textValue());
    for (String internal : new String[] {
        "request-url-secret", "request-header-secret", "request-body-secret",
        "response-body-secret", "response-header-secret"}) {
      assertFalse(encoded.contains(internal), internal);
    }
    assertSame(request, failure.request());
    assertEquals(message, failure.getMessage());
    assertEquals(0, failure.getStackTrace().length);
    assertNull(last().getThrowableProxy());
    assertNull(last().getArgumentArray());
  }

  @Test
  void realEcsEncoderOmitsEmptyDetailsAndDiagnosticFieldsOnSummary() throws Exception {
    MDC.setContextMap(previousContext());
    writer.logEvent(LogEvent.RECEIPT_PROCESSING_COMPLETED,
        new EventDetails.ReceiptDisposition(null, null, null));
    JsonNode json = new ObjectMapper().readTree(encode(last()));
    assertFalse(json.has("ctx_details"));
    assertFalse(json.has("error.type"));
    assertFalse(json.has("error.message"));
    assertFalse(json.has("error.stack_trace"));
    assertFalse(json.has("soapTechnicalErrorLogged"));
  }

  @Test
  void nestedEmissionRestoresOuterEventAndThenCallerContext() {
    org.slf4j.Logger nestedLogger = mock(org.slf4j.Logger.class);
    when(nestedLogger.isInfoEnabled()).thenReturn(true);
    doAnswer(invocation -> {
      assertEquals("REST_OPERATION_COMPLETED", MDC.get("event.code"));
      Map<String, String> outer = MDC.getCopyOfContextMap();
      writer.technicalFailure(new IOException("secret"));
      assertEquals(outer, MDC.getCopyOfContextMap());
      return null;
    }).when(nestedLogger).info("REST operation completed");
    Map<String, String> before = previousContext();
    MDC.setContextMap(before);
    new LogEventWriter(nestedLogger).logEvent(LogEvent.REST_OPERATION_COMPLETED);
    assertEquals(before, MDC.getCopyOfContextMap());
  }

  private ILoggingEvent last() {
    return appender.list.get(appender.list.size() - 1);
  }

  private String encode(ILoggingEvent event) {
    EcsEncoder encoder = new EcsEncoder();
    encoder.setContext(context);
    encoder.setServiceName("payments-test");
    encoder.start();
    try {
      return new String(encoder.encode(event), StandardCharsets.UTF_8);
    } finally {
      encoder.stop();
    }
  }

  private EventDetails.DependencyCall dependency(EventDetails.Outcome outcome) {
    return new EventDetails.DependencyCall(EventDetails.Dependency.GPD_CORE,
        EventDetails.DependencyOperation.GET_PAYMENT_OPTION, outcome, 503, 42L, 2, null, null);
  }

  private Map<String, String> previousContext() {
    return Map.of(
        "requestId", "request-1",
        "status", "OK",
        "httpCode", "200",
        "event.code", "previous-event",
        "ctx_details", "previous-details-secret",
        "error.type", "previous-type-secret",
        "error.message", "previous-message-secret",
        "error.stack_trace", "previous-trace-secret",
        "soapTechnicalErrorLogged", "true");
  }

  private static final class ToStringForbiddenException extends RuntimeException {
    private ToStringForbiddenException(String message) {
      super(message);
    }

    @Override
    public String getLocalizedMessage() {
      throw new AssertionError("Must not read getLocalizedMessage");
    }

    @Override
    public String toString() {
      throw new AssertionError("Must not read toString");
    }
  }
}
