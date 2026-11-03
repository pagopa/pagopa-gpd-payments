package it.gov.pagopa.payments.backfill;

import com.azure.core.http.rest.PagedIterable;
import com.azure.core.http.rest.PagedResponse;
import com.azure.data.tables.TableClient;
import com.azure.data.tables.models.ListEntitiesOptions;
import com.azure.data.tables.models.TableEntity;
import com.azure.data.tables.models.TableEntityUpdateMode;
import com.azure.data.tables.models.TableServiceException;
import it.gov.pagopa.payments.service.ReceiptBlobStore;
import it.gov.pagopa.payments.service.ReceiptPayloadReader;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ReceiptBackfillService {

  private static final String DOCUMENT_PROPERTY = "document";
  private static final String CONTENT_HASH_PROPERTY = "contentHash";

  private final TableClient tableClient;
  private final ReceiptBlobStore receiptBlobStore;
  private final BackfillCheckpointStore checkpointStore;

  public ReceiptBackfillService(
      TableClient tableClient,
      ReceiptBlobStore receiptBlobStore,
      BackfillCheckpointStore checkpointStore) {

    this.tableClient = tableClient;
    this.receiptBlobStore = receiptBlobStore;
    this.checkpointStore = checkpointStore;
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

    /*
     * Important: a null continuation token alone cannot distinguish
     * "not started" from "completed".
     */
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

    /*
     * No PartitionKey filter:
     * scan the entire Table page by page.
     */
    PagedIterable<TableEntity> entities =
        tableClient.listEntities(
            new ListEntitiesOptions(),
            null,
            null);

    Iterable<PagedResponse<TableEntity>> pages =
        continuationToken == null
            ? entities.iterableByPage()
            : entities.iterableByPage(continuationToken);

    for (PagedResponse<TableEntity> page : pages) {

      /*
       * If processPage throws because of a RETRYABLE_ERROR,
       * execution stops here and the checkpoint is NOT advanced.
       */
      processPage(
          page,
          summary,
          dryRun);

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
    }

    log.info(
        "Receipt backfill completed. dryRun={}, summary={}",
        dryRun,
        summary);

    return summary;
  }

  private void processPage(
      PagedResponse<TableEntity> page,
      MigrationSummary summary,
      boolean dryRun) {

    boolean pageFailed = false;

    for (TableEntity entity : page.getValue()) {
      summary.incrementScanned();

      MigrationResult result =
          dryRun ? analyze(entity) : migrateOne(entity);

      summary.increment(result);

      if (result == MigrationResult.RETRYABLE_ERROR
          || result == MigrationResult.HASH_MISMATCH) {
        pageFailed = true;
      }
    }

    if (pageFailed) {
      throw new IllegalStateException(
          "Errors occurred while processing the backfill page");
    }
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
}