package it.gov.pagopa.payments.exception;

public class DeadLetterNotFoundException extends RuntimeException {

	private static final long serialVersionUID = -1381626398476851536L;

	public DeadLetterNotFoundException(String fileName, Throwable cause) {
        super("Dead-letter message not found: " + fileName, cause);
    }
}