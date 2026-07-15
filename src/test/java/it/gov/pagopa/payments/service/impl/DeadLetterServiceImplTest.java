package it.gov.pagopa.payments.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import it.gov.pagopa.payments.client.BlobStorageClient;
import it.gov.pagopa.payments.model.DeadLetterMessage;
import it.gov.pagopa.payments.model.enumeration.DeadLetterReason;
import java.time.Instant;
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
}