package it.gov.pagopa.payments.client.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.InputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;

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
}