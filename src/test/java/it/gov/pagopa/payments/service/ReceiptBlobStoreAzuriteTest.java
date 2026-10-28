package it.gov.pagopa.payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import it.gov.pagopa.payments.exception.ReceiptBlobNotFoundException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class ReceiptBlobStoreAzuriteTest {

  @Container
  static GenericContainer<?> azurite =
      new GenericContainer<>(
          DockerImageName.parse("mcr.microsoft.com/azure-storage/azurite:latest"))
          .withExposedPorts(10000)
          .withCommand("azurite-blob", "--blobHost", "0.0.0.0", "--skipApiVersionCheck");

  static BlobContainerClient container;
  static ReceiptBlobStore store;

  @BeforeAll
  static void init() {
    String connectionString =
        String.format(
            "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
                + "AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;"
                + "BlobEndpoint=http://%s:%s/devstoreaccount1;",
            azurite.getHost(), azurite.getMappedPort(10000));

    BlobServiceClient service =
        new BlobServiceClientBuilder().connectionString(connectionString).buildClient();
    container = service.createBlobContainerIfNotExists("receipts");
    store = new ReceiptBlobStore(container);
  }

  @Test
  void downloadExistingBlob() {
    byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
    container
        .getBlobClient(ReceiptBlobStore.blobName("CF1", "IUV1"))
        .upload(BinaryData.fromBytes(content), true);

    assertThat(store.download("CF1", "IUV1")).isEqualTo(content);
  }

  @Test
  void downloadMissingBlobThrowsNotFound() {
    assertThatThrownBy(() -> store.download("CF1", "MISSING"))
        .isInstanceOf(ReceiptBlobNotFoundException.class);
  }
}
