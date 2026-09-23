package it.gov.pagopa.payments.utils;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SchedulerUtils {

  public static void updateMDCForEndExecution() {
    log.info("Scheduled job finished successfully");
  }

  public static void updateMDCError(Exception e, String method) {
    log.info("An error occurring during a scheduled job");
  }
}
