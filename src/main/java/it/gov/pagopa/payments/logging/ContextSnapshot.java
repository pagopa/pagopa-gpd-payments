package it.gov.pagopa.payments.logging;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import org.slf4j.MDC;

/**
 * Immutable executor handoff: only reviewed correlation and payment identifiers cross threads.
 * Timing, method, event fields and diagnostic ownership deliberately stay with their owner.
 */
public record ContextSnapshot(
    String requestId,
    String operationId,
    String jobRunId,
    String messageId,
    String noticeNumber,
    String creditorFiscalCode) {

  public static ContextSnapshot capture() {
    return new ContextSnapshot(
        MDC.get("requestId"), MDC.get("operationId"), MDC.get("jobRunId"),
        MDC.get("messageId"), MDC.get("noticeNumber"), MDC.get("creditorFiscalCode"));
  }

  /** Continues the captured operation, with worker-local diagnostic ownership on every invocation. */
  public Runnable wrap(Runnable task) {
    Objects.requireNonNull(task, "task");
    return () -> {
      try (OperationScope ignored = OperationScope.resume(this)) {
        task.run();
      }
    };
  }

  /** Restores the worker's exact prior MDC even when the task throws. */
  public <T> Callable<T> wrap(Callable<T> task) {
    Objects.requireNonNull(task, "task");
    return () -> {
      try (OperationScope ignored = OperationScope.resume(this)) {
        return task.call();
      }
    };
  }

  /** Starts a distinct execution/attempt, retaining correlation/payment but replacing operationId. */
  public OperationScope openOperation(String method) {
    return OperationScope.start(this, method);
  }

  Map<String, String> fields() {
    Map<String, String> fields = new HashMap<>();
    put(fields, "requestId", requestId);
    put(fields, "operationId", operationId);
    put(fields, "jobRunId", jobRunId);
    put(fields, "messageId", messageId);
    put(fields, "noticeNumber", noticeNumber);
    put(fields, "creditorFiscalCode", creditorFiscalCode);
    return fields;
  }

  private static void put(Map<String, String> fields, String key, String value) {
    if (value != null) {
      fields.put(key, value);
    }
  }
}
