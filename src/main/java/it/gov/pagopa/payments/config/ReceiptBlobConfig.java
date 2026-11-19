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
    System.out.println("Lunghezza: " + connectionStr.length());
    System.out.println("Inizia correttamente: " +
        connectionStr.startsWith("DefaultEndpointsProtocol=https;"));
    System.out.println("AccountName presente: " +
        connectionStr.contains("AccountName="));
    System.out.println("AccountKey presente: " +
        connectionStr.contains("AccountKey="));
    System.out.println("EndpointSuffix presente: " +
        connectionStr.contains("EndpointSuffix="));

    BlobServiceClient service =
        new BlobServiceClientBuilder().connectionString(connectionStr).buildClient();
    return service.getBlobContainerClient(containerName);
  }
}
