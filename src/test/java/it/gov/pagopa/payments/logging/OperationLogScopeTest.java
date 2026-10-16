package it.gov.pagopa.payments.logging;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class OperationLogScopeTest {
  private Map<String, String> original;

  @BeforeEach
  void setUp() {
    original = MDC.getCopyOfContextMap();
    MDC.clear();
  }

  @AfterEach
  void tearDown() {
    restore(original);
  }

  @Test
  void persistentPaymentAndSummaryProjectionStayInOneExecution() {
    assertNull(OperationLogScope.currentOrNull());
    try (OperationLogScope scope = OperationLogScope.open("request", "paSendRT")) {
      String operation = MDC.get("operationId");
      scope.setPaymentLogContext("0000123", "00123456789");
      scope.summaryArgs("{\"receiptId\":\"receipt\"}");
      assertSame(scope, OperationLogScope.currentOrNull());
      assertEquals(operation, MDC.get("operationId"));
      assertEquals("0000123", MDC.get("noticeNumber"));
      assertEquals("00123456789", MDC.get("creditorFiscalCode"));
      assertEquals("{\"receiptId\":\"receipt\"}", scope.summaryArgs());
      assertNull(MDC.get("args"));
      assertFalse(ContextSnapshot.capture().fields().containsKey("args"));
      assertFalse(scope.hasDiagnosis());
      scope.recordDiagnosis();
      assertTrue(scope.hasDiagnosis());
      scope.setPaymentLogContext(null, null);
      assertNull(MDC.get("noticeNumber"));
      assertNull(MDC.get("creditorFiscalCode"));
    }
    assertNull(OperationLogScope.currentOrNull());
    assertNull(MDC.get("args"));
  }

  @Test
  void newOperationDropsAllStaleFieldsAndRestoresExactPriorMap() {
    Map<String, String> previous = staleContext();
    MDC.setContextMap(previous);
    try (OperationLogScope scope = OperationLogScope.open(" supplied request ", "paSendRT")) {
      assertSame(scope, OperationLogScope.current());
      assertEquals(Map.of(
          "requestId", " supplied request ", "operationId", MDC.get("operationId"),
          "method", "paSendRT"), MDC.getCopyOfContextMap());
      UUID.fromString(MDC.get("operationId"));
      MDC.put("status", "KO");
    }
    assertEquals(previous, MDC.getCopyOfContextMap());
    assertThrows(IllegalStateException.class, OperationLogScope::current);
  }

  @Test
  void nestedOperationsRestoreOuterContextIncludingItsCompletionFields() {
    try (OperationLogScope outer = OperationLogScope.open("outer")) {
      MDC.put("status", "OK");
      Map<String, String> before = MDC.getCopyOfContextMap();
      try (OperationLogScope inner = OperationLogScope.open("inner", "read")) {
        assertNotEquals(before.get("operationId"), MDC.get("operationId"));
        assertNull(MDC.get("status"));
        assertSame(inner, OperationLogScope.current());
      }
      assertSame(outer, OperationLogScope.current());
      assertEquals(before, MDC.getCopyOfContextMap());
    }
    assertNull(MDC.getCopyOfContextMap());
  }

  @Test
  void nestedOperationsRestoreExactlyOnException() {
    MDC.setContextMap(staleContext());
    Map<String, String> before = MDC.getCopyOfContextMap();
    assertThrows(IllegalArgumentException.class, () -> {
      try (OperationLogScope outer = OperationLogScope.open("outer")) {
        Map<String, String> outerContext = MDC.getCopyOfContextMap();
        try {
          try (OperationLogScope inner = OperationLogScope.open(null)) {
            MDC.put("ctx_details", "failed");
            throw new IllegalArgumentException("failure");
          }
        } finally {
          assertEquals(outerContext, MDC.getCopyOfContextMap());
          assertSame(outer, OperationLogScope.current());
        }
      }
    });
    assertEquals(before, MDC.getCopyOfContextMap());
  }

  @Test
  void paymentScopesPreserveLeadingZerosReplaceMissingValuesAndRestoreOnException() {
    try (OperationLogScope operation = OperationLogScope.open("request", "read");
        OperationLogScope first = operation.payment("0000123", "00123456789")) {
      Map<String, String> before = MDC.getCopyOfContextMap();
      assertEquals("0000123", MDC.get("noticeNumber"));
      assertThrows(IllegalStateException.class, () -> {
        try (OperationLogScope second = first.payment(null, null)) {
          assertNull(MDC.get("noticeNumber"));
          assertNull(MDC.get("creditorFiscalCode"));
          assertEquals(before.get("operationId"), MDC.get("operationId"));
          assertEquals("read", MDC.get("method"));
          throw new IllegalStateException("payment failure");
        }
      });
      assertEquals(before, MDC.getCopyOfContextMap());
    }
    assertNull(MDC.getCopyOfContextMap());
  }

  @Test
  void paymentEnrichmentPreservesCurrentOperationMetadataButRestoresItsOwnChanges() {
    try (OperationLogScope operation = OperationLogScope.open("request", "read")) {
      MDC.put("startTime", "123456");
      MDC.put("status", "OK");
      Map<String, String> before = MDC.getCopyOfContextMap();
      try (OperationLogScope payment = operation.payment("0000123", "00123456789")) {
        assertEquals("123456", MDC.get("startTime"));
        assertEquals("OK", MDC.get("status"));
        assertEquals(before.get("operationId"), MDC.get("operationId"));
        assertEquals("0000123", MDC.get("noticeNumber"));
        MDC.put("status", "KO");
        try (OperationLogScope cleared = payment.payment(null, null)) {
          assertNull(MDC.get("noticeNumber"));
          assertNull(MDC.get("creditorFiscalCode"));
          assertEquals("123456", MDC.get("startTime"));
          assertEquals("KO", MDC.get("status"));
        }
        assertEquals("0000123", MDC.get("noticeNumber"));
      }
      assertEquals(before, MDC.getCopyOfContextMap());
    }
  }

  @Test
  void generatesValidFreshIdsForEveryExecutionAndForNullOrEmptyRequestId() {
    String firstOperation;
    String firstRequest;
    try (OperationLogScope ignored = OperationLogScope.open(null)) {
      firstOperation = MDC.get("operationId");
      firstRequest = MDC.get("requestId");
      UUID.fromString(firstOperation);
      UUID.fromString(firstRequest);
      assertNotEquals(firstOperation, firstRequest);
    }
    try (OperationLogScope ignored = OperationLogScope.open("")) {
      UUID.fromString(MDC.get("requestId"));
      UUID.fromString(MDC.get("operationId"));
      assertNotEquals(firstOperation, MDC.get("operationId"));
      assertNotEquals(firstRequest, MDC.get("requestId"));
    }
    try (OperationLogScope ignored = OperationLogScope.open(" ")) {
      assertEquals(" ", MDC.get("requestId"));
      assertNotEquals(firstOperation, MDC.get("operationId"));
    }
  }

  @Test
  void restoresAnEmptyMapWithoutChangingItToAnAbsentMap() {
    MDC.setContextMap(Map.of());
    try (OperationLogScope ignored = OperationLogScope.open("request")) {
      assertNotNull(MDC.get("operationId"));
    }
    assertEquals(Map.of(), MDC.getCopyOfContextMap());
  }

  @Test
  void rejectsOutOfOrderCloseWithoutChangingContextAndAllowsRepeatedClose() {
    OperationLogScope outer = OperationLogScope.open("outer");
    OperationLogScope inner = OperationLogScope.open("inner");
    try {
      Map<String, String> before = MDC.getCopyOfContextMap();
      assertThrows(IllegalStateException.class, outer::close);
      assertEquals(before, MDC.getCopyOfContextMap());
    } finally {
      inner.close();
      outer.close();
    }
    outer.close();
    assertNull(MDC.getCopyOfContextMap());
  }

  @Test
  void pendingBoundaryFailureSharesPaymentStateAndIsTakenOnlyOnce() {
    Throwable original = new Exception("original");
    try (OperationLogScope operation = OperationLogScope.open("request")) {
      assertNull(operation.takeBoundaryFailure());
      try (OperationLogScope payment = operation.payment("0000123", "creditor")) {
        payment.rememberBoundaryFailure(original);
      }
      assertSame(original, operation.takeBoundaryFailure());
      assertNull(operation.takeBoundaryFailure());
      operation.rememberBoundaryFailure(original);
      try (OperationLogScope independent = OperationLogScope.open("independent")) {
        assertNull(independent.takeBoundaryFailure());
      }
      assertSame(original, operation.takeBoundaryFailure());
      operation.rememberBoundaryFailure(original);
    }
    try (OperationLogScope next = OperationLogScope.open("next")) {
      assertNull(next.takeBoundaryFailure());
    }
  }

  @Test
  void rejectsCrossThreadAccessAndClosedScopeUse() throws Exception {
    var executor = Executors.newSingleThreadExecutor();
    OperationLogScope operation = OperationLogScope.open("request");
    try {
      executor.submit(() -> {
        assertThrows(IllegalStateException.class, operation::close);
        assertThrows(IllegalStateException.class, () -> operation.payment("nav", "creditor"));
        assertThrows(IllegalStateException.class,
            () -> operation.markHandled(new Exception()));
        assertThrows(IllegalStateException.class,
            () -> operation.dependencyFailure(new Exception()));
        assertThrows(IllegalStateException.class,
            () -> operation.rememberBoundaryFailure(new Exception()));
        assertThrows(IllegalStateException.class, operation::takeBoundaryFailure);
        assertThrows(IllegalStateException.class, OperationLogScope::current);
      }).get();
    } finally {
      executor.shutdownNow();
      operation.close();
    }
    assertThrows(IllegalStateException.class, () -> operation.payment("nav", "creditor"));
    assertThrows(IllegalStateException.class,
        () -> operation.rememberBoundaryFailure(new Exception()));
    assertThrows(IllegalStateException.class, operation::takeBoundaryFailure);
  }

  static Map<String, String> staleContext() {
    Map<String, String> fields = new HashMap<>();
    for (String key : new String[] {
        "requestId", "operationId", "jobRunId", "messageId", "noticeNumber",
        "creditorFiscalCode", "method", "args", "status", "httpCode", "soapOutcome",
        "error.code", "error.type", "error.message", "error.stack_trace", "faultCode",
        "faultDetail", "ctx_details", "event.code", "startTime", "responseTime",
        "soapTechnicalErrorLogged", "unreviewed-field"}) {
      fields.put(key, "old-" + key);
    }
    return fields;
  }

  static void restore(Map<String, String> fields) {
    if (fields == null) {
      MDC.clear();
    } else {
      MDC.setContextMap(fields);
    }
  }
}
