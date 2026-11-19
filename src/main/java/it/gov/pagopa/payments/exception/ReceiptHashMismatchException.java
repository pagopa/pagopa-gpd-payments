package it.gov.pagopa.payments.exception;

public class ReceiptHashMismatchException extends RuntimeException {
  public ReceiptHashMismatchException(String cf, String iuv) {
    super("Hash mismatch for receipt cf=" + cf + ", iuv=" + iuv);
  }
}
