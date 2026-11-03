package it.gov.pagopa.payments.backfill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.anyLong;

@ExtendWith(MockitoExtension.class)
class BlobBackfillCheckpointStoreTest {

  private static final String ORGANIZATION_CF = "80007580279";

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
  void shouldGenerateCheckpointBlobName() {
    String blobName =
        BlobBackfillCheckpointStore.checkpointBlobName(
            ORGANIZATION_CF);

    assertEquals(
        "backfill-checkpoints/80007580279.json",
        blobName);
  }

  @Test
  void shouldLoadCheckpoint() throws Exception {

    when(container.getBlobClient(
        "backfill-checkpoints/" + ORGANIZATION_CF + ".json"))
        .thenReturn(blobClient);

    BackfillCheckpoint expected =
        new BackfillCheckpoint(
            ORGANIZATION_CF,
            "token-page-2",
            100,
            90,
            5,
            2,
            1,
            2,
            OffsetDateTime.parse("2026-10-30T16:00:00Z"));

    byte[] json =
        objectMapper.writeValueAsBytes(expected);

    BinaryData binaryData =
        BinaryData.fromBytes(json);

    when(blobClient.downloadContent())
        .thenReturn(binaryData);

    Optional<BackfillCheckpoint> result =
        checkpointStore.load(ORGANIZATION_CF);

    assertTrue(result.isPresent());

    BackfillCheckpoint actual =
        result.get();

    assertEquals(
        ORGANIZATION_CF,
        actual.getOrganizationFiscalCode());

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
        OffsetDateTime.parse("2026-10-30T16:00:00Z"),
        actual.getUpdatedAt());
  }

  @Test
  void shouldReturnEmptyWhenCheckpointDoesNotExist() {
    when(container.getBlobClient(
        "backfill-checkpoints/" + ORGANIZATION_CF + ".json"))
        .thenReturn(blobClient);

    BlobStorageException exception =
        mock(BlobStorageException.class);

    when(exception.getStatusCode())
        .thenReturn(404);

    when(blobClient.downloadContent())
        .thenThrow(exception);

    Optional<BackfillCheckpoint> result =
        checkpointStore.load(ORGANIZATION_CF);

    assertTrue(result.isEmpty());
  }

  @Test
  void shouldSaveCheckpointWithOverwrite() {
    when(container.getBlobClient(
        "backfill-checkpoints/" + ORGANIZATION_CF + ".json"))
        .thenReturn(blobClient);

    BackfillCheckpoint checkpoint =
        new BackfillCheckpoint(
            ORGANIZATION_CF,
            "token-page-2",
            100,
            90,
            5,
            2,
            1,
            2,
            OffsetDateTime.parse("2026-10-30T16:00:00Z"));

    checkpointStore.save(checkpoint);

    verify(blobClient)
        .upload(
            any(InputStream.class),
            anyLong(),
            eq(true));
  }

  @Test
  void shouldDeleteCheckpoint() {
    when(container.getBlobClient(
        "backfill-checkpoints/" + ORGANIZATION_CF + ".json"))
        .thenReturn(blobClient);

    when(blobClient.deleteIfExists())
        .thenReturn(true);

    checkpointStore.delete(ORGANIZATION_CF);

    verify(blobClient)
        .deleteIfExists();
  }
}