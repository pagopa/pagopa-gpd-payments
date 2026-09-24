package it.gov.pagopa.payments.model;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(
        description =
                "Complete terminally failed retry message stored in dead-letter storage.")
public class DeadLetterMessage {

    @Schema(
            description = "Original Azure Queue message identifier.",
            example = "message-1")
    private String messageId;

    @Schema(
            description =
                    "Number of times the message was dequeued before being moved to dead-letter storage.",
            example = "11")
    private Long dequeueCount;

    @Schema(
            description = "Reason why the message was moved to dead-letter storage.",
            example = "MAX_RETRY_ATTEMPTS_REACHED")
    private DeadLetterReason reason;

    @Schema(
            description = "UTC timestamp when the message was moved to dead-letter storage.",
            example = "2026-08-31T10:00:00Z")
    private Instant deadLetteredAt;

    @Schema(
            description =
                    "Original paSendRT XML payload preserved for operational and forensic inspection.")
    private String originalMessage;
}