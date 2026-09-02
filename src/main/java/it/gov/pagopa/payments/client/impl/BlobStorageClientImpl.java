package it.gov.pagopa.payments.client.impl;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobStorageException;

import it.gov.pagopa.payments.client.BlobStorageClient;
import it.gov.pagopa.payments.exception.DeadLetterAccessException;
import it.gov.pagopa.payments.exception.DeadLetterNotFoundException;
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
	private static final int HTTP_NOT_FOUND = 404;

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
		try {
			return blobContainerClient.listBlobs().stream().map(BlobItem::getName)
					.filter(name -> name.endsWith(FILE_EXTENSION)).limit(maxMessages).toList();

		} catch (BlobStorageException e) {
			throw new DeadLetterAccessException("Unable to list dead-letter messages", e);
		}
	}

	@Override
	public String getStringJsonFromBlobStorage(String fileName) {
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		BlobClient blobClient = blobContainerClient.getBlobClient(fileName);

		try {
			blobClient.downloadStream(outputStream);
			return outputStream.toString(StandardCharsets.UTF_8);
		} catch (BlobStorageException e) {
			if (e.getStatusCode() == HTTP_NOT_FOUND) {
				throw new DeadLetterNotFoundException(fileName, e);
			}
			throw new DeadLetterAccessException("Unable to read dead-letter message: " + fileName, e);
		}
	}
}