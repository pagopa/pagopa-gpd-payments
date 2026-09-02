package it.gov.pagopa.payments.client.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.azure.core.http.rest.PagedIterable;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobStorageException;

import it.gov.pagopa.payments.exception.DeadLetterAccessException;
import it.gov.pagopa.payments.exception.DeadLetterNotFoundException;

@ExtendWith(MockitoExtension.class)
class BlobStorageClientImplTest {

    @Mock
    private BlobContainerClient blobContainerClient;

    @Mock
    private BlobClient blobClient;

    @InjectMocks
    private BlobStorageClientImpl sut;

    @Test
    void saveStringJsonToBlobStorage_shouldUploadJsonFile() {

        when(blobContainerClient.getBlobClient("path/message.json"))
                .thenReturn(blobClient);

        sut.saveStringJsonToBlobStorage(
                "{\"messageId\":\"123\"}",
                "path/message");

        verify(blobContainerClient)
                .getBlobClient("path/message.json");

        verify(blobClient)
                .upload(any(InputStream.class), anyLong());
    }
    
    @Test
    void listJsonBlobsShouldReturnAtMostConfiguredNumberOfJsonFiles() {

        @SuppressWarnings("unchecked")
        PagedIterable<BlobItem> pagedIterable =
                mock(PagedIterable.class);

        BlobItem firstBlob =
                new BlobItem()
                        .setName(
                                "2026/08/31/10/message-1/"
                                        + "MAX_RETRY_ATTEMPTS_REACHED_1000.json");

        BlobItem secondBlob =
                new BlobItem()
                        .setName(
                                "2026/08/31/11/message-2/"
                                        + "MAX_RETRY_ATTEMPTS_REACHED_2000.json");

        BlobItem nonJsonBlob =
                new BlobItem()
                        .setName("ignored.txt");

        when(blobContainerClient.listBlobs())
                .thenReturn(pagedIterable);

        when(pagedIterable.stream())
                .thenReturn(
                        Stream.of(
                                nonJsonBlob,
                                firstBlob,
                                secondBlob));

        List<String> result =
                sut.listJsonBlobs(1);

        assertEquals(
                List.of(
                        "2026/08/31/10/message-1/"
                                + "MAX_RETRY_ATTEMPTS_REACHED_1000.json"),
                result);

        verify(blobContainerClient)
                .listBlobs();
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

        BlobStorageException storageException =
                mock(BlobStorageException.class);

        when(blobContainerClient.getBlobClient(fileName))
                .thenReturn(blobClient);

        when(storageException.getStatusCode())
                .thenReturn(404);

        doThrow(storageException)
                .when(blobClient)
                .downloadStream(any(OutputStream.class));

        assertThrows(
                DeadLetterNotFoundException.class,
                () -> sut.getStringJsonFromBlobStorage(fileName));
    }
    
    @Test
    void getStringJsonFromBlobStorageShouldThrowAccessExceptionOnStorageFailure() {

        String fileName =
                "2026/08/31/10/message-1/"
                        + "MAX_RETRY_ATTEMPTS_REACHED_1000.json";

        BlobStorageException storageException =
                mock(BlobStorageException.class);

        when(blobContainerClient.getBlobClient(fileName))
                .thenReturn(blobClient);

        when(storageException.getStatusCode())
                .thenReturn(500);

        doThrow(storageException)
                .when(blobClient)
                .downloadStream(any(OutputStream.class));

        assertThrows(
                DeadLetterAccessException.class,
                () -> sut.getStringJsonFromBlobStorage(fileName));
    }
}