package it.gov.pagopa.payments.backfill;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BackfillAnomalyStoreTest {

  private BlobContainerClient container;
  private BlobClient blobClient;
  private BackfillAnomalyStore anomalyStore;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  void setUp() {
    container = mock(BlobContainerClient.class);
    blobClient = mock(BlobClient.class);

    when(container.getBlobClient(anyString()))
        .thenReturn(blobClient);

    anomalyStore = new BackfillAnomalyStore(
        container,
        objectMapper);
  }

  @Test
  void shouldSaveInconsistentReceipt() throws Exception {
    String cf = "80007580279";
    String iuv = "123456789";

    anomalyStore.saveInconsistent(cf, iuv, false);

    verify(container).getBlobClient(
        "backfill-anomalies/migration/inconsistent/"
            + cf + "/" + iuv + ".json");

    ArgumentCaptor<InputStream> streamCaptor =
        ArgumentCaptor.forClass(InputStream.class);

    verify(blobClient).upload(
        streamCaptor.capture(),
        anyLong(),
        eq(true));

    String json = new String(
        streamCaptor.getValue().readAllBytes(),
        StandardCharsets.UTF_8);

    JsonNode node = objectMapper.readTree(json);

    assertEquals(cf, node.get("organizationFiscalCode").asText());
    assertEquals(iuv, node.get("iuv").asText());
    assertEquals("INCONSISTENT", node.get("status").asText());
    assertEquals(
        "Missing document and contentHash",
        node.get("reason").asText());

    assertTrue(node.hasNonNull("detectedAt"));
  }

  @Test
  void shouldSaveDryRunAnomalyInSeparatePath() {
    anomalyStore.saveInconsistent(
        "80007580279",
        "123456789",
        true);

    verify(container).getBlobClient(
        "backfill-anomalies/dry-run/inconsistent/"
            + "80007580279/123456789.json");
  }

  @Test
  void shouldUseSameBlobOnRetry() {
    String cf = "80007580279";
    String iuv = "123456789";

    anomalyStore.saveInconsistent(cf, iuv, false);
    anomalyStore.saveInconsistent(cf, iuv, false);

    verify(container, times(2)).getBlobClient(
        "backfill-anomalies/migration/inconsistent/"
            + cf + "/" + iuv + ".json");

    verify(blobClient, times(2)).upload(
        any(InputStream.class),
        anyLong(),
        eq(true));
  }

  @Test
  void shouldThrowWhenBlobUploadFails() {
    doThrow(new RuntimeException("Azure Blob unavailable"))
        .when(blobClient)
        .upload(
            any(InputStream.class),
            anyLong(),
            eq(true));

    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class,
            () -> anomalyStore.saveInconsistent(
                "80007580279",
                "123456789",
                false));

    assertTrue(exception.getMessage().contains(
        "Unable to persist inconsistent receipt anomaly"));
  }
}