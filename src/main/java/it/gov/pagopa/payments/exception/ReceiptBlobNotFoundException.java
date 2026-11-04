package it.gov.pagopa.payments.exception;

public class ReceiptBlobNotFoundException extends RuntimeException {
  public ReceiptBlobNotFoundException(String cf, String iuv, Throwable cause) {
    super("Blob not found for receipt cf=" + cf + ", iuv=" + iuv, cause);
  }
}
