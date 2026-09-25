package it.gov.pagopa.payments.utils;

import static it.gov.pagopa.payments.config.LoggingAspect.ERROR_TYPE;
import static it.gov.pagopa.payments.config.LoggingAspect.EVENT_ACTION;
import static it.gov.pagopa.payments.config.LoggingAspect.EVENT_OUTCOME;
import static it.gov.pagopa.payments.config.LoggingAspect.OUTCOME_FAILURE;
import static it.gov.pagopa.payments.config.LoggingAspect.OUTCOME_SUCCESS;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

/** OER milestone of a scheduled job: {@code event_action} is the job name. */
@Slf4j
public class SchedulerUtils {

  private static final String JOB_COMPLETED = "Completed scheduled job";

  public static void logJobCompleted(String job) {
    MDC.put(EVENT_ACTION, job);
    MDC.put(EVENT_OUTCOME, OUTCOME_SUCCESS);
    log.info(JOB_COMPLETED);
  }

  /** No stack trace: the job rethrows and Spring's scheduler logs it at ERROR. */
  public static void logJobFailed(String job, Exception e) {
    MDC.put(EVENT_ACTION, job);
    MDC.put(EVENT_OUTCOME, OUTCOME_FAILURE);
    MDC.put(ERROR_TYPE, e.getClass().getName());
    log.info(JOB_COMPLETED);
  }
}
