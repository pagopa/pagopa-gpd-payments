package it.gov.pagopa.payments.client.impl;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobItem;
import it.gov.pagopa.payments.client.BlobStorageClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BlobStorageClientImpl implements BlobStorageClient {

	private static final String FILE_EXTENSION = ".json";

	private final BlobContainerClient blobContainerClient;

	@Override
	public void saveStringJsonToBlobStorage(String json, String fileName) {
		byte[] content = json.getBytes(StandardCharsets.UTF_8);

		try (InputStream inputStream = new ByteArrayInputStream(content)) {
			BlobClient blobClient = blobContainerClient.getBlobClient(fileName + FILE_EXTENSION);
			blobClient.upload(inputStream, content.length);
		} catch (IOException e) {
			throw new IllegalStateException("Unable to persist dead-letter message", e);
		}
	}

	@Override
	public List<String> listJsonBlobs(int maxMessages) {
		return blobContainerClient.listBlobs().stream().map(BlobItem::getName)
				.filter(name -> name.endsWith(FILE_EXTENSION)).limit(maxMessages).toList();
	}

	@Override
	public String getStringJsonFromBlobStorage(String fileName) {
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		BlobClient blobClient = blobContainerClient.getBlobClient(fileName);
		blobClient.downloadStream(outputStream);

		return outputStream.toString(StandardCharsets.UTF_8);
	}
}