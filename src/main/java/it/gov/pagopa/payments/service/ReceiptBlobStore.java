package it.gov.pagopa.payments.service;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobStorageException;
import it.gov.pagopa.payments.endpoints.validation.exceptions.PartnerValidationException;
import it.gov.pagopa.payments.exception.ReceiptBlobNotFoundException;
import it.gov.pagopa.payments.model.PaaErrorEnum;
import java.io.ByteArrayInputStream;
import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class ReceiptBlobStore {

  private final BlobContainerClient container;

  public ReceiptBlobStore(@Qualifier("receiptsContainer") BlobContainerClient container) {
    this.container = container;
  }

  public static String blobName(String organizationCf, String iuv) {
    return "receipts/" + organizationCf + "/" + iuv;
  }

  public byte[] download(String organizationCf, String iuv) {
    BlobClient blob = container.getBlobClient(blobName(organizationCf, iuv));
    try {
      return blob.downloadContent().toBytes();
    } catch (BlobStorageException e) {
      if (e.getStatusCode() == 404) {
        throw new ReceiptBlobNotFoundException(organizationCf, iuv, e);
      }
      throw e;
    }
  }

  public void upload(
      String organizationFiscalCode,
      String iuv,
      byte[] content) {

    BlobClient blobClient =
        container.getBlobClient(
            blobName(organizationFiscalCode, iuv));

    try {
      blobClient.upload(
          new ByteArrayInputStream(content),
          content.length,
          false);

    } catch (BlobStorageException e) {
      if (!BlobErrorCode.BLOB_ALREADY_EXISTS.equals(e.getErrorCode())) {
        throw e;
      }

      byte[] existingContent =
          blobClient.downloadContent().toBytes();

      if (!Arrays.equals(existingContent, content)) {
        throw new PartnerValidationException(
            PaaErrorEnum.PAA_RECEIPT_DUPLICATA);
      }

      log.debug(
          "Receipt Blob already exists with identical content. "
          + "organizationFiscalCode={}, iuv={}",
          organizationFiscalCode,
          iuv);
    }
  }
}
