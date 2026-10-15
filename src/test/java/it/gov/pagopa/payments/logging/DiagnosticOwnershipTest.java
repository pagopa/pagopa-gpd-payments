package it.gov.pagopa.payments.logging;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.elastic.logging.logback.EcsEncoder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class DiagnosticOwnershipTest {
  private Map<String, String> original;

  @BeforeEach
  void setUp() {
    original = MDC.getCopyOfContextMap();
    MDC.clear();
  }

  @AfterEach
  void tearDown() {
    OperationScopeTest.restore(original);
  }

  @Test
  void dependencyDiagnosticOwnsPropagatedFailureButWriterRemainsNonDeduplicating()
      throws Exception {
    LoggerContext context = new LoggerContext();
    EcsEncoder encoder = new EcsEncoder();
    try {
      var logger = context.getLogger("test.scoped.diagnostic");
      logger.setLevel(Level.DEBUG);
      logger.setAdditive(false);
      var appender = new ListAppender<ILoggingEvent>() {
        @Override
        protected void append(ILoggingEvent event) {
          event.prepareForDeferredProcessing();
          super.append(event);
        }
      };
      appender.setContext(context);
      appender.start();
      logger.addAppender(appender);
      encoder.setContext(context);
      encoder.start();
      EventWriter writer = new EventWriter(logger);
      IOException failure = new IOException("original complete message");
      try (OperationScope operation = OperationScope.open("request", "read")) {
        var details = new EventDetails.DependencyCall(
            EventDetails.Dependency.AZURE_TABLE_STORAGE, EventDetails.DependencyOperation.GET_RECEIPT,
            EventDetails.Outcome.KO, null, null, null, null, null);
        assertTrue(operation.diagnoseOnce(failure, () -> writer.dependencyFailed(
            details, EventWriter.FailureDisposition.TERMINAL, failure)));
        Exception converted = new Exception("converted", failure);
        assertFalse(operation.diagnoseOnce(converted, () -> writer.technicalFailure(converted)));
        writer.emit(LogEvent.REST_OPERATION_COMPLETED);
        assertEquals(2, appender.list.size());
        assertEquals("original complete message",
            appender.list.get(0).getMDCPropertyMap().get("error.message"));
        assertNull(appender.list.get(0).getThrowableProxy());
        JsonNode diagnostic = new ObjectMapper().readTree(
            new String(encoder.encode(appender.list.get(0)), StandardCharsets.UTF_8));
        assertEquals(IOException.class.getName(), diagnostic.get("error.type").asText());
        assertEquals("request", diagnostic.get("requestId").asText());
        assertEquals(Map.of(
            "requestId", "request", "operationId", MDC.get("operationId"), "method", "read"),
            MDC.getCopyOfContextMap());
        assertEquals(MDC.getCopyOfContextMap(), appender.list.get(1).getMDCPropertyMap().entrySet()
            .stream().filter(entry -> !entry.getKey().equals("event.code"))
            .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
        assertFalse(diagnostic.has("soapTechnicalErrorLogged"));
        assertFalse(diagnostic.has("diagnosed"));
        assertFalse(diagnostic.has("ownership"));
        assertFalse(diagnostic.has("links"));

        writer.technicalFailure(failure);
        writer.technicalFailure(failure);
        assertEquals(4, appender.list.size());
      }
    } finally {
      encoder.stop();
      context.stop();
    }
  }

  @Test
  void paymentScopesShareOwnershipAndDoNotLoseItWhenTheyClose() {
    Exception failure = new Exception();
    AtomicInteger emitted = new AtomicInteger();
    try (OperationScope operation = OperationScope.open("request")) {
      try (OperationScope payment = operation.payment("000123", "creditor")) {
        assertTrue(payment.diagnoseOnce(failure, emitted::incrementAndGet));
        assertFalse(operation.diagnoseOnce(failure, emitted::incrementAndGet));
      }
      assertFalse(operation.diagnoseOnce(failure, emitted::incrementAndGet));
      try (OperationScope payment = operation.payment("other", null)) {
        assertFalse(payment.diagnoseOnce(failure, emitted::incrementAndGet));
      }
    }
    assertEquals(1, emitted.get());
  }

  @Test
  void separateRetryOperationsDoNotSuppressEvenTheSameExceptionInstance() {
    Exception failure = new Exception();
    AtomicInteger emitted = new AtomicInteger();
    try (OperationScope job = OperationScope.open("job")) {
      assertTrue(job.diagnoseOnce(failure, emitted::incrementAndGet));
      for (int attempt = 0; attempt < 2; attempt++) {
        try (OperationScope retry = OperationScope.open("attempt")) {
          assertThrows(IllegalStateException.class,
              () -> job.diagnoseOnce(failure, emitted::incrementAndGet));
          assertTrue(retry.diagnoseOnce(failure, emitted::incrementAndGet));
          assertFalse(retry.diagnoseOnce(failure, emitted::incrementAndGet));
        }
      }
      assertFalse(job.diagnoseOnce(failure, emitted::incrementAndGet));
    }
    try (OperationScope next = OperationScope.open("next")) {
      assertTrue(next.diagnoseOnce(failure, emitted::incrementAndGet));
    }
    assertEquals(4, emitted.get());
  }

  @Test
  void comparesIdentityNotEqualityOrMessage() {
    class EqualFailure extends Exception {
      EqualFailure() {
        super("same message");
      }

      @Override
      public boolean equals(Object other) {
        return other instanceof EqualFailure;
      }

      @Override
      public int hashCode() {
        return 1;
      }
    }
    AtomicInteger emitted = new AtomicInteger();
    try (OperationScope operation = OperationScope.open("request")) {
      assertTrue(operation.diagnoseOnce(new EqualFailure(), emitted::incrementAndGet));
      assertTrue(operation.diagnoseOnce(new EqualFailure(), emitted::incrementAndGet));
    }
    assertEquals(2, emitted.get());
  }

  @Test
  void scansDeepPrimaryChainBeyondDiagnosticRenderingBudget() {
    Throwable root = new Exception("root");
    Throwable outer = root;
    for (int i = 0; i < 10000; i++) {
      outer = new Exception("wrapper", outer);
    }
    try (OperationScope operation = OperationScope.open("request")) {
      assertTrue(operation.diagnoseOnce(root, () -> {}));
      assertFalse(operation.diagnoseOnce(outer, () -> fail("already diagnosed")));
    }
    try (OperationScope operation = OperationScope.open("other")) {
      assertTrue(operation.diagnoseOnce(outer, () -> {}));
      assertFalse(operation.diagnoseOnce(root, () -> fail("already diagnosed")));
    }
  }

  @Test
  void explicitDisconnectedConversionsWorkBeforeAndAfterDiagnosis() {
    Exception original = new Exception("original");
    Exception converted = new Exception("without cause");
    Exception convertedAgain = new Exception("also without cause");
    try (OperationScope operation = OperationScope.open("request")) {
      operation.linkFailure(converted, original);
      assertTrue(operation.diagnoseOnce(converted, () -> {}));
      assertFalse(operation.diagnoseOnce(original, () -> fail("already diagnosed")));
      operation.linkFailure(convertedAgain, converted);
      assertFalse(operation.diagnoseOnce(convertedAgain, () -> fail("already diagnosed")));
    }
    try (OperationScope operation = OperationScope.open("other")) {
      assertTrue(operation.diagnoseOnce(original, () -> {}));
      operation.linkFailure(converted, original);
      assertFalse(operation.diagnoseOnce(converted, () -> fail("already diagnosed")));
      assertTrue(operation.diagnoseOnce(convertedAgain, () -> {}));
    }
  }

  @Test
  void deepConversionLinksAndPrimaryCyclesAreTraversedIteratively() {
    Exception first = new Exception("first");
    Exception second = new Exception("second", first);
    first.initCause(second);
    try (OperationScope operation = OperationScope.open("request")) {
      Throwable last = second;
      for (int i = 0; i < 10000; i++) {
        Throwable converted = new Exception("converted");
        operation.linkFailure(converted, last);
        last = converted;
      }
      assertTrue(operation.diagnoseOnce(last, () -> {}));
      assertFalse(operation.diagnoseOnce(first, () -> fail("already diagnosed")));
      operation.linkFailure(first, last);
      assertFalse(operation.diagnoseOnce(last, () -> fail("already diagnosed")));
    }
  }

  @Test
  void suppressedCleanupFailuresRemainDistinct() {
    Exception main = new Exception("main");
    Exception cleanup = new Exception("cleanup");
    main.addSuppressed(cleanup);
    try (OperationScope operation = OperationScope.open("request")) {
      assertTrue(operation.diagnoseOnce(main, () -> {}));
      assertTrue(operation.diagnoseOnce(cleanup, () -> {}));
      assertTrue(operation.diagnoseOnce(new Exception("other"), () -> {}));
    }
  }

  @Test
  void failedEmissionDoesNotClaimOwnershipAndPreservesMdc() {
    var logger = mock(org.slf4j.Logger.class);
    when(logger.isErrorEnabled()).thenReturn(true);
    RuntimeException loggerFailure = new RuntimeException("logger failed");
    doThrow(loggerFailure).doNothing().when(logger).error("Operation failed technically");
    EventWriter writer = new EventWriter(logger);
    IOException failure = new IOException("original");
    try (OperationScope operation = OperationScope.open("request")) {
      Map<String, String> before = MDC.getCopyOfContextMap();
      assertSame(loggerFailure, assertThrows(RuntimeException.class,
          () -> operation.diagnoseOnce(failure, () -> writer.technicalFailure(failure))));
      assertEquals(before, MDC.getCopyOfContextMap());
      assertTrue(operation.diagnoseOnce(failure, () -> writer.technicalFailure(failure)));
      assertFalse(operation.diagnoseOnce(failure, () -> writer.technicalFailure(failure)));
      verify(logger, times(2)).error("Operation failed technically");
      assertEquals(before, MDC.getCopyOfContextMap());
    }
  }

  @Test
  void rejectsNullInputsAndUseAfterBoundaryCloses() {
    OperationScope operation = OperationScope.open("request");
    try {
      assertThrows(NullPointerException.class, () -> operation.diagnoseOnce(null, () -> {}));
      assertThrows(NullPointerException.class,
          () -> operation.diagnoseOnce(new Exception(), null));
      assertThrows(NullPointerException.class,
          () -> operation.linkFailure(null, new Exception()));
      assertThrows(NullPointerException.class,
          () -> operation.linkFailure(new Exception(), null));
    } finally {
      operation.close();
    }
    assertThrows(IllegalStateException.class,
        () -> operation.diagnoseOnce(new Exception(), () -> {}));
    assertThrows(IllegalStateException.class,
        () -> operation.linkFailure(new Exception(), new Exception()));
  }
}
