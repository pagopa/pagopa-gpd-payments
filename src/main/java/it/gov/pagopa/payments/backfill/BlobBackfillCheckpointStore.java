package it.gov.pagopa.payments.backfill;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobStorageException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
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
  public Optional<BackfillCheckpoint> load(boolean dryRun) {

    BlobClient blobClient =
        container.getBlobClient(
            checkpointBlobName(dryRun));

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
          "Unable to deserialize backfill checkpoint. dryRun="
              + dryRun,
          e);
    }
  }

  @Override
  public void save(
      BackfillCheckpoint checkpoint,
      boolean dryRun) {

    BlobClient blobClient =
        container.getBlobClient(
            checkpointBlobName(dryRun));

    try {
      String json =
          objectMapper.writeValueAsString(checkpoint);

      byte[] content =
          json.getBytes(StandardCharsets.UTF_8);

      blobClient.upload(
          new ByteArrayInputStream(content),
          content.length,
          true);

    } catch (JsonProcessingException e) {
      throw new IllegalStateException(
          "Unable to serialize backfill checkpoint. dryRun="
              + dryRun,
          e);
    }
  }

  @Override
  public void delete(boolean dryRun) {

    BlobClient blobClient =
        container.getBlobClient(
            checkpointBlobName(dryRun));

    blobClient.deleteIfExists();
  }

  static String checkpointBlobName(boolean dryRun) {

    String mode =
        dryRun ? "dry-run/" : "migration/";

    return CHECKPOINT_PREFIX
        + mode
        + "checkpoint.json";
  }
}