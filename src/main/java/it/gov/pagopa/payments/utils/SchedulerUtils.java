package it.gov.pagopa.payments.utils;

import static it.gov.pagopa.payments.config.LoggingAspect.*;

import java.util.Calendar;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

@Slf4j
public class SchedulerUtils {

  public static void updateMDCForStartExecution(String method, String args) {
    MDC.put(EVENT_ACTION, method);
    MDC.put(START_TIME, String.valueOf(Calendar.getInstance().getTimeInMillis()));
    MDC.put(CORRELATION_ID, UUID.randomUUID().toString());
    MDC.put(CTX_DETAILS_ARGS, args);
  }

  public static void updateMDCForEndExecution() {
    MDC.put(EVENT_OUTCOME, "success");
    String executionTime = getExecutionTime();
    if (executionTime != null && !"-".equals(executionTime)) {
      MDC.put(CTX_DETAILS_RESPONSE_TIME, executionTime);
    }
    log.info("Scheduled job finished successfully");
    
    MDC.clear(); // Safe to clear all MDC since the scheduled job execution is over
  }

  public static void updateMDCError(Exception e, String method) {
    MDC.put(EVENT_OUTCOME, "failure");
    String executionTime = getExecutionTime();
    if (executionTime != null && !"-".equals(executionTime)) {
      MDC.put(CTX_DETAILS_RESPONSE_TIME, executionTime);
    }
    
    log.error("An error occurred during a scheduled job", e);
    
    MDC.clear(); // Safe to clear all MDC since the scheduled job execution is over
  }
}
