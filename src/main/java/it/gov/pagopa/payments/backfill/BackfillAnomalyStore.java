package it.gov.pagopa.payments.backfill;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class BackfillAnomalyStore {

  private final BlobContainerClient container;
  private final ObjectMapper objectMapper;

  public BackfillAnomalyStore(
      @Qualifier("receiptsContainer") BlobContainerClient container,
      ObjectMapper objectMapper) {

    this.container = container;
    this.objectMapper = objectMapper;
  }

  public void saveInconsistent(
      String organizationFiscalCode,
      String iuv,
      boolean dryRun) {

    String blobName =
        "backfill-anomalies/"
            + (dryRun ? "dry-run/" : "migration/")
            + "inconsistent/"
            + organizationFiscalCode
            + "/"
            + iuv
            + ".json";

    try {
      Map<String, Object> anomaly = Map.of(
          "organizationFiscalCode", organizationFiscalCode,
          "iuv", iuv,
          "reason", "Missing document and contentHash",
          "status", "INCONSISTENT",
          "detectedAt", OffsetDateTime.now().toString());

      byte[] content = objectMapper
          .writeValueAsString(anomaly)
          .getBytes(StandardCharsets.UTF_8);

      BlobClient blob = container.getBlobClient(blobName);

      blob.upload(
          new ByteArrayInputStream(content),
          content.length,
          true);

    } catch (Exception e) {
      throw new IllegalStateException(
          "Unable to persist inconsistent receipt anomaly. "
              + "organizationFiscalCode=" + organizationFiscalCode
              + ", iuv=" + iuv,
          e);
    }
  }
}