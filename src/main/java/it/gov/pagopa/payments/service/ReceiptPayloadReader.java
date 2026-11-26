package it.gov.pagopa.payments.service;

import com.azure.storage.blob.models.BlobStorageException;
import io.micrometer.core.instrument.MeterRegistry;
import it.gov.pagopa.payments.exception.AppError;
import it.gov.pagopa.payments.exception.AppException;
import it.gov.pagopa.payments.exception.ReceiptBlobNotFoundException;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class ReceiptPayloadReader {

  private final ReceiptBlobStore blobStore;
  private final MeterRegistry metrics;

  public ReceiptPayloadReader(
      ReceiptBlobStore blobStore,
      MeterRegistry metrics) {
    this.blobStore = blobStore;
    this.metrics = metrics;
  }

  public String readDocument(String cf, String iuv) {
    try {
      byte[] data = blobStore.download(cf, iuv);

      metrics.counter("receipt.read", "source", "blob").increment();

      return new String(data, StandardCharsets.UTF_8);

    } catch (ReceiptBlobNotFoundException e) {
      throw new AppException(AppError.RECEIPT_NOT_FOUND, cf, iuv);

    } catch (BlobStorageException e) {
      log.error("Error reading receipt from Blob Storage", e);
      throw new AppException(AppError.DB_ERROR);
    }
  }
}
