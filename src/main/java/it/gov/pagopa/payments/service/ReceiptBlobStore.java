package it.gov.pagopa.payments.service;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobStorageException;
import it.gov.pagopa.payments.exception.ReceiptBlobNotFoundException;
import java.io.ByteArrayInputStream;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
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

    blobClient.upload(
        new ByteArrayInputStream(content),
        content.length);
  }
}
