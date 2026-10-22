package it.gov.pagopa.payments.logging;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** Logs each job/message independently, restoring the caller even for nested executions. */
public final class BackgroundExecutionLogging {
  private static final Logger LOG = LoggerFactory.getLogger(BackgroundExecutionLogging.class);
  private boolean failed;

  private BackgroundExecutionLogging() {}

  public static void runWithLogging(
      String method, Consumer<BackgroundExecutionLogging> action) {
    Map<String, String> previous = MDC.getCopyOfContextMap();
    long started = System.nanoTime();
    BackgroundExecutionLogging execution = new BackgroundExecutionLogging();
    try {
      MDC.clear();
      MDC.put(HttpRequestLogContext.CORRELATION_ID, UUID.randomUUID().toString());
      MDC.put(HttpRequestLogContext.METHOD, method);
      action.accept(execution);
    } catch (RuntimeException | Error failure) {
      execution.markFailureForLogging();
      throw failure;
    } finally {
      try {
        MDC.put(HttpRequestLogContext.STATUS, execution.failed ? "KO" : "OK");
        LOG.info("Background execution completed", new StructuredLogFields(
            JsonNodeFactory.instance.objectNode()
                .put("event.duration", Math.max(0L, System.nanoTime() - started))));
      } finally {
        if (previous == null) {
          MDC.clear();
        } else {
          MDC.setContextMap(previous);
        }
      }
    }
  }

  /** Marks an absorbed/rescheduled failure without changing the application's control flow. */
  public void markFailureForLogging() {
    failed = true;
  }

  public void recordPaymentLogContext(String creditorId, String noticeNumber, String iuv) {
    putPaymentLogField("ctx_creditor_id", creditorId);
    putPaymentLogField("ctx_notice_number", noticeNumber);
    putPaymentLogField("ctx_iuv", iuv);
  }

  private void putPaymentLogField(String field, String value) {
    if (value == null) {
      MDC.remove(field);
    } else {
      MDC.put(field, value);
    }
  }
}
