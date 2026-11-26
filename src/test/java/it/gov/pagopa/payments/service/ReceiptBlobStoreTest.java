package it.gov.pagopa.payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobStorageException;
import it.gov.pagopa.payments.endpoints.validation.exceptions.PartnerValidationException;
import it.gov.pagopa.payments.exception.ReceiptBlobNotFoundException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

import com.azure.storage.blob.models.BlobErrorCode;
import java.io.InputStream;

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

  @Test
  void uploadSuccessfully() {
    byte[] content = "<xml/>".getBytes(StandardCharsets.UTF_8);

    store.upload("CF123", "IUV456", content);

    verify(container).getBlobClient("receipts/CF123/IUV456");

    verify(blobClient)
        .upload(any(InputStream.class), eq((long) content.length), eq(false));

    verify(blobClient, never()).downloadContent();
  }

  @Test
  void uploadExistingBlobWithSameContent() {
    byte[] content = "<xml/>".getBytes(StandardCharsets.UTF_8);

    BlobStorageException ex = mock(BlobStorageException.class);

    when(ex.getErrorCode())
        .thenReturn(BlobErrorCode.BLOB_ALREADY_EXISTS);

    doThrow(ex)
        .when(blobClient)
        .upload(any(InputStream.class), eq((long) content.length), eq(false));

    when(blobClient.downloadContent())
        .thenReturn(BinaryData.fromBytes(content));

    store.upload("CF123", "IUV456", content);

    verify(blobClient)
        .upload(any(InputStream.class), eq((long) content.length), eq(false));

    verify(blobClient).downloadContent();
  }

  @Test
  void uploadExistingBlobWithDifferentContent() {
    byte[] content = "<xml/>".getBytes(StandardCharsets.UTF_8);
    byte[] existingContent = "<different/>".getBytes(StandardCharsets.UTF_8);

    BlobStorageException ex = mock(BlobStorageException.class);

    when(ex.getErrorCode())
        .thenReturn(BlobErrorCode.BLOB_ALREADY_EXISTS);

    doThrow(ex)
        .when(blobClient)
        .upload(any(InputStream.class), eq((long) content.length), eq(false));

    when(blobClient.downloadContent())
        .thenReturn(BinaryData.fromBytes(existingContent));

    assertThatThrownBy(() -> store.upload("CF123", "IUV456", content))
        .isInstanceOf(PartnerValidationException.class);

    verify(blobClient).downloadContent();
  }
  @Test
  void uploadOtherStorageErrorsAreRethrown() {
    byte[] content = "<xml/>".getBytes(StandardCharsets.UTF_8);

    BlobStorageException ex = mock(BlobStorageException.class);

    when(ex.getErrorCode())
        .thenReturn(BlobErrorCode.INTERNAL_ERROR);

    doThrow(ex)
        .when(blobClient)
        .upload(any(InputStream.class), eq((long) content.length), eq(false));

    assertThatThrownBy(() -> store.upload("CF123", "IUV456", content))
        .isSameAs(ex);

    verify(blobClient, never()).downloadContent();
  }

}
