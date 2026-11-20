package it.gov.pagopa.backfill.service;

import com.azure.core.http.rest.PagedIterable;
import com.azure.core.http.rest.PagedResponse;
import com.azure.data.tables.TableClient;
import com.azure.data.tables.models.ListEntitiesOptions;
import com.azure.data.tables.models.TableEntity;
import com.azure.data.tables.models.TableEntityUpdateMode;
import com.azure.data.tables.models.TableServiceException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.gov.pagopa.backfill.model.BackfillCheckpoint;
import it.gov.pagopa.backfill.model.BackfillSizeStats;
import it.gov.pagopa.backfill.model.BackfillStatus;
import it.gov.pagopa.backfill.model.MigrationResult;
import it.gov.pagopa.backfill.model.MigrationSummary;
import it.gov.pagopa.payments.service.ReceiptBlobStore;
import it.gov.pagopa.payments.service.ReceiptPayloadReader;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ReceiptBackfillService {

  private static final String DOCUMENT_PROPERTY = "document";
  private static final String CONTENT_HASH_PROPERTY = "contentHash";

  private final TableClient tableClient;
  private final ReceiptBlobStore receiptBlobStore;
  private final BackfillCheckpointStore checkpointStore;
  private final BackfillAnomalyStore anomalyStore;

  private final ObjectMapper objectMapper =
      new ObjectMapper().findAndRegisterModules();

  @Value("${receipts.backfill.test.stop-after-pages:0}")
  private int stopAfterPages;

  @Value("${receipts.backfill.test.stop-after-entities:0}")
  private int stopAfterEntities;

  public ReceiptBackfillService(
      TableClient tableClient,
      ReceiptBlobStore receiptBlobStore,
      BackfillCheckpointStore checkpointStore,
      BackfillAnomalyStore anomalyStore) {

    this.tableClient = tableClient;
    this.receiptBlobStore = receiptBlobStore;
    this.checkpointStore = checkpointStore;
    this.anomalyStore = anomalyStore;
  }

  public MigrationResult migrateOne(TableEntity entity) {

    String organizationFiscalCode = entity.getPartitionKey();
    String iuv = entity.getRowKey();

    Object documentProperty =
        entity.getProperty(DOCUMENT_PROPERTY);

    Object contentHashProperty =
        entity.getProperty(CONTENT_HASH_PROPERTY);

    if (documentProperty == null && contentHashProperty != null) {
      log.debug(
          "Receipt already migrated. organizationFiscalCode={}, iuv={}",
          organizationFiscalCode,
          iuv);

      return MigrationResult.ALREADY_MIGRATED;
    }

    if (documentProperty == null) {
      log.error(
          "Inconsistent receipt. Missing document and contentHash. "
              + "organizationFiscalCode={}, iuv={}",
          organizationFiscalCode,
          iuv);

      return MigrationResult.INCONSISTENT;
    }

    byte[] document =
        documentProperty
            .toString()
            .getBytes(StandardCharsets.UTF_8);

    String expectedHash =
        ReceiptPayloadReader.sha256Hex(document);

    try {

      receiptBlobStore.upload(
          organizationFiscalCode,
          iuv,
          document);

      byte[] storedDocument =
          receiptBlobStore.download(
              organizationFiscalCode,
              iuv);

      String actualHash =
          ReceiptPayloadReader.sha256Hex(storedDocument);

      if (!expectedHash.equals(actualHash)) {
        log.error(
            "Receipt Blob hash mismatch. organizationFiscalCode={}, iuv={}",
            organizationFiscalCode,
            iuv);

        return MigrationResult.HASH_MISMATCH;
      }

      entity.getProperties().remove(DOCUMENT_PROPERTY);

      entity.addProperty(
          CONTENT_HASH_PROPERTY,
          expectedHash);

      tableClient.updateEntityWithResponse(
          entity,
          TableEntityUpdateMode.REPLACE,
          true,
          null,
          null);

      log.info(
          "Receipt migrated successfully. organizationFiscalCode={}, iuv={}",
          organizationFiscalCode,
          iuv);

      return MigrationResult.MIGRATED;

    } catch (TableServiceException e) {

      if (e.getResponse() != null
          && e.getResponse().getStatusCode() == 412) {

        log.warn(
            "Receipt changed during migration, will be retried. "
                + "organizationFiscalCode={}, iuv={}",
            organizationFiscalCode,
            iuv);

        return MigrationResult.RETRYABLE_ERROR;
      }

      log.error(
          "Table error migrating receipt. organizationFiscalCode={}, iuv={}",
          organizationFiscalCode,
          iuv,
          e);

      return MigrationResult.RETRYABLE_ERROR;

    } catch (Exception e) {

      log.error(
          "Error migrating receipt. organizationFiscalCode={}, iuv={}",
          organizationFiscalCode,
          iuv,
          e);

      return MigrationResult.RETRYABLE_ERROR;
    }
  }

  public MigrationSummary migrateAll(boolean dryRun) {
    log.info(
        "Starting receipt backfill. dryRun={}",
        dryRun);

    Optional<BackfillCheckpoint> existingCheckpoint =
        checkpointStore.load(dryRun);

    if (existingCheckpoint.isPresent()
        && existingCheckpoint.get().getStatus()
           == BackfillStatus.COMPLETED) {

      log.info(
          "Receipt backfill already completed. dryRun={}",
          dryRun);

      return new MigrationSummary(
          existingCheckpoint.get());
    }

    MigrationSummary summary =
        existingCheckpoint
            .map(MigrationSummary::new)
            .orElseGet(MigrationSummary::new);

    String continuationToken =
        existingCheckpoint
            .map(BackfillCheckpoint::getContinuationToken)
            .orElse(null);

    if (continuationToken != null) {
      log.info(
          "Resuming receipt backfill from checkpoint. "
              + "continuationToken={}, summary={}",
          continuationToken,
          summary);
    }

    PagedIterable<TableEntity> entities =
        tableClient.listEntities(
            new ListEntitiesOptions(),
            null,
            null);

    Iterable<PagedResponse<TableEntity>> pages =
        continuationToken == null
            ? entities.iterableByPage()
            : entities.iterableByPage(continuationToken);

    Map<String, BackfillSizeStats> statsByCf = new LinkedHashMap<>();

    int processedPages = 0;

    for (PagedResponse<TableEntity> page : pages) {

      MigrationSummary pageSummary = processPage(page, dryRun, statsByCf);

      summary.add(pageSummary);

      String nextContinuationToken =
          page.getContinuationToken();

      BackfillStatus status =
          nextContinuationToken == null
              ? BackfillStatus.COMPLETED
              : BackfillStatus.IN_PROGRESS;

      BackfillCheckpoint checkpoint =
          new BackfillCheckpoint(
              nextContinuationToken,
              summary.getScanned(),
              summary.getMigrated(),
              summary.getAlreadyMigrated(),
              summary.getInconsistent(),
              summary.getHashMismatch(),
              summary.getFailed(),
              status,
              OffsetDateTime.now());

      checkpointStore.save(
          checkpoint,
          dryRun);

      log.info(
          "Receipt backfill page completed. "
              + "continuationToken={}, status={}, summary={}",
          nextContinuationToken,
          status,
          summary);

      processedPages++;

      if (dryRun
          && stopAfterPages > 0
          && processedPages >= stopAfterPages
          && status == BackfillStatus.IN_PROGRESS) {

        log.warn(
            "[DRY-RUN TEST] Simulated interruption after {} pages. "
            + "Checkpoint saved. continuationToken={}, summary={}",
            processedPages,
            nextContinuationToken,
            summary);

        throw new IllegalStateException(
            "Simulated dry-run interruption after "
            + processedPages + " pages");
      }
    }

    log.info(
        "Receipt backfill completed. dryRun={}, summary={}",
        dryRun,
        summary);

    if (dryRun) {
      BackfillSizeStats total = new BackfillSizeStats();

      statsByCf.forEach((cf, stats) -> {
        total.merge(stats);

        log.info(
            "[DRY-RUN SIZE] CF={}, entities={}, toMigrate={}, "
            + "currentBytes={}, xmlBytes={}, projectedBytes={}, "
            + "savedBytes={}, reduction={}%",
            cf,
            stats.getEntityCount(),
            stats.getReceiptsToMigrate(),
            stats.getCurrentBytes(),
            stats.getXmlBytes(),
            stats.getProjectedBytes(),
            stats.getSavedBytes(),
            String.format(java.util.Locale.ROOT,
                "%.2f", stats.getReductionPercentage()));
      });

      log.info(
          "[DRY-RUN SIZE TOTAL] entities={}, toMigrate={}, "
          + "currentBytes={}, xmlBytes={}, projectedBytes={}, "
          + "savedBytes={}, reduction={}%",
          total.getEntityCount(),
          total.getReceiptsToMigrate(),
          total.getCurrentBytes(),
          total.getXmlBytes(),
          total.getProjectedBytes(),
          total.getSavedBytes(),
          String.format(java.util.Locale.ROOT,
              "%.2f", total.getReductionPercentage()));
    }
    return summary;
  }

  private MigrationSummary  processPage(
      PagedResponse<TableEntity> page,
      boolean dryRun,
      Map<String, BackfillSizeStats> statsByCf) {

    MigrationSummary pageSummary = new MigrationSummary();

    boolean pageFailed = false;

    for (TableEntity entity : page.getValue()) {
      pageSummary.incrementScanned();

      if (dryRun) {
        BackfillSizeStats stats = statsByCf.computeIfAbsent(
            entity.getPartitionKey(),
            key -> new BackfillSizeStats());

        collectSizeStats(entity, stats);
      }

      MigrationResult result =
          dryRun ? analyze(entity) : migrateOne(entity);

      if (result == MigrationResult.INCONSISTENT) {
        anomalyStore.saveInconsistent(
            entity.getPartitionKey(),
            entity.getRowKey(),
            dryRun);

        log.warn(
            "Inconsistent receipt persisted. "
                + "organizationFiscalCode={}, iuv={}",
            entity.getPartitionKey(),
            entity.getRowKey());
      }

      pageSummary.increment(result);

      if (dryRun
          && stopAfterEntities > 0
          && pageSummary.getScanned() >= stopAfterEntities) {

        log.warn(
            "[DRY-RUN TEST] Simulated interruption inside page "
            + "after {} entities. "
            + "Page checkpoint NOT saved. "
            + "pageSummary={}",
            pageSummary.getScanned(),
            pageSummary);

        throw new IllegalStateException(
            "Simulated dry-run interruption inside page after "
            + pageSummary.getScanned()
            + " entities");
      }

      if (result == MigrationResult.RETRYABLE_ERROR
          || result == MigrationResult.HASH_MISMATCH) {
        pageFailed = true;
      }
    }

    if (pageFailed) {
      throw new IllegalStateException(
          "Errors occurred while processing the backfill page");
    }
    return pageSummary;
  }

  private MigrationResult analyze(TableEntity entity) {

    Object documentProperty =
        entity.getProperty(DOCUMENT_PROPERTY);

    Object contentHashProperty =
        entity.getProperty(CONTENT_HASH_PROPERTY);

    if (documentProperty == null
        && contentHashProperty != null) {

      return MigrationResult.ALREADY_MIGRATED;
    }

    if (documentProperty == null) {
      return MigrationResult.INCONSISTENT;
    }

    return MigrationResult.MIGRATED;
  }

  private long estimateEntitySize(TableEntity entity) {
    try {
      Map<String, Object> properties = new LinkedHashMap<>();

      properties.put("PartitionKey", entity.getPartitionKey());
      properties.put("RowKey", entity.getRowKey());
      properties.putAll(entity.getProperties());

      return objectMapper.writeValueAsBytes(properties).length;

    } catch (JsonProcessingException e) {
      throw new IllegalStateException(
          "Cannot estimate entity size", e);
    }
  }

  private void collectSizeStats(
      TableEntity entity,
      BackfillSizeStats stats) {

    long currentSize = estimateEntitySize(entity);

    Object documentProperty = entity.getProperty(DOCUMENT_PROPERTY);

    if (documentProperty == null) {
      stats.add(currentSize, currentSize, 0, false);
      return;
    }

    byte[] document = documentProperty.toString()
        .getBytes(StandardCharsets.UTF_8);

    TableEntity projected = new TableEntity(
        entity.getPartitionKey(),
        entity.getRowKey());

    projected.getProperties().putAll(entity.getProperties());
    projected.getProperties().remove(DOCUMENT_PROPERTY);

    projected.addProperty(
        CONTENT_HASH_PROPERTY,
        ReceiptPayloadReader.sha256Hex(document));

    long projectedSize = estimateEntitySize(projected);

    stats.add(currentSize, projectedSize, document.length, true);
  }
}