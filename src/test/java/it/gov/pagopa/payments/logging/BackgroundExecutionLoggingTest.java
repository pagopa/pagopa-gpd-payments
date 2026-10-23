package it.gov.pagopa.payments.logging;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;

class BackgroundExecutionLoggingTest {
  private Map<String, String> previous;

  @BeforeEach
  void before() {
    previous = MDC.getCopyOfContextMap();
    MDC.setContextMap(Map.of("method", "caller", "args", "SECRET", "httpCode", "500"));
  }

  @AfterEach
  void after() {
    if (previous == null) MDC.clear();
    else MDC.setContextMap(previous);
  }

  @Test
  void nestedMessagesHaveIndependentIdentityAndRestoreJobAndCaller() throws Exception {
    try (ApplicationLogCapture logs = new ApplicationLogCapture()) {
      AtomicReference<String> job = new AtomicReference<>();
      BackgroundExecutionLogging.runWithLogging("job", execution -> {
        job.set(MDC.get("correlation_id"));
        assertNull(MDC.get("args"));
        assertNull(MDC.get("httpCode"));
        BackgroundExecutionLogging.runWithLogging("message", first -> {
          first.recordPaymentLogContext("creditor", "000123", "actual-iuv");
          first.markFailureForLogging();
        });
        assertEquals(job.get(), MDC.get("correlation_id"));
        assertNull(MDC.get("ctx_iuv"));
        BackgroundExecutionLogging.runWithLogging("message", second -> {
          assertNull(MDC.get("ctx_creditor_id"));
          assertNull(MDC.get("ctx_notice_number"));
          assertNull(MDC.get("ctx_iuv"));
        });
      });
      assertEquals(3, logs.events.size());
      var first = logs.encodedEvent(logs.events.get(0));
      var second = logs.encodedEvent(logs.events.get(1));
      var parent = logs.encodedEvent(logs.events.get(2));
      assertEquals("KO", first.path("status").asText());
      assertEquals("000123", first.path("ctx_notice_number").asText());
      assertEquals("OK", second.path("status").asText());
      assertEquals("OK", parent.path("status").asText());
      assertEquals("job", parent.path("method").asText());
      assertNotEquals(first.path("correlation_id"), second.path("correlation_id"));
      assertNotEquals(parent.path("correlation_id"), first.path("correlation_id"));
      assertFalse(parent.has("ctx_iuv"));
      assertTrue(parent.path("event.duration").isIntegralNumber());
      for (var event : logs.events) {
        var logEntry = logs.encodedEvent(event);
        assertEquals("Background execution completed", logEntry.path("message").asText());
        assertFalse(logEntry.has("http.response.status_code"));
        assertFalse(logEntry.has("ctx_soap_outcome"));
        assertFalse(logEntry.has("ctx_soap_fault_code"));
        assertFalse(logEntry.has("args"));
      }
      assertEquals(Map.of("method", "caller", "args", "SECRET", "httpCode", "500"),
          MDC.getCopyOfContextMap());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void propagatedFailureRetainsIdentityAndRestoresMdc(boolean fatal) throws Exception {
    Throwable failure = fatal ? new AssertionError("SECRET") : new IllegalStateException("SECRET");
    try (ApplicationLogCapture logs = new ApplicationLogCapture()) {
      Throwable actual = assertThrows(failure.getClass(), () ->
          BackgroundExecutionLogging.runWithLogging("job", execution -> {
            if (failure instanceof Error error) throw error;
            throw (RuntimeException) failure;
          }));
      assertSame(failure, actual);
      assertEquals(1, logs.events.size());
      assertEquals("KO", logs.encodedEvent(logs.events.get(0)).path("status").asText());
      assertEquals("caller", MDC.get("method"));
      assertEquals("SECRET", MDC.get("args"));
    }
  }
}
