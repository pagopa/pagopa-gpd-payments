package it.gov.pagopa.payments.service;

import it.gov.pagopa.payments.model.DeadLetterMessage;

public interface DeadLetterService {

    /**
     * Persists a terminally failed message in the dead-letter storage.
     *
     * <p>If persistence fails, the exception must propagate to the caller so that
     * the original queue message is not deleted.
     */
    void sendToDeadLetter(DeadLetterMessage message);
}