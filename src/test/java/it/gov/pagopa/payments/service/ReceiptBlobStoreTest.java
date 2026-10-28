package it.gov.pagopa.payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobStorageException;
import it.gov.pagopa.payments.exception.ReceiptBlobNotFoundException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReceiptBlobStoreTest {

  private BlobContainerClient container;
  private BlobClient blobClient;
  private ReceiptBlobStore store;

  @BeforeEach
  void setUp() {
    container = mock(BlobContainerClient.class);
    blobClient = mock(BlobClient.class);
    when(container.getBlobClient("receipts/CF123/IUV456")).thenReturn(blobClient);
    store = new ReceiptBlobStore(container);
  }

  @Test
  void blobNameDependsOnlyOnCfAndIuv() {
    assertThat(ReceiptBlobStore.blobName("CF123", "IUV456")).isEqualTo("receipts/CF123/IUV456");
  }

  @Test
  void downloadReturnsBlobContent() {
    byte[] content = "<xml/>".getBytes(StandardCharsets.UTF_8);
    when(blobClient.downloadContent()).thenReturn(BinaryData.fromBytes(content));

    assertThat(store.download("CF123", "IUV456")).isEqualTo(content);
  }

  @Test
  void download404IsMappedToNotFoundException() {
    BlobStorageException ex = mock(BlobStorageException.class);
    when(ex.getStatusCode()).thenReturn(404);
    when(blobClient.downloadContent()).thenThrow(ex);

    assertThatThrownBy(() -> store.download("CF123", "IUV456"))
        .isInstanceOf(ReceiptBlobNotFoundException.class);
  }

  @Test
  void otherStorageErrorsAreRethrown() {
    BlobStorageException ex = mock(BlobStorageException.class);
    when(ex.getStatusCode()).thenReturn(500);
    when(blobClient.downloadContent()).thenThrow(ex);

    assertThatThrownBy(() -> store.download("CF123", "IUV456")).isSameAs(ex);
  }
}
