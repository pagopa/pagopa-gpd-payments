package it.gov.pagopa.payments.utils;

import it.gov.pagopa.payments.config.LogContext;
import it.gov.pagopa.payments.config.LogMessages;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.event.Level;

@Slf4j
public final class SchedulerUtils {
  private SchedulerUtils() {}

  public static void logCompletion(long started, Exception failure) {
    if (failure != null) {
      LogContext.diagnostic(log, Level.ERROR, LogMessages.BACKGROUND_OPERATION_FAILED, failure);
    }
    LogContext.log(log, Level.INFO, LogMessages.BACKGROUND_OPERATION_COMPLETED, LogContext.event()
        .put(LogContext.STATUS, failure == null ? LogContext.STATUS_OK : LogContext.STATUS_KO)
        .put(LogContext.RESPONSE_TIME, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
  }
}
