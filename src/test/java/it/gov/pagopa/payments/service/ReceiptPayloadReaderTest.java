package it.gov.pagopa.payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.storage.blob.models.BlobStorageException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.gov.pagopa.payments.exception.AppError;
import it.gov.pagopa.payments.exception.AppException;
import it.gov.pagopa.payments.exception.ReceiptBlobNotFoundException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReceiptPayloadReaderTest {

  private static final String CF = "CF123";
  private static final String IUV = "IUV456";
  private static final String CONTENT = "<xml>receipt</xml>";

  private ReceiptBlobStore blobStore;
  private ReceiptPayloadReader reader;
  private SimpleMeterRegistry meterRegistry;

  @BeforeEach
  void setUp() {
    blobStore = mock(ReceiptBlobStore.class);
    meterRegistry = new SimpleMeterRegistry();
    reader = new ReceiptPayloadReader(blobStore, meterRegistry);
  }

  @Test
  void readDocumentFromBlob() {
    when(blobStore.download(CF, IUV))
        .thenReturn(CONTENT.getBytes(StandardCharsets.UTF_8));

    assertThat(reader.readDocument(CF, IUV))
        .isEqualTo(CONTENT);

    verify(blobStore).download(CF, IUV);
  }

  @Test
  void readDocumentShouldIncrementBlobMetric() {
    when(blobStore.download(CF, IUV))
        .thenReturn(CONTENT.getBytes(StandardCharsets.UTF_8));

    reader.readDocument(CF, IUV);

    assertThat(
        meterRegistry.counter("receipt.read", "source", "blob").count())
        .isEqualTo(1.0);
  }

  @Test
  void missingBlobThrowsReceiptNotFound() {
    when(blobStore.download(CF, IUV))
        .thenThrow(
            new ReceiptBlobNotFoundException(
                CF, IUV, new RuntimeException()));

    assertThatThrownBy(() -> reader.readDocument(CF, IUV))
        .isInstanceOf(AppException.class)
        .hasMessageContaining(
            new AppException(AppError.RECEIPT_NOT_FOUND, CF, IUV)
                .getMessage());
  }

  @Test
  void blobStorageErrorThrowsDbError() {
    BlobStorageException exception =
        new BlobStorageException("Storage error", null, null);

    when(blobStore.download(CF, IUV))
        .thenThrow(exception);

    assertThatThrownBy(() -> reader.readDocument(CF, IUV))
        .isInstanceOf(AppException.class)
        .hasMessageContaining(
            new AppException(AppError.DB_ERROR).getMessage());
  }

  @Test
  void failedReadShouldNotIncrementBlobMetric() {
    when(blobStore.download(CF, IUV))
        .thenThrow(
            new ReceiptBlobNotFoundException(
                CF, IUV, new RuntimeException()));

    assertThatThrownBy(() -> reader.readDocument(CF, IUV))
        .isInstanceOf(AppException.class);

    assertThat(
        meterRegistry.counter("receipt.read", "source", "blob").count())
        .isZero();
  }
}