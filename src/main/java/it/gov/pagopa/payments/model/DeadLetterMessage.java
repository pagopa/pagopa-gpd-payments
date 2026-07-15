package it.gov.pagopa.payments.model;

import java.time.Instant;

import it.gov.pagopa.payments.model.enumeration.DeadLetterReason;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeadLetterMessage {

    private String messageId;

    private Long dequeueCount;

    private DeadLetterReason reason;

    private Instant deadLetteredAt;

    private String originalMessage;
}