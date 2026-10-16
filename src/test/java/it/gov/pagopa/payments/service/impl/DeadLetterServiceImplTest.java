package it.gov.pagopa.payments.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.azure.core.http.HttpResponse;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobStorageException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import it.gov.pagopa.payments.client.BlobStorageClient;
import it.gov.pagopa.payments.client.impl.BlobStorageClientImpl;
import it.gov.pagopa.payments.exception.DeadLetterAccessException;
import it.gov.pagopa.payments.exception.DeadLetterNotFoundException;
import it.gov.pagopa.payments.logging.LogEventWriter;
import it.gov.pagopa.payments.logging.FailureDiagnostics;
import it.gov.pagopa.payments.logging.OperationLogScope;
import it.gov.pagopa.payments.model.DeadLetterMessage;
import it.gov.pagopa.payments.model.DeadLetterMessageSummary;
import it.gov.pagopa.payments.model.enumeration.DeadLetterReason;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.io.OutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class DeadLetterServiceImplTest {

    @Mock
    private BlobStorageClient blobStorageClient;

    private ObjectMapper objectMapper;

    private DeadLetterServiceImpl sut;

    private AutoCloseable mocks;
    private final Logger logger = (Logger) LoggerFactory.getLogger(DeadLetterServiceImpl.class);
    private final Logger blobLogger = (Logger) LoggerFactory.getLogger(BlobStorageClientImpl.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>() {
        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };
    private Level previousLevel;
    private Level previousBlobLevel;
    private Map<String, String> previousMdc;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        previousMdc = MDC.getCopyOfContextMap();
        MDC.clear();
        previousLevel = logger.getLevel();
        previousBlobLevel = blobLogger.getLevel();
        logger.setLevel(Level.DEBUG);
        blobLogger.setLevel(Level.DEBUG);
        logs.start();
        logger.addAppender(logs);
        blobLogger.addAppender(logs);

        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        sut = new DeadLetterServiceImpl(blobStorageClient, objectMapper);
    }

    @AfterEach
    void tearDown() throws Exception {
        logger.detachAppender(logs);
        blobLogger.detachAppender(logs);
        logger.setLevel(previousLevel);
        blobLogger.setLevel(previousBlobLevel);
        logs.stop();
        mocks.close();
        if (previousMdc == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(previousMdc);
        }
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
        assertEquals(objectMapper.writeValueAsString(message), jsonCaptor.getValue());
        assertTrue(logs.list.isEmpty(), "Persistence must not claim completed dead-letter disposition");
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
        assertTrue(logs.list.isEmpty(), "Deserialization propagates to the caller boundary");
        assertInstanceOf(JsonProcessingException.class, exception.getCause());
        assertNull(MDC.get("requestId"));
        assertNull(MDC.get("operationId"));
    }

    @Test
    void jsonEncodingFailureIsLocalTechnicalAndKeepsConversionAndCause() throws Exception {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        JsonProcessingException failure = new JsonProcessingException("encoding failed") {};
        DeadLetterMessage message = DeadLetterMessage.builder().messageId("message-123").build();
        when(failingMapper.writeValueAsString(message)).thenThrow(failure);
        sut = new DeadLetterServiceImpl(blobStorageClient, failingMapper);

        try (OperationLogScope ignored = OperationLogScope.open("encoding-request")) {
            IllegalStateException converted = assertThrows(IllegalStateException.class,
                    () -> sut.sendToDeadLetter(message));
            assertEquals("Unable to serialize dead-letter message message-123", converted.getMessage());
            assertSame(failure, converted.getCause());
            FailureDiagnostics.technicalFailure(new LogEventWriter(logger), converted);
        }
        assertTechnicalFailure();
        verifyNoInteractions(blobStorageClient);
    }

    @Test
    void jsonEncodingFailureWithoutScopeDoesNotInventIdsOrCallBlob() throws Exception {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        JsonProcessingException failure = new JsonProcessingException("encoding failed") {};
        DeadLetterMessage message = DeadLetterMessage.builder().messageId("message-123").build();
        when(failingMapper.writeValueAsString(message)).thenThrow(failure);
        sut = new DeadLetterServiceImpl(blobStorageClient, failingMapper);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> sut.sendToDeadLetter(message)).getCause());
        assertTrue(logs.list.isEmpty());
        assertNull(MDC.get("requestId"));
        assertNull(MDC.get("operationId"));
        verifyNoInteractions(blobStorageClient);
    }

    @Test
    void decodingFailureIsNotAttributedToSuccessfulBlobReadAndCallerDoesNotDuplicate() {
        when(blobStorageClient.getStringJsonFromBlobStorage("message.json")).thenReturn("invalid-json");
        try (OperationLogScope ignored = OperationLogScope.open("decoding-request")) {
            DeadLetterAccessException converted = assertThrows(DeadLetterAccessException.class,
                    () -> sut.getDeadLetter("message.json"));
            FailureDiagnostics.technicalFailure(new LogEventWriter(logger), converted);
        }
        assertTechnicalFailure();
    }

    @Test
    void actualBlobReadFailureKeepsDependencyOwnershipThroughServiceAndCaller() {
        BlobStorageException failure = storageFailure(503);
        BlobContainerClient container = mock(BlobContainerClient.class);
        BlobClient blob = mock(BlobClient.class);
        when(container.getBlobClient("message.json")).thenReturn(blob);
        doThrow(failure).when(blob).downloadStream(any(OutputStream.class));
        sut = new DeadLetterServiceImpl(new BlobStorageClientImpl(container), objectMapper);

        try (OperationLogScope ignored = OperationLogScope.open("storage-request")) {
            DeadLetterAccessException converted = assertThrows(DeadLetterAccessException.class,
                    () -> sut.getDeadLetter("message.json"));
            assertSame(failure, converted.getCause());
            FailureDiagnostics.technicalFailure(new LogEventWriter(logger), converted);
        }

        assertEquals(1, logs.list.size());
        assertEquals("DEPENDENCY_CALL_FAILED", logs.list.get(0).getMDCPropertyMap().get("event.code"));
        assertEquals(DeadLetterServiceImpl.class.getName(), logs.list.get(0).getLoggerName());
        assertNull(logs.list.get(0).getThrowableProxy());
    }

    @Test
    void actualBlobNotFoundRemainsBusinessAbsenceWithNoTechnicalEvent() {
        BlobStorageException absence = storageFailure(404);
        BlobContainerClient container = mock(BlobContainerClient.class);
        BlobClient blob = mock(BlobClient.class);
        when(container.getBlobClient("missing.json")).thenReturn(blob);
        doThrow(absence).when(blob).downloadStream(any(OutputStream.class));
        sut = new DeadLetterServiceImpl(new BlobStorageClientImpl(container), objectMapper);

        DeadLetterNotFoundException converted = assertThrows(DeadLetterNotFoundException.class,
                () -> sut.getDeadLetter("missing.json"));
        assertSame(absence, converted.getCause());
        assertTrue(logs.list.isEmpty());
    }

    private void assertTechnicalFailure() {
        assertEquals(1, logs.list.size());
        ILoggingEvent event = logs.list.get(0);
        assertEquals("OPERATION_TECHNICAL_FAILED", event.getMDCPropertyMap().get("event.code"));
        assertEquals(Level.ERROR, event.getLevel());
        assertNull(event.getMDCPropertyMap().get("ctx_details"));
        assertNull(event.getThrowableProxy());
        assertNull(event.getArgumentArray());
    }

    private BlobStorageException storageFailure(int status) {
        HttpResponse response = mock(HttpResponse.class);
        when(response.getStatusCode()).thenReturn(status);
        return new BlobStorageException("Blob call failed", response, null);
    }
}