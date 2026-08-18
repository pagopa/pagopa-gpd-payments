package it.gov.pagopa.payments.utils;

import static it.gov.pagopa.payments.config.LoggingAspect.CORRELATION_ID;
import static it.gov.pagopa.payments.config.LoggingAspect.CTX_DETAILS_RESPONSE_TIME;
import static it.gov.pagopa.payments.config.LoggingAspect.ERROR_MESSAGE;
import static it.gov.pagopa.payments.config.LoggingAspect.ERROR_TYPE;
import static it.gov.pagopa.payments.config.LoggingAspect.EVENT_ACTION;
import static it.gov.pagopa.payments.config.LoggingAspect.EVENT_OUTCOME;
import static it.gov.pagopa.payments.config.LoggingAspect.OUTCOME_FAILURE;
import static it.gov.pagopa.payments.config.LoggingAspect.OUTCOME_SUCCESS;
import static it.gov.pagopa.payments.config.LoggingAspect.START_TIME;
import static it.gov.pagopa.payments.config.LoggingAspect.getExecutionTime;

import java.util.Calendar;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

/** Milestone logging of the scheduled jobs: one event when the job execution is over. */
@Slf4j
public class SchedulerUtils {

  private static final String JOB_COMPLETED = "Completed scheduled job";
  private static final String JOB_FAILED = "Failed scheduled job";

  private SchedulerUtils() {
    // utility class
  }

  public static void updateMDCForStartExecution(String method) {
    MDC.put(EVENT_ACTION, method);
    MDC.put(START_TIME, String.valueOf(Calendar.getInstance().getTimeInMillis()));
    MDC.put(CORRELATION_ID, UUID.randomUUID().toString());
  }

  public static void updateMDCForEndExecution() {
    MDC.put(EVENT_OUTCOME, OUTCOME_SUCCESS);
    String executionTime = getExecutionTime();
    if (executionTime != null && !"-".equals(executionTime)) {
      MDC.put(CTX_DETAILS_RESPONSE_TIME, executionTime);
    }
    log.info(JOB_COMPLETED);

    MDC.clear(); // Safe to clear all MDC since the scheduled job execution is over
  }

  public static void updateMDCError(Exception e) {
    MDC.put(EVENT_OUTCOME, OUTCOME_FAILURE);
    String executionTime = getExecutionTime();
    if (executionTime != null && !"-".equals(executionTime)) {
      MDC.put(CTX_DETAILS_RESPONSE_TIME, executionTime);
    }
    MDC.put(ERROR_TYPE, e.getClass().getName());
    String message = LogMasker.redact(e.getMessage());
    if (message != null) {
      MDC.put(ERROR_MESSAGE, message);
    }

    log.error(JOB_FAILED, e);

    MDC.clear(); // Safe to clear all MDC since the scheduled job execution is over
  }
}
