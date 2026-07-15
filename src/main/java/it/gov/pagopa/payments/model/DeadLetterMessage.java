package it.gov.pagopa.payments.model;

import java.time.Instant;
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

    private Integer dequeueCount;

    private String reason;

    private Instant deadLetteredAt;

    private String originalMessage;
}