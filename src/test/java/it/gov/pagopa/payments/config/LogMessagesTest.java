package it.gov.pagopa.payments.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LogMessagesTest {
  @Test
  void structuredEventMessagesPreserveTheElkContract() {
    assertEquals("Completed API operation", LogMessages.API_OPERATION_COMPLETED);
    assertEquals("Completed I/O operation", LogMessages.IO_OPERATION_COMPLETED);
    assertEquals("Completed Azure HTTP attempt", LogMessages.AZURE_HTTP_ATTEMPT_COMPLETED);
    assertEquals("Completed Feign HTTP attempt", LogMessages.FEIGN_HTTP_ATTEMPT_COMPLETED);
    assertEquals("Completed background operation", LogMessages.BACKGROUND_OPERATION_COMPLETED);
    assertEquals("Background operation failed", LogMessages.BACKGROUND_OPERATION_FAILED);
    assertEquals("HTTP request processing failed", LogMessages.HTTP_REQUEST_PROCESSING_FAILED);
    assertEquals("SOAP response XML validation failed", LogMessages.SOAP_RESPONSE_VALIDATION_FAILED);
  }
}
