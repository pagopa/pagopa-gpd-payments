package it.gov.pagopa.payments.logging;

import org.slf4j.event.Level;

/**
 * Stable event codes, messages and default severities shared by application emitters.
 * Enum names are the JSON {@code event.code} contract, not Java-only identifiers.
 */
public enum LogEvent {
  SOAP_OPERATION_COMPLETED("SOAP operation completed", Level.INFO),
  REST_OPERATION_COMPLETED("REST operation completed", Level.INFO),
  PAYMENT_ELIGIBILITY_REJECTED("Payment eligibility rejected", Level.INFO),
  SOAP_REQUEST_VALIDATION_FAILED("SOAP request XSD validation failed", Level.WARN),
  DEPENDENCY_CALL_COMPLETED("Dependency call completed", Level.DEBUG),
  DEPENDENCY_CALL_FAILED("Dependency call failed", Level.ERROR),
  RECEIPT_RETRY_SCHEDULED("Receipt retry scheduled", Level.INFO),
  RECEIPT_PROCESSING_COMPLETED("Receipt processing completed", Level.INFO),
  RECEIPT_DEAD_LETTERED("Receipt moved to dead letter", Level.WARN),
  SCHEDULED_JOB_COMPLETED("Scheduled job completed", Level.INFO),
  OPERATION_TECHNICAL_FAILED("Operation failed technically", Level.ERROR);

  private final String message;
  private final Level defaultLevel;

  LogEvent(String message, Level defaultLevel) {
    this.message = message;
    this.defaultLevel = defaultLevel;
  }

  public String code() {
    return name();
  }

  public String message() {
    return message;
  }

  public Level defaultLevel() {
    return defaultLevel;
  }

  /** Restricts each event to its approved detail projection; absent details are allowed. */
  boolean accepts(EventDetails details) {
    if (details == null) {
      return true;
    }
    return switch (this) {
      case PAYMENT_ELIGIBILITY_REJECTED -> details instanceof EventDetails.Eligibility;
      case SOAP_REQUEST_VALIDATION_FAILED -> details instanceof EventDetails.Validation;
      case DEPENDENCY_CALL_COMPLETED, DEPENDENCY_CALL_FAILED ->
          details instanceof EventDetails.DependencyCall;
      case RECEIPT_RETRY_SCHEDULED -> details instanceof EventDetails.Retry;
      case RECEIPT_PROCESSING_COMPLETED, RECEIPT_DEAD_LETTERED ->
          details instanceof EventDetails.ReceiptDisposition;
      case SCHEDULED_JOB_COMPLETED -> details instanceof EventDetails.JobSummary;
      default -> false;
    };
  }
}
