package it.gov.pagopa.payments.config;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ReceiptBlobConfig {

  @Bean("receiptsContainer")
  public BlobContainerClient receiptsContainer(
      @Value("${receipts.blob.connect-str}") String connectionStr,
      @Value("${receipts.blob.container}") String containerName) {
    BlobServiceClient service =
        new BlobServiceClientBuilder().connectionString(connectionStr).buildClient();
    return service.getBlobContainerClient(containerName);
  }
}
