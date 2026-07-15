package it.gov.pagopa.payments.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.gov.pagopa.payments.client.BlobStorageClient;
import it.gov.pagopa.payments.model.DeadLetterMessage;
import it.gov.pagopa.payments.service.DeadLetterService;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class DeadLetterServiceImpl implements DeadLetterService {

    private final BlobStorageClient blobStorageClient;
    private final ObjectMapper objectMapper;

    @Override
    public void sendToDeadLetter(DeadLetterMessage message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            String filePath = buildFilePath(message);

            blobStorageClient.saveStringJsonToBlobStorage(json, filePath);

            log.info(
                    "Message persisted in dead-letter storage [messageId={},dequeueCount={},reason={}]",
                    message.getMessageId(),
                    message.getDequeueCount(),
                    message.getReason());

        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Unable to serialize dead-letter message " + message.getMessageId(),
                    e);
        }
    }

    private String buildFilePath(DeadLetterMessage message) {
        ZonedDateTime timestamp =
                message.getDeadLetteredAt().atZone(ZoneOffset.UTC);

        /*
         * UTC is used for Blob partitioning to keep dead-letter paths independent
         * from the timezone configured on the application pod.
         * Example path: yyyy/MM/dd/hh/messageId/reason_timestamp (2026/08/27/14/75f9c345.../MAX_RETRY_ATTEMPTS_REACHED_1787839200000.json)
         */
        return String.format(
                "%04d/%02d/%02d/%02d/%s/%s_%d",
                timestamp.getYear(),
                timestamp.getMonthValue(),
                timestamp.getDayOfMonth(),
                timestamp.getHour(),
                message.getMessageId(),
                message.getReason(),
                message.getDeadLetteredAt().toEpochMilli());
    }
}