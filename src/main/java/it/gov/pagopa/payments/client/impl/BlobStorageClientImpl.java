package it.gov.pagopa.payments.client.impl;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import it.gov.pagopa.payments.client.BlobStorageClient;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BlobStorageClientImpl implements BlobStorageClient {

    private static final String FILE_EXTENSION = ".json";

    private final BlobContainerClient blobContainerClient;

    @Override
    public void saveStringJsonToBlobStorage(String json, String fileName) {
        byte[] content = json.getBytes(StandardCharsets.UTF_8);

        try (InputStream inputStream = new ByteArrayInputStream(content)) {
            BlobClient blobClient =
                    blobContainerClient.getBlobClient(fileName + FILE_EXTENSION);

            blobClient.upload(inputStream, content.length);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Unable to persist dead-letter message", e);
        }
    }
}