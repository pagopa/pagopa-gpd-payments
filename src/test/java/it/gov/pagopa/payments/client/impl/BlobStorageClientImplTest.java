package it.gov.pagopa.payments.client.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.azure.core.http.rest.PagedIterable;
import com.azure.core.http.HttpResponse;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobItemProperties;
import com.azure.storage.blob.models.BlobStorageException;

import it.gov.pagopa.payments.exception.DeadLetterAccessException;
import it.gov.pagopa.payments.exception.DeadLetterNotFoundException;
import it.gov.pagopa.payments.logging.LogEventWriter;
import it.gov.pagopa.payments.logging.FailureDiagnostics;
import it.gov.pagopa.payments.logging.OperationLogScope;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

@ExtendWith(MockitoExtension.class)
class BlobStorageClientImplTest {

    @Mock
    private BlobContainerClient blobContainerClient;

    @Mock
    private BlobClient blobClient;

    @InjectMocks
    private BlobStorageClientImpl sut;

    private final Logger logger = (Logger) LoggerFactory.getLogger(BlobStorageClientImpl.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>() {
        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };
    private Level previousLevel;
    private Map<String, String> previousMdc;

    @BeforeEach
    void captureLogs() {
        previousMdc = MDC.getCopyOfContextMap();
        MDC.clear();
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void restoreLogs() {
        logger.detachAppender(logs);
        logger.setLevel(previousLevel);
        logs.stop();
        if (previousMdc == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(previousMdc);
        }
    }

    @Test
    void saveStringJsonToBlobStorage_shouldUploadJsonFile() {

        when(blobContainerClient.getBlobClient("path/message.json"))
                .thenReturn(blobClient);
        doAnswer(invocation -> {
            InputStream content = invocation.getArgument(0);
            assertArrayEquals("{\"messageId\":\"123\"}".getBytes(StandardCharsets.UTF_8),
                    content.readAllBytes());
            assertEquals(19L, (long) invocation.getArgument(1));
            return null;
        }).when(blobClient).upload(any(InputStream.class), anyLong(), eq(true));

        sut.saveStringJsonToBlobStorage(
                "{\"messageId\":\"123\"}",
                "path/message");

        verify(blobContainerClient)
                .getBlobClient("path/message.json");

		verify(blobClient).upload(any(InputStream.class), anyLong(), eq(true));
        assertTrue(logs.list.isEmpty());
    }
    
    @Test
    void listJsonBlobsShouldReturnMostRecentJsonFilesUpToConfiguredLimit() {

        @SuppressWarnings("unchecked")
        PagedIterable<BlobItem> pagedIterable =
                mock(PagedIterable.class);

        BlobItem olderBlob =
                new BlobItem()
                        .setName(
                                "2026/08/31/10/message-1/"
                                        + "MAX_RETRY_ATTEMPTS_REACHED_1000.json")
                        .setProperties(
                                new BlobItemProperties()
                                        .setLastModified(
                                                OffsetDateTime.parse(
                                                        "2026-08-31T10:00:00Z")));

        BlobItem newerBlob =
                new BlobItem()
                        .setName(
                                "2026/08/31/11/message-2/"
                                        + "MAX_RETRY_ATTEMPTS_REACHED_2000.json")
                        .setProperties(
                                new BlobItemProperties()
                                        .setLastModified(
                                                OffsetDateTime.parse(
                                                        "2026-08-31T11:00:00Z")));

        BlobItem nonJsonBlob =
                new BlobItem()
                        .setName("ignored.txt")
                        .setProperties(
                                new BlobItemProperties()
                                        .setLastModified(
                                                OffsetDateTime.parse(
                                                        "2026-08-31T12:00:00Z")));

        when(blobContainerClient.listBlobs())
                .thenReturn(pagedIterable);

        /*
         * The oldest JSON blob is intentionally returned first by Azure.
         * The client must sort by lastModified before applying the limit.
         */
        when(pagedIterable.stream())
                .thenReturn(
                        Stream.of(
                                nonJsonBlob,
                                olderBlob,
                                newerBlob));

        List<String> result =
                sut.listJsonBlobs(1);

        assertEquals(
                List.of(
                        "2026/08/31/11/message-2/"
                                + "MAX_RETRY_ATTEMPTS_REACHED_2000.json"),
                result);

        verify(blobContainerClient)
                .listBlobs();
        assertTrue(logs.list.isEmpty());
    }
    
    @Test
    void getStringJsonFromBlobStorageShouldReturnBlobContent() {

        String fileName =
                "2026/08/31/10/message-1/"
                        + "MAX_RETRY_ATTEMPTS_REACHED_1000.json";

        String json =
                "{\"messageId\":\"message-1\",\"dequeueCount\":11}";

        when(blobContainerClient.getBlobClient(fileName))
                .thenReturn(blobClient);

        doAnswer(
                invocation -> {
                    OutputStream outputStream =
                            invocation.getArgument(0);

                    outputStream.write(
                            json.getBytes(StandardCharsets.UTF_8));

                    return null;
                })
                .when(blobClient)
                .downloadStream(any(OutputStream.class));

        String result =
                sut.getStringJsonFromBlobStorage(fileName);

        assertEquals(
                json,
                result);
        assertTrue(logs.list.isEmpty());

        verify(blobContainerClient)
                .getBlobClient(fileName);

        verify(blobClient)
                .downloadStream(any(OutputStream.class));
    }
    
    @Test
    void getStringJsonFromBlobStorageShouldThrowNotFoundWhenBlobDoesNotExist() {

        String fileName =
                "2026/08/31/10/message-1/"
                        + "MAX_RETRY_ATTEMPTS_REACHED_1000.json";

        BlobStorageException storageException = storageFailure(404);

        when(blobContainerClient.getBlobClient(fileName))
                .thenReturn(blobClient);

        doThrow(storageException)
                .when(blobClient)
                .downloadStream(any(OutputStream.class));

        assertThrows(
                DeadLetterNotFoundException.class,
                () -> sut.getStringJsonFromBlobStorage(fileName));
        assertTrue(logs.list.isEmpty());
    }
    
    @Test
    void getStringJsonFromBlobStorageShouldThrowAccessExceptionOnStorageFailure() throws Exception {

        String fileName =
                "2026/08/31/10/message-1/"
                        + "MAX_RETRY_ATTEMPTS_REACHED_1000.json";

        BlobStorageException storageException = storageFailure(500);

        when(blobContainerClient.getBlobClient(fileName))
                .thenReturn(blobClient);

        doThrow(storageException)
                .when(blobClient)
                .downloadStream(any(OutputStream.class));

        DeadLetterAccessException converted = assertThrows(
                DeadLetterAccessException.class,
                () -> sut.getStringJsonFromBlobStorage(fileName));
        assertSame(storageException, converted.getCause());
        assertEquals("Unable to read dead-letter message: " + fileName, converted.getMessage());
        assertTrue(logs.list.isEmpty(), "Unscoped propagated failures belong to the caller");
        assertNull(MDC.get("operationId"));
    }

    @Test
    void uploadFailureIsUnchangedAndCallerDoesNotRediagnoseIt() throws Exception {
        BlobStorageException failure = storageFailure(503);
        when(blobContainerClient.getBlobClient("message.json")).thenReturn(blobClient);
        doThrow(failure).when(blobClient).upload(any(InputStream.class), anyLong(), eq(true));
        try (OperationLogScope ignored = OperationLogScope.open("blob-request")) {
            assertSame(failure, assertThrows(BlobStorageException.class,
                    () -> sut.saveStringJsonToBlobStorage("{\"unchanged\":\"è\"}", "message")));
            FailureDiagnostics.technicalFailure(new LogEventWriter(logger),
                    new IllegalStateException("caller wrapper", failure));
        }
        assertFailure("persistDeadLetter", 503);
        assertEquals("blob-request", logs.list.get(0).getMDCPropertyMap().get("requestId"));
    }

    @Test
    void uploadPreservesUtf8BytesAndLength() {
        String json = "{\"originalMessage\":\"è🙂\\n<paSendRT/>\"}";
        byte[] expected = json.getBytes(StandardCharsets.UTF_8);
        when(blobContainerClient.getBlobClient("message.json")).thenReturn(blobClient);
        doAnswer(invocation -> {
            InputStream content = invocation.getArgument(0);
            assertArrayEquals(expected, content.readAllBytes());
            assertEquals((long) expected.length, (long) invocation.getArgument(1));
            return null;
        }).when(blobClient).upload(any(InputStream.class), anyLong(), eq(true));
        sut.saveStringJsonToBlobStorage(json, "message");
        assertTrue(logs.list.isEmpty());
    }

    @Test
    void uploadFailureWithoutScopeBelongsToCallerAndDoesNotInventIds() throws Exception {
        BlobStorageException failure = storageFailure(503);
        assertEquals(503, failure.getStatusCode());
        when(blobContainerClient.getBlobClient("message.json")).thenReturn(blobClient);
        doThrow(failure).when(blobClient).upload(any(InputStream.class), anyLong(), eq(true));
        assertSame(failure, assertThrows(BlobStorageException.class,
                () -> sut.saveStringJsonToBlobStorage("{}", "message")));
        assertTrue(logs.list.isEmpty());
        assertNull(MDC.get("requestId"));
        assertNull(MDC.get("operationId"));
    }

    @Test
    void lazyListFailureIsConvertedWithCauseAndDiagnosedOnce() throws Exception {
        @SuppressWarnings("unchecked")
        PagedIterable<BlobItem> pages = mock(PagedIterable.class);
        BlobStorageException failure = storageFailure(503);
        when(blobContainerClient.listBlobs()).thenReturn(pages);
        when(pages.stream()).thenReturn(Stream.<BlobItem>generate(() -> { throw failure; }));

        try (OperationLogScope ignored = OperationLogScope.open("blob-list-request")) {
            DeadLetterAccessException converted = assertThrows(DeadLetterAccessException.class,
                    () -> sut.listJsonBlobs(10));
            assertEquals("Unable to list dead-letter messages", converted.getMessage());
            assertSame(failure, converted.getCause());
            FailureDiagnostics.technicalFailure(new LogEventWriter(logger), converted);
        }
        assertFailure("listDeadLetters", 503);
    }

    @Test
    void immediateListFailureWithoutScopeBelongsToCaller() throws Exception {
        BlobStorageException failure = storageFailure(500);
        assertEquals(500, failure.getStatusCode());
        when(blobContainerClient.listBlobs()).thenThrow(failure);
        assertSame(failure, assertThrows(DeadLetterAccessException.class,
                () -> sut.listJsonBlobs(10)).getCause());
        assertTrue(logs.list.isEmpty());
        assertNull(MDC.get("requestId"));
        assertNull(MDC.get("operationId"));
    }

    @Test
    void nonBlobListFailureIsRethrownUnchanged() throws Exception {
        RuntimeException failure = new IllegalStateException("transport failure");
        when(blobContainerClient.listBlobs()).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> sut.listJsonBlobs(10)));
        assertTrue(logs.list.isEmpty());
    }

    @Test
    void clientAcquisitionFailureKeepsOriginalException() throws Exception {
        BlobStorageException failure = storageFailure(500);
        assertEquals(500, failure.getStatusCode());
        when(blobContainerClient.getBlobClient("message.json")).thenThrow(failure);
        assertSame(failure, assertThrows(BlobStorageException.class,
                () -> sut.getStringJsonFromBlobStorage("message.json")));
        assertTrue(logs.list.isEmpty());
    }

    @Test
    void convertedDownloadFailureDoesNotDuplicateCallerDiagnosis() throws Exception {
        BlobStorageException failure = storageFailure(500);
        when(blobContainerClient.getBlobClient("message.json")).thenReturn(blobClient);
        doThrow(failure).when(blobClient).downloadStream(any(OutputStream.class));
        try (OperationLogScope ignored = OperationLogScope.open("download-request")) {
            DeadLetterAccessException converted = assertThrows(DeadLetterAccessException.class,
                    () -> sut.getStringJsonFromBlobStorage("message.json"));
            FailureDiagnostics.technicalFailure(new LogEventWriter(logger), converted);
        }
        assertFailure("getDeadLetter", 500);
    }

    @Test
    void nonBlobDownloadFailureIsRethrownUnchanged() throws Exception {
        RuntimeException failure = new IllegalStateException("transport failure");
        when(blobContainerClient.getBlobClient("message.json")).thenReturn(blobClient);
        doThrow(failure).when(blobClient).downloadStream(any(OutputStream.class));
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> sut.getStringJsonFromBlobStorage("message.json")));
        assertTrue(logs.list.isEmpty());
    }

    private void assertFailure(String operation, Integer status) throws Exception {
        assertEquals(1, logs.list.size());
        ILoggingEvent event = logs.list.get(0);
        assertEquals(Level.ERROR, event.getLevel());
        assertEquals("DEPENDENCY_CALL_FAILED", event.getMDCPropertyMap().get("event.code"));
        JsonNode details = new ObjectMapper().readTree(event.getMDCPropertyMap().get("ctx_details"));
        assertEquals("azure-blob-storage", details.path("dependency").asText());
        assertEquals(operation, details.path("dependencyOperation").asText());
        assertEquals("KO", details.path("dependencyOutcome").asText());
        assertTrue(details.path("durationMs").asLong() >= 0);
        if (status == null) {
            assertFalse(details.has("dependencyHttpCode"));
        } else {
            assertEquals(status.intValue(), details.path("dependencyHttpCode").asInt());
        }
        assertNull(event.getThrowableProxy());
        assertNull(event.getArgumentArray());
    }

    private BlobStorageException storageFailure(int status) {
        HttpResponse response = mock(HttpResponse.class);
        when(response.getStatusCode()).thenReturn(status);
        return new BlobStorageException("Blob call failed", response, null);
    }
}