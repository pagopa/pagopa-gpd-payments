package it.gov.pagopa.payments.exception;

public class DeadLetterAccessException extends RuntimeException {

	private static final long serialVersionUID = -7346060962614791012L;

	public DeadLetterAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}