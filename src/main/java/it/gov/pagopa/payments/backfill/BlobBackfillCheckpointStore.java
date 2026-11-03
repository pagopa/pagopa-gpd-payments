package it.gov.pagopa.payments.backfill;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobStorageException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class BlobBackfillCheckpointStore
    implements BackfillCheckpointStore {

  private static final String CHECKPOINT_PREFIX =
      "backfill-checkpoints/";

  private final BlobContainerClient container;
  private final ObjectMapper objectMapper;

  public BlobBackfillCheckpointStore(
      @Qualifier("receiptsContainer")
      BlobContainerClient container,
      ObjectMapper objectMapper) {

    this.container = container;
    this.objectMapper = objectMapper;
  }

  @Override
  public Optional<BackfillCheckpoint> load(
      String organizationFiscalCode) {

    BlobClient blobClient =
        container.getBlobClient(
            checkpointBlobName(organizationFiscalCode));

    try {
      byte[] content =
          blobClient.downloadContent().toBytes();

      BackfillCheckpoint checkpoint =
          objectMapper.readValue(
              content,
              BackfillCheckpoint.class);

      return Optional.of(checkpoint);

    } catch (BlobStorageException e) {

      if (e.getStatusCode() == 404) {
        return Optional.empty();
      }

      throw e;

    } catch (Exception e) {
      throw new IllegalStateException(
          "Unable to deserialize backfill checkpoint for organizationFiscalCode="
              + organizationFiscalCode,
          e);
    }
  }

  @Override
  public void save(
      BackfillCheckpoint checkpoint) {

    BlobClient blobClient =
        container.getBlobClient(
            checkpointBlobName(
                checkpoint.getOrganizationFiscalCode()));

    try {
      String json =
          objectMapper.writeValueAsString(checkpoint);

      byte[] content =
          json.getBytes(StandardCharsets.UTF_8);

      blobClient.upload(
          new java.io.ByteArrayInputStream(content),
          content.length,
          true);

    } catch (JsonProcessingException e) {
      throw new IllegalStateException(
          "Unable to serialize backfill checkpoint for organizationFiscalCode="
              + checkpoint.getOrganizationFiscalCode(),
          e);
    }
  }

  @Override
  public void delete(
      String organizationFiscalCode) {

    BlobClient blobClient =
        container.getBlobClient(
            checkpointBlobName(organizationFiscalCode));

    blobClient.deleteIfExists();
  }

  static String checkpointBlobName(
      String organizationFiscalCode) {

    return CHECKPOINT_PREFIX
        + organizationFiscalCode
        + ".json";
  }
}