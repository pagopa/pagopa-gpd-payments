package it.gov.pagopa.payments.service;

import it.gov.pagopa.payments.model.DeadLetterMessage;
import it.gov.pagopa.payments.model.DeadLetterMessageSummary;
import java.util.List;

public interface DeadLetterService {

    /**
     * Persists a terminally failed message in the dead-letter storage.
     *
     * <p>If persistence fails, the exception must propagate to the caller so that
     * the original queue message is not deleted.
     */
    void sendToDeadLetter(DeadLetterMessage message);

    /**
     * Returns dead-letter messages without exposing the original receipt payload.
     *
     * @param maxMessages maximum number of messages to return
     * @return dead-letter message summaries
     */
    List<DeadLetterMessageSummary> getDeadLetters(int maxMessages);

    /**
     * Returns the complete dead-letter message stored in the specified blob.
     *
     * @param fileName full blob name including the .json extension
     * @return complete dead-letter message
     */
    DeadLetterMessage getDeadLetter(String fileName);
}