package it.gov.pagopa.payments.utils;

import static it.gov.pagopa.payments.config.LoggingAspect.*;

import java.util.Calendar;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

@Slf4j
public class SchedulerUtils {

  public static void updateMDCForStartExecution(String method, String args) {
  }

  public static void updateMDCForEndExecution() {
    log.info("Scheduled job finished successfully");
  }

  public static void updateMDCError(Exception e, String method) {
    log.info("An error occurring during a scheduled job");
  }
}
