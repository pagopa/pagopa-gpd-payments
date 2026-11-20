package it.gov.pagopa.backfill.service;
import com.azure.storage.blob.BlobContainerClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.gov.pagopa.backfill.model.BackfillMetricsReport;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class BlobBackfillMetricsStore {

  private static final String PREFIX = "backfill-metrics/";

  private final BlobContainerClient containerClient;
  private final ObjectMapper objectMapper;

  public BlobBackfillMetricsStore(
      BlobContainerClient receiptsContainer,
      ObjectMapper objectMapper) {
    this.containerClient = receiptsContainer;
    this.objectMapper = objectMapper;
  }

  public void save(
      String executionId,
      boolean dryRun,
      BackfillMetricsReport report) {

    try {
      String mode = dryRun ? "dry-run/" : "migration/";

      String blobName =
          PREFIX + mode + executionId + ".json";

      byte[] json = objectMapper
          .writerWithDefaultPrettyPrinter()
          .writeValueAsBytes(report);

      containerClient
          .getBlobClient(blobName)
          .upload(
              new java.io.ByteArrayInputStream(json),
              json.length,
              true);

    } catch (Exception e) {
      throw new IllegalStateException(
          "Failed to save backfill metrics", e);
    }
  }
}
