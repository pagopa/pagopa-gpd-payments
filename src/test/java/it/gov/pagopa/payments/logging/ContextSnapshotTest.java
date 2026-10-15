package it.gov.pagopa.payments.logging;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class ContextSnapshotTest {
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
  void capturesOnlyReviewedImmutableIdentifiersWithoutMethodTimingOrOutcome() {
    MDC.setContextMap(OperationScopeTest.staleContext());
    ContextSnapshot captured = ContextSnapshot.capture();
    MDC.clear();
    captured.wrap((Runnable) () -> assertEquals(reviewedFields(), MDC.getCopyOfContextMap())).run();
    assertNull(MDC.getCopyOfContextMap());
    assertEquals("old-requestId", captured.requestId());
    Map<String, String> mutableCopy = captured.fields();
    mutableCopy.put("requestId", "changed");
    assertEquals(reviewedFields(), captured.fields());
  }

  @Test
  void reusedWorkerRestoresItsExactContextAfterRunnableSuccessAndFailure() throws Exception {
    var executor = Executors.newSingleThreadExecutor();
    try {
      Map<String, String> workerBefore = OperationScopeTest.staleContext();
      executor.submit(() -> MDC.setContextMap(workerBefore)).get();
      ContextSnapshot captured;
      try (OperationScope operation = OperationScope.open("request", "method");
          OperationScope payment = operation.payment("000123", "00123456789")) {
        captured = ContextSnapshot.capture();
      }
      Runnable verify = () -> {
        assertEquals(captured.fields(), MDC.getCopyOfContextMap());
        MDC.put("status", "KO");
        MDC.put("ctx_details", "worker-details");
      };
      executor.submit(captured.wrap(verify)).get();
      assertEquals(workerBefore, executor.submit(MDC::getCopyOfContextMap).get());
      ExecutionException thrown = assertThrows(ExecutionException.class,
          () -> executor.submit(captured.wrap((Runnable) () -> {
            verify.run();
            throw new IllegalStateException("worker failure");
          })).get());
      assertEquals("worker failure", thrown.getCause().getMessage());
      assertEquals(workerBefore, executor.submit(MDC::getCopyOfContextMap).get());
      assertTrue(executor.submit(() -> {
        assertThrows(IllegalStateException.class, OperationScope::current);
        return true;
      }).get());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void callableRestoresAbsentWorkerContextOnCheckedExceptionAndSuccessfulReuse() throws Exception {
    var executor = Executors.newSingleThreadExecutor();
    ContextSnapshot snapshot = new ContextSnapshot("request", "operation", null, null, null, null);
    try {
      executor.submit(MDC::clear).get();
      IOException failure = new IOException("checked failure");
      Callable<String> task = snapshot.wrap((Callable<String>) () -> {
        assertEquals(snapshot.fields(), MDC.getCopyOfContextMap());
        MDC.put("args", "not transferable");
        throw failure;
      });
      assertSame(failure, assertThrows(ExecutionException.class,
          () -> executor.submit(task).get()).getCause());
      assertNull(executor.submit(MDC::getCopyOfContextMap).get());
      assertEquals("done", executor.submit(snapshot.wrap((Callable<String>) () -> {
        assertEquals(snapshot.fields(), MDC.getCopyOfContextMap());
        return "done";
      })).get());
      assertNull(executor.submit(MDC::getCopyOfContextMap).get());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void emptySnapshotDoesNotInheritWorkerIdsAndGeneratesIdsForEachExecution() {
    ContextSnapshot empty = ContextSnapshot.capture();
    MDC.setContextMap(OperationScopeTest.staleContext());
    String[] ids = new String[2];
    AtomicInteger calls = new AtomicInteger();
    Runnable wrapped = empty.wrap((Runnable) () -> {
      UUID.fromString(MDC.get("requestId"));
      UUID.fromString(MDC.get("operationId"));
      assertEquals(2, MDC.getCopyOfContextMap().size());
      ids[calls.getAndIncrement()] = MDC.get("operationId");
    });
    wrapped.run();
    wrapped.run();
    assertNotEquals(ids[0], ids[1]);
    assertEquals(OperationScopeTest.staleContext(), MDC.getCopyOfContextMap());
  }

  @Test
  void explicitNewAttemptRetainsCorrelationPaymentAndUsesFreshOperationId() {
    ContextSnapshot snapshot = new ContextSnapshot(
        "request", "previous-operation", "job", "message", "000123", "00123456789");
    String first;
    try (OperationScope ignored = snapshot.openOperation("processReceipt")) {
      first = MDC.get("operationId");
      UUID.fromString(first);
      assertNotEquals(snapshot.operationId(), first);
      assertEquals("job", MDC.get("jobRunId"));
      assertEquals("message", MDC.get("messageId"));
      assertEquals("000123", MDC.get("noticeNumber"));
      assertEquals("00123456789", MDC.get("creditorFiscalCode"));
      assertEquals("request", MDC.get("requestId"));
      assertEquals("processReceipt", MDC.get("method"));
    }
    try (OperationScope ignored = snapshot.openOperation("processReceipt")) {
      assertNotEquals(first, MDC.get("operationId"));
    }
    assertNull(MDC.getCopyOfContextMap());
  }

  @Test
  void synchronousWrapRestoresOuterScopeAndDoesNotShareItsDiagnosticOwnership() {
    Exception failure = new Exception("same instance");
    AtomicInteger emitted = new AtomicInteger();
    try (OperationScope outer = OperationScope.open("request")) {
      assertTrue(outer.diagnoseOnce(failure, emitted::incrementAndGet));
      ContextSnapshot snapshot = ContextSnapshot.capture();
      Runnable task = snapshot.wrap((Runnable) () ->
          assertTrue(OperationScope.current().diagnoseOnce(failure, emitted::incrementAndGet)));
      task.run();
      task.run();
      assertSame(outer, OperationScope.current());
      assertFalse(outer.diagnoseOnce(failure, emitted::incrementAndGet));
    }
    assertEquals(3, emitted.get());
  }

  @Test
  void workerDoesNotShareParentOwnershipOrReusedInvocationOwnership() throws Exception {
    var executor = Executors.newSingleThreadExecutor();
    AtomicInteger emitted = new AtomicInteger();
    Exception failure = new Exception("same instance");
    try (OperationScope parent = OperationScope.open("request")) {
      parent.diagnoseOnce(failure, emitted::incrementAndGet);
      Runnable task = ContextSnapshot.capture().wrap((Runnable) () -> {
        OperationScope local = OperationScope.current();
        assertTrue(local.diagnoseOnce(failure, emitted::incrementAndGet));
        assertFalse(local.diagnoseOnce(failure, emitted::incrementAndGet));
      });
      executor.submit(task).get();
      executor.submit(task).get();
      assertFalse(parent.diagnoseOnce(failure, emitted::incrementAndGet));
      assertEquals(3, emitted.get());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void rejectsNullTasksAtCaptureBoundary() {
    ContextSnapshot empty = ContextSnapshot.capture();
    assertThrows(NullPointerException.class, () -> empty.wrap((Runnable) null));
    assertThrows(NullPointerException.class, () -> empty.wrap((Callable<?>) null));
  }

  private static Map<String, String> reviewedFields() {
    return Map.of(
        "requestId", "old-requestId", "operationId", "old-operationId",
        "jobRunId", "old-jobRunId", "messageId", "old-messageId",
        "noticeNumber", "old-noticeNumber", "creditorFiscalCode", "old-creditorFiscalCode");
  }
}
