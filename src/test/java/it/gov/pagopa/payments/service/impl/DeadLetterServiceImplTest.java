package it.gov.pagopa.payments.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import it.gov.pagopa.payments.client.BlobStorageClient;
import it.gov.pagopa.payments.exception.DeadLetterAccessException;
import it.gov.pagopa.payments.model.DeadLetterMessage;
import it.gov.pagopa.payments.model.DeadLetterMessageSummary;
import it.gov.pagopa.payments.model.enumeration.DeadLetterReason;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class DeadLetterServiceImplTest {

    @Mock
    private BlobStorageClient blobStorageClient;

    private ObjectMapper objectMapper;

    private DeadLetterServiceImpl sut;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        sut = new DeadLetterServiceImpl(blobStorageClient, objectMapper);
    }

    @Test
    void sendToDeadLetter_shouldPersistMessageAsJson() throws Exception {
        Instant deadLetteredAt =
                Instant.parse("2026-08-27T14:30:00Z");

        DeadLetterMessage message =
                DeadLetterMessage.builder()
                        .messageId("message-123")
                        .dequeueCount(6L)
                        .reason(
                                DeadLetterReason.MAX_RETRY_ATTEMPTS_REACHED)
                        .deadLetteredAt(deadLetteredAt)
                        .originalMessage("<paSendRT>test</paSendRT>")
                        .build();

        ArgumentCaptor<String> jsonCaptor =
                ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> filePathCaptor =
                ArgumentCaptor.forClass(String.class);

        sut.sendToDeadLetter(message);

        verify(blobStorageClient)
                .saveStringJsonToBlobStorage(
                        jsonCaptor.capture(),
                        filePathCaptor.capture());

        DeadLetterMessage storedMessage =
                objectMapper.readValue(
                        jsonCaptor.getValue(),
                        DeadLetterMessage.class);

        assertEquals("message-123", storedMessage.getMessageId());
        assertEquals(6, storedMessage.getDequeueCount());
        assertEquals(
        		DeadLetterReason.MAX_RETRY_ATTEMPTS_REACHED,
                storedMessage.getReason());
        assertEquals(
                "<paSendRT>test</paSendRT>",
                storedMessage.getOriginalMessage());

        assertTrue(
        	    filePathCaptor.getValue()
        	        .startsWith(
        	            "2026/08/27/14/message-123/"
        	            + "MAX_RETRY_ATTEMPTS_REACHED_"));
    }
    
    @Test
    void getDeadLettersShouldReturnMessageSummaries() throws Exception {

        String fileName =
                "2026/08/31/10/message-1/"
                        + "MAX_RETRY_ATTEMPTS_REACHED_1000.json";

        Instant deadLetteredAt =
                Instant.parse("2026-08-31T10:00:00Z");

        DeadLetterMessage message =
                DeadLetterMessage.builder()
                        .messageId("message-1")
                        .dequeueCount(11L)
                        .reason(DeadLetterReason.MAX_RETRY_ATTEMPTS_REACHED)
                        .deadLetteredAt(deadLetteredAt)
                        .originalMessage("<paSendRT>payload</paSendRT>")
                        .build();

        String json =
                objectMapper.writeValueAsString(message);

        when(blobStorageClient.listJsonBlobs(10))
                .thenReturn(List.of(fileName));

        when(blobStorageClient.getStringJsonFromBlobStorage(fileName))
                .thenReturn(json);

        List<DeadLetterMessageSummary> result =
                sut.getDeadLetters(10);

        assertEquals(1, result.size());

        DeadLetterMessageSummary summary =
                result.get(0);

        assertEquals(
                fileName,
                summary.getFileName());

        assertEquals(
                "message-1",
                summary.getMessageId());

        assertEquals(
                11L,
                summary.getDequeueCount());

        assertEquals(
                DeadLetterReason.MAX_RETRY_ATTEMPTS_REACHED,
                summary.getReason());

        assertEquals(
                deadLetteredAt,
                summary.getDeadLetteredAt());
    }
    
    @Test
    void getDeadLetterShouldReturnCompleteMessage() throws Exception {

        String fileName =
                "2026/08/31/10/message-1/"
                        + "MAX_RETRY_ATTEMPTS_REACHED_1000.json";

        Instant deadLetteredAt =
                Instant.parse("2026-08-31T10:00:00Z");

        DeadLetterMessage expected =
                DeadLetterMessage.builder()
                        .messageId("message-1")
                        .dequeueCount(11L)
                        .reason(DeadLetterReason.MAX_RETRY_ATTEMPTS_REACHED)
                        .deadLetteredAt(deadLetteredAt)
                        .originalMessage("<paSendRT>payload</paSendRT>")
                        .build();

        String json =
                objectMapper.writeValueAsString(expected);

        when(blobStorageClient.getStringJsonFromBlobStorage(fileName))
                .thenReturn(json);

        DeadLetterMessage result =
                sut.getDeadLetter(fileName);

        assertEquals(
                "message-1",
                result.getMessageId());

        assertEquals(
                11L,
                result.getDequeueCount());

        assertEquals(
                DeadLetterReason.MAX_RETRY_ATTEMPTS_REACHED,
                result.getReason());

        assertEquals(
                deadLetteredAt,
                result.getDeadLetteredAt());

        assertEquals(
                "<paSendRT>payload</paSendRT>",
                result.getOriginalMessage());
    }
    
    @Test
    void getDeadLetterShouldFailWhenStoredJsonIsInvalid() {

        String fileName =
                "2026/08/31/10/message-1/"
                        + "MAX_RETRY_ATTEMPTS_REACHED_1000.json";

        when(blobStorageClient.getStringJsonFromBlobStorage(fileName))
                .thenReturn("invalid-json");

        DeadLetterAccessException exception =
                assertThrows(
                        DeadLetterAccessException.class,
                        () -> sut.getDeadLetter(fileName));

        assertEquals(
                "Unable to deserialize dead-letter message",
                exception.getMessage());
    }
}