package it.gov.pagopa.payments.exception;

public class ReceiptInconsistentException extends RuntimeException {
  public ReceiptInconsistentException(String cf, String iuv) {
    super("Receipt has neither contentHash nor payload: cf=" + cf + ", iuv=" + iuv);
  }
}
