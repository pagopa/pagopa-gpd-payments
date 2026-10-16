package it.gov.pagopa.payments.utils;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import it.gov.pagopa.payments.logging.LogEventWriter;
import it.gov.pagopa.payments.logging.FailureDiagnostics;
import it.gov.pagopa.payments.logging.OperationLogScope;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class SchedulerUtilsTest {
  private Logger logger;
  private Level previousLevel;
  private boolean previousAdditive;
  private Map<String, String> previousMdc;
  private ListAppender<ILoggingEvent> logs;

  @BeforeEach
  void setUp() {
    previousMdc = MDC.getCopyOfContextMap();
    MDC.clear();
    logger = (Logger) LoggerFactory.getLogger(SchedulerUtils.class);
    previousLevel = logger.getLevel();
    previousAdditive = logger.isAdditive();
    logger.setLevel(Level.DEBUG);
    logger.setAdditive(false);
    logs = new ListAppender<>() {
      @Override
      protected void append(ILoggingEvent event) {
        event.prepareForDeferredProcessing();
        super.append(event);
      }
    };
    logs.setContext(logger.getLoggerContext());
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(logs);
    logs.stop();
    logger.setLevel(previousLevel);
    logger.setAdditive(previousAdditive);
    if (previousMdc == null) {
      MDC.clear();
    } else {
      MDC.setContextMap(previousMdc);
    }
  }

  @Test
  void publicStartPreservesLegacyIdentifiersAndTimingButNeverStoresArguments() {
    MDC.put("args", "stale arguments");
    MDC.put("faultDetail", "stale failure");
    long before = System.currentTimeMillis();

    SchedulerUtils.updateMDCForStartExecution("legacyJob", "private arbitrary payload");

    long after = System.currentTimeMillis();
    assertEquals("legacyJob", MDC.get("method"));
    long started = Long.parseLong(MDC.get("startTime"));
    assertTrue(started >= before && started <= after);
    assertDoesNotThrow(() -> UUID.fromString(MDC.get("requestId")));
    assertDoesNotThrow(() -> UUID.fromString(MDC.get("operationId")));
    assertNotEquals(MDC.get("requestId"), MDC.get("operationId"));
    assertEquals(Set.of("method", "startTime", "requestId", "operationId"),
        MDC.getCopyOfContextMap().keySet());
    assertTrue(logs.list.isEmpty());

    String requestId = MDC.get("requestId");
    String operationId = MDC.get("operationId");
    SchedulerUtils.updateMDCForStartExecution("legacyJob", "");
    assertNull(MDC.get("args"));
    assertNotEquals(requestId, MDC.get("requestId"));
    assertNotEquals(operationId, MDC.get("operationId"));
    SchedulerUtils.updateMDCForStartExecution("legacyJob", null);
    assertNull(MDC.get("args"));
  }

  @Test
  void publicSuccessEmitsStableSummaryWithLegacy201AndNoInventedCounts() {
    SchedulerUtils.updateMDCForStartExecution("legacyJob", "private arguments");
    String requestId = MDC.get("requestId");
    String operationId = MDC.get("operationId");
    MDC.put("startTime", Long.toString(System.currentTimeMillis() - 1000));
    MDC.put("args", "stale arguments");
    MDC.put("faultDetail", "stale failure");

    assertDoesNotThrow(SchedulerUtils::updateMDCForEndExecution);

    assertEquals("OK", MDC.get("status"));
    assertEquals("201", MDC.get("httpCode"));
    assertTrue(Long.parseLong(MDC.get("responseTime")) >= 1000);
    assertEquals(requestId, MDC.get("requestId"));
    assertEquals(operationId, MDC.get("operationId"));
    assertEquals(1, logs.list.size());
    assertSummary(logs.list.get(0), "OK", "201");
    assertNull(MDC.get("args"));
    assertNull(MDC.get("faultDetail"));
  }

  @Test
  void publicFailureIsNonthrowingAndEmitsDiagnosticThenSummaryWithLegacy500() {
    SchedulerUtils.updateMDCForStartExecution("legacyJob", "private arguments");
    MDC.put("args", "stale arguments");
    MDC.put("faultDetail", "stale failure");
    IOException failure = new IOException("original failure");

    assertDoesNotThrow(() -> SchedulerUtils.updateMDCError(failure, "legacyJob"));

    assertEquals("KO", MDC.get("status"));
    assertEquals("500", MDC.get("httpCode"));
    assertEquals("legacyJob", MDC.get("faultCode"));
    assertTrue(Long.parseLong(MDC.get("responseTime")) >= 0);
    assertEquals(2, logs.list.size());
    ILoggingEvent diagnostic = logs.list.get(0);
    assertEquals(Level.ERROR, diagnostic.getLevel());
    assertEquals("Operation failed technically", diagnostic.getFormattedMessage());
    assertEquals("OPERATION_TECHNICAL_FAILED",
        diagnostic.getMDCPropertyMap().get("event.code"));
    assertEquals(IOException.class.getName(), diagnostic.getMDCPropertyMap().get("error.type"));
    assertEquals("original failure", diagnostic.getMDCPropertyMap().get("error.message"));
    assertNull(diagnostic.getThrowableProxy());
    assertNull(diagnostic.getArgumentArray());
    assertSummary(logs.list.get(1), "KO", "500");
    for (ILoggingEvent event : logs.list) {
      assertNull(event.getMDCPropertyMap().get("args"));
      assertNull(event.getMDCPropertyMap().get("faultDetail"));
    }
    assertEquals(Set.of("method", "startTime", "requestId", "operationId", "status",
        "httpCode", "responseTime", "faultCode"), MDC.getCopyOfContextMap().keySet());
  }

  @Test
  void endMethodsRetainLegacyMissingStartTimeFallback() {
    SchedulerUtils.updateMDCForEndExecution();
    assertEquals("-", MDC.get("responseTime"));
    SchedulerUtils.updateMDCError(new IOException("failure"), "legacyJob");
    assertEquals("-", MDC.get("responseTime"));
    assertSummary(logs.list.get(0), "OK", "201");
    assertSummary(logs.list.get(2), "KO", "500");
  }

  @Test
  void diagnosticsKeepCompleteOriginalMessagesWithThreeNodesAndThreeFramesPerNode() {
    String message = "https://original.example/path?token=unchanged\n" + "original".repeat(500);
    IOException root = new IOException("root original token=unchanged");
    IOException omitted = new IOException("omitted intermediate", root);
    IOException intermediate = new IOException("first original cause", omitted);
    IOException failure = new IOException(message, intermediate);
    StackTraceElement[] frames = new StackTraceElement[8];
    for (int i = 0; i < frames.length; i++) {
      frames[i] = new StackTraceElement(
          "it.gov.pagopa.payments.Sample", "frame" + i, "Sample.java", i + 1);
    }
    for (Throwable node : new Throwable[] {failure, intermediate, omitted, root}) {
      node.setStackTrace(frames);
    }

    SchedulerUtils.updateMDCError(failure, "legacyJob");

    Map<String, String> diagnostic = logs.list.get(0).getMDCPropertyMap();
    assertEquals(message, diagnostic.get("error.message"));
    String stack = diagnostic.get("error.stack_trace");
    assertTrue(stack.contains(IOException.class.getName() + ": " + message));
    assertTrue(stack.contains("first original cause"));
    assertTrue(stack.contains("root original token=unchanged"));
    assertFalse(stack.contains("omitted intermediate"));
    assertEquals(9, stack.lines().filter(line -> line.startsWith("\tat ")).count());
    assertEquals(3, stack.lines().filter(line -> line.endsWith("5 frames omitted")).count());
    assertEquals(2, stack.lines().filter(line -> line.startsWith("Caused by: ")).count());
    assertTrue(stack.contains("1 exceptions omitted"));
    assertFalse(stack.contains("frame3"));
    assertArrayEquals(frames, failure.getStackTrace());
    assertSame(intermediate, failure.getCause());
    assertNull(logs.list.get(0).getThrowableProxy());
    assertSummary(logs.list.get(1), "KO", "500");
  }

  @Test
  void absorbedFailureIsHandledButIndependentFailuresStillReachBoundary() {
    IOException original = new IOException("same message");
    try (OperationLogScope ignored = OperationLogScope.open("request", "legacyJob")) {
      FailureDiagnostics.absorbedFailure(new LogEventWriter(logger), original);
      SchedulerUtils.updateMDCError(new IOException("wrapped", original), "legacyJob");
      Exception converted = new Exception("converted", original);
      SchedulerUtils.updateMDCError(converted, "legacyJob");
      SchedulerUtils.updateMDCError(new IOException("same message"), "legacyJob");

      assertEquals(2, diagnostics());
      assertEquals(5, logs.list.size());
      assertSummary(logs.list.get(1), "KO", "500");
      assertSummary(logs.list.get(2), "KO", "500");
      assertSummary(logs.list.get(4), "KO", "500");
      assertEquals(Set.of("requestId", "operationId", "method", "status", "httpCode",
          "responseTime", "faultCode"), MDC.getCopyOfContextMap().keySet());
    }
    try (OperationLogScope ignored = OperationLogScope.open("next", "legacyJob")) {
      SchedulerUtils.updateMDCError(original, "legacyJob");
      assertEquals(3, diagnostics());
    }
  }

  @Test
  void withoutScopeRepeatedFailureAlwaysEmitsWithoutCreatingImplicitScope() {
    IOException failure = new IOException("same failure");
    SchedulerUtils.updateMDCError(failure, "legacyJob");
    SchedulerUtils.updateMDCError(failure, "legacyJob");

    assertNull(OperationLogScope.currentOrNull());
    assertEquals(2, diagnostics());
    assertEquals(4, logs.list.size());
    assertSummary(logs.list.get(1), "KO", "500");
    assertSummary(logs.list.get(3), "KO", "500");
  }

  @Test
  void diagnosticDoesNotReadArbitraryThrowableRepresentations() {
    IOException failure = new IOException("original message") {
      @Override
      public String getLocalizedMessage() {
        throw new AssertionError("Localized exception message");
      }

      @Override
      public String toString() {
        throw new AssertionError("Arbitrary exception representation");
      }
    };

    assertDoesNotThrow(() -> SchedulerUtils.updateMDCError(failure, "legacyJob"));

    assertEquals("original message", logs.list.get(0).getMDCPropertyMap().get("error.message"));
    assertNull(logs.list.get(0).getThrowableProxy());
    assertSummary(logs.list.get(1), "KO", "500");
  }

  private long diagnostics() {
    return logs.list.stream().filter(event -> "OPERATION_TECHNICAL_FAILED".equals(
        event.getMDCPropertyMap().get("event.code"))).count();
  }

  private void assertSummary(ILoggingEvent event, String status, String code) {
    assertEquals("Scheduled job completed", event.getFormattedMessage());
    assertEquals(Level.INFO, event.getLevel());
    assertEquals("SCHEDULED_JOB_COMPLETED", event.getMDCPropertyMap().get("event.code"));
    assertEquals(status, event.getMDCPropertyMap().get("status"));
    assertEquals(code, event.getMDCPropertyMap().get("httpCode"));
    assertNull(event.getThrowableProxy());
    assertNull(event.getArgumentArray());
    for (String key : Set.of("error.type", "error.message", "error.stack_trace", "ctx_details",
        "args", "faultDetail", "soapTechnicalErrorLogged", "diagnosed", "ownership", "links")) {
      assertFalse(event.getMDCPropertyMap().containsKey(key), key);
    }
  }
}
