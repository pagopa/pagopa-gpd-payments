package it.gov.pagopa.payments.model;

import it.gov.pagopa.payments.model.enumeration.DeadLetterReason;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeadLetterMessageSummary {

    private String fileName;
    private String messageId;
    private Long dequeueCount;
    private DeadLetterReason reason;
    private Instant deadLetteredAt;
}