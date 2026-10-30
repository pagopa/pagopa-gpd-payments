package it.gov.pagopa.payments.config;

public final class LogMessages {
  public static final String API_OPERATION_COMPLETED = "Completed API operation"; // Final inbound API outcome
  public static final String IO_OPERATION_COMPLETED = "Completed I/O operation"; // Completed repository method invocation
  public static final String AZURE_HTTP_ATTEMPT_COMPLETED = "Completed Azure HTTP attempt"; // Single Azure transport attempt
  public static final String FEIGN_HTTP_ATTEMPT_COMPLETED = "Completed Feign HTTP attempt"; // Single Feign transport attempt
  public static final String BACKGROUND_OPERATION_COMPLETED = "Completed background operation"; // Scheduled job execution outcome
  public static final String BACKGROUND_OPERATION_FAILED = "Background operation failed"; // Unhandled scheduled job failure
  public static final String HTTP_REQUEST_PROCESSING_FAILED = "HTTP request processing failed"; // Unhandled inbound request failure
  public static final String SOAP_RESPONSE_VALIDATION_FAILED = "SOAP response XML validation failed"; // Invalid outgoing SOAP XML

  private LogMessages() {}
}
