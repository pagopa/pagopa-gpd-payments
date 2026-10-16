package it.gov.pagopa.payments.utils;

import static it.gov.pagopa.payments.config.LoggingAspect.*;

import it.gov.pagopa.payments.logging.LogEventWriter;
import it.gov.pagopa.payments.logging.FailureDiagnostics;
import it.gov.pagopa.payments.logging.LogEvent;
import java.util.Calendar;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

@Slf4j
public class SchedulerUtils {

  private static final LogEventWriter LOG_EVENTS = new LogEventWriter(log);

  public static void updateMDCForStartExecution(String method, String args) {
    MDC.remove(ARGS);
    MDC.remove(FAULT_DETAIL);
    MDC.put(METHOD, method);
    MDC.put(START_TIME, String.valueOf(Calendar.getInstance().getTimeInMillis()));
    MDC.put(REQUEST_ID, UUID.randomUUID().toString());
    MDC.put(OPERATION_ID, UUID.randomUUID().toString());
  }

  public static void updateMDCForEndExecution() {
    MDC.remove(ARGS);
    MDC.remove(FAULT_DETAIL);
    MDC.put(STATUS, "OK");
    MDC.put(CODE, "201");
    MDC.put(RESPONSE_TIME, getExecutionTime());
    LOG_EVENTS.logEvent(LogEvent.SCHEDULED_JOB_COMPLETED);
  }

  public static void updateMDCError(Exception e, String method) {
    MDC.remove(ARGS);
    MDC.remove(FAULT_DETAIL);
    MDC.put(STATUS, "KO");
    MDC.put(CODE, "500");
    MDC.put(RESPONSE_TIME, getExecutionTime());
    MDC.put(FAULT_CODE, method);
    FailureDiagnostics.technicalFailure(LOG_EVENTS, e);
    LOG_EVENTS.logEvent(LogEvent.SCHEDULED_JOB_COMPLETED);
  }
}
