package it.gov.pagopa.payments.backfill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobStorageException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlobBackfillCheckpointStoreTest {

  @Mock
  private BlobContainerClient container;

  @Mock
  private BlobClient blobClient;

  private ObjectMapper objectMapper;

  private BlobBackfillCheckpointStore checkpointStore;

  @BeforeEach
  void setUp() {

    objectMapper =
        new ObjectMapper()
            .registerModule(new JavaTimeModule());

    checkpointStore =
        new BlobBackfillCheckpointStore(
            container,
            objectMapper);
  }

  @Test
  void shouldGenerateDryRunCheckpointBlobName() {

    String blobName =
        BlobBackfillCheckpointStore.checkpointBlobName(true);

    assertEquals(
        "backfill-checkpoints/dry-run/checkpoint.json",
        blobName);
  }

  @Test
  void shouldGenerateMigrationCheckpointBlobName() {

    String blobName =
        BlobBackfillCheckpointStore.checkpointBlobName(false);

    assertEquals(
        "backfill-checkpoints/migration/checkpoint.json",
        blobName);
  }

  @Test
  void shouldLoadDryRunCheckpoint() throws Exception {

    when(container.getBlobClient(
        "backfill-checkpoints/dry-run/checkpoint.json"))
        .thenReturn(blobClient);

    BackfillCheckpoint expected =
        createCheckpoint();

    byte[] json =
        objectMapper.writeValueAsBytes(expected);

    when(blobClient.downloadContent())
        .thenReturn(BinaryData.fromBytes(json));

    Optional<BackfillCheckpoint> result =
        checkpointStore.load(true);

    assertTrue(result.isPresent());

    BackfillCheckpoint actual =
        result.get();

    assertEquals(
        "token-page-2",
        actual.getContinuationToken());

    assertEquals(100, actual.getScanned());
    assertEquals(90, actual.getMigrated());
    assertEquals(5, actual.getAlreadyMigrated());
    assertEquals(2, actual.getInconsistent());
    assertEquals(1, actual.getHashMismatch());
    assertEquals(2, actual.getFailed());

    assertEquals(
        BackfillStatus.IN_PROGRESS,
        actual.getStatus());

    assertEquals(
        OffsetDateTime.parse("2026-10-30T16:00:00Z"),
        actual.getUpdatedAt());
  }

  @Test
  void shouldLoadMigrationCheckpoint() throws Exception {

    when(container.getBlobClient(
        "backfill-checkpoints/migration/checkpoint.json"))
        .thenReturn(blobClient);

    BackfillCheckpoint expected =
        createCheckpoint();

    byte[] json =
        objectMapper.writeValueAsBytes(expected);

    when(blobClient.downloadContent())
        .thenReturn(BinaryData.fromBytes(json));

    Optional<BackfillCheckpoint> result =
        checkpointStore.load(false);

    assertTrue(result.isPresent());

    assertEquals(
        "token-page-2",
        result.get().getContinuationToken());

    assertEquals(
        BackfillStatus.IN_PROGRESS,
        result.get().getStatus());
  }

  @Test
  void shouldReturnEmptyWhenDryRunCheckpointDoesNotExist() {

    when(container.getBlobClient(
        "backfill-checkpoints/dry-run/checkpoint.json"))
        .thenReturn(blobClient);

    BlobStorageException exception =
        mock(BlobStorageException.class);

    when(exception.getStatusCode())
        .thenReturn(404);

    when(blobClient.downloadContent())
        .thenThrow(exception);

    Optional<BackfillCheckpoint> result =
        checkpointStore.load(true);

    assertTrue(result.isEmpty());
  }

  @Test
  void shouldSaveDryRunCheckpointWithOverwrite() {

    when(container.getBlobClient(
        "backfill-checkpoints/dry-run/checkpoint.json"))
        .thenReturn(blobClient);

    BackfillCheckpoint checkpoint =
        createCheckpoint();

    checkpointStore.save(
        checkpoint,
        true);

    verify(blobClient)
        .upload(
            any(InputStream.class),
            anyLong(),
            eq(true));
  }

  @Test
  void shouldSaveMigrationCheckpointWithOverwrite() {

    when(container.getBlobClient(
        "backfill-checkpoints/migration/checkpoint.json"))
        .thenReturn(blobClient);

    BackfillCheckpoint checkpoint =
        createCheckpoint();

    checkpointStore.save(
        checkpoint,
        false);

    verify(blobClient)
        .upload(
            any(InputStream.class),
            anyLong(),
            eq(true));
  }

  @Test
  void shouldDeleteDryRunCheckpoint() {

    when(container.getBlobClient(
        "backfill-checkpoints/dry-run/checkpoint.json"))
        .thenReturn(blobClient);

    when(blobClient.deleteIfExists())
        .thenReturn(true);

    checkpointStore.delete(true);

    verify(blobClient)
        .deleteIfExists();
  }

  @Test
  void shouldDeleteMigrationCheckpoint() {

    when(container.getBlobClient(
        "backfill-checkpoints/migration/checkpoint.json"))
        .thenReturn(blobClient);

    when(blobClient.deleteIfExists())
        .thenReturn(true);

    checkpointStore.delete(false);

    verify(blobClient)
        .deleteIfExists();
  }

  private BackfillCheckpoint createCheckpoint() {

    return new BackfillCheckpoint(
        "token-page-2",
        100,
        90,
        5,
        2,
        1,
        2,
        BackfillStatus.IN_PROGRESS,
        OffsetDateTime.parse("2026-10-30T16:00:00Z"));
  }
}