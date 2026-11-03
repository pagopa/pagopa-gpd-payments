package it.gov.pagopa.payments.backfill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.azure.core.http.rest.PagedIterable;
import com.azure.core.http.rest.PagedResponse;
import com.azure.data.tables.TableClient;
import com.azure.data.tables.models.ListEntitiesOptions;
import com.azure.data.tables.models.TableEntity;
import com.azure.data.tables.models.TableEntityUpdateMode;
import it.gov.pagopa.payments.service.ReceiptBlobStore;
import it.gov.pagopa.payments.service.ReceiptPayloadReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReceiptBackfillServiceTest {

  private static final String ORGANIZATION_CF = "80007580279";
  private static final String IUV = "123456789";
  private static final String DOCUMENT = "<receipt>test</receipt>";

  @Mock private TableClient tableClient;
  @Mock private ReceiptBlobStore receiptBlobStore;
  @Mock private BackfillCheckpointStore checkpointStore;

  private ReceiptBackfillService receiptBackfillService;

  @BeforeEach
  void setUp() {
    receiptBackfillService =
        new ReceiptBackfillService(tableClient, receiptBlobStore, checkpointStore);
  }

  @Test
  void shouldMigrateLegacyReceipt() {
    byte[] document = DOCUMENT.getBytes(StandardCharsets.UTF_8);
    String expectedHash = ReceiptPayloadReader.sha256Hex(document);

    TableEntity entity =
        new TableEntity(ORGANIZATION_CF, IUV)
            .addProperty("document", DOCUMENT)
            .addProperty("status", "PAID");

    when(receiptBlobStore.download(ORGANIZATION_CF, IUV)).thenReturn(document);

    MigrationResult result = receiptBackfillService.migrateOne(entity);

    assertEquals(MigrationResult.MIGRATED, result);
    verify(receiptBlobStore).upload(ORGANIZATION_CF, IUV, document);
    verify(receiptBlobStore).download(ORGANIZATION_CF, IUV);

    assertNull(entity.getProperty("document"));
    assertEquals(expectedHash, entity.getProperty("contentHash"));
    assertEquals("PAID", entity.getProperty("status"));

    verify(tableClient)
        .updateEntityWithResponse(entity, TableEntityUpdateMode.REPLACE, true, null, null);
  }

  @Test
  void shouldSkipAlreadyMigratedReceipt() {
    TableEntity entity =
        new TableEntity(ORGANIZATION_CF, IUV)
            .addProperty("contentHash", "abc123")
            .addProperty("status", "PAID");

    MigrationResult result = receiptBackfillService.migrateOne(entity);

    assertEquals(MigrationResult.ALREADY_MIGRATED, result);
    verifyNoInteractions(receiptBlobStore);
    verifyNoInteractions(tableClient);
  }

  @Test
  void shouldReturnInconsistentWhenDocumentAndHashAreMissing() {
    TableEntity entity = new TableEntity(ORGANIZATION_CF, IUV).addProperty("status", "PAID");

    MigrationResult result = receiptBackfillService.migrateOne(entity);

    assertEquals(MigrationResult.INCONSISTENT, result);
    verifyNoInteractions(receiptBlobStore);
    verifyNoInteractions(tableClient);
  }

  @Test
  void shouldOverwriteExistingBlobAndMigrate() {
    byte[] document = DOCUMENT.getBytes(StandardCharsets.UTF_8);

    TableEntity entity = new TableEntity(ORGANIZATION_CF, IUV).addProperty("document", DOCUMENT);

    when(receiptBlobStore.download(ORGANIZATION_CF, IUV)).thenReturn(document);

    MigrationResult result = receiptBackfillService.migrateOne(entity);

    assertEquals(MigrationResult.MIGRATED, result);
    // l'upload avviene sempre (overwrite), anche se il blob esisteva già
    verify(receiptBlobStore).upload(ORGANIZATION_CF, IUV, document);
    verify(tableClient)
        .updateEntityWithResponse(entity, TableEntityUpdateMode.REPLACE, true, null, null);
  }

  @Test
  void shouldReturnHashMismatchWhenDownloadedContentDiffers() {
    byte[] document = DOCUMENT.getBytes(StandardCharsets.UTF_8);
    byte[] differentDocument = "<receipt>different</receipt>".getBytes(StandardCharsets.UTF_8);

    TableEntity entity = new TableEntity(ORGANIZATION_CF, IUV).addProperty("document", DOCUMENT);

    when(receiptBlobStore.download(ORGANIZATION_CF, IUV)).thenReturn(differentDocument);

    MigrationResult result = receiptBackfillService.migrateOne(entity);

    assertEquals(MigrationResult.HASH_MISMATCH, result);
    verify(receiptBlobStore).upload(ORGANIZATION_CF, IUV, document);

    verify(tableClient, never())
        .updateEntityWithResponse(any(), any(), any(Boolean.class), isNull(), isNull());

    assertNotNull(entity.getProperty("document"));
    assertNull(entity.getProperty("contentHash"));
  }

  @Test
  void shouldReturnRetryableErrorWhenBlobUploadFails() {
    TableEntity entity = new TableEntity(ORGANIZATION_CF, IUV).addProperty("document", DOCUMENT);

    doThrow(new RuntimeException("Blob upload error"))
        .when(receiptBlobStore)
        .upload(any(), any(), any());

    MigrationResult result = receiptBackfillService.migrateOne(entity);

    assertEquals(MigrationResult.RETRYABLE_ERROR, result);
    verify(receiptBlobStore, never()).download(any(), any());
    verify(tableClient, never())
        .updateEntityWithResponse(any(), any(), any(Boolean.class), isNull(), isNull());
  }

  @Test
  void shouldReturnRetryableErrorWhenTableUpdateFails() {
    byte[] document = DOCUMENT.getBytes(StandardCharsets.UTF_8);

    TableEntity entity = new TableEntity(ORGANIZATION_CF, IUV).addProperty("document", DOCUMENT);

    when(receiptBlobStore.download(ORGANIZATION_CF, IUV)).thenReturn(document);

    doThrow(new RuntimeException("Table update error"))
        .when(tableClient)
        .updateEntityWithResponse(
            any(TableEntity.class), eq(TableEntityUpdateMode.REPLACE), eq(true), isNull(), isNull());

    MigrationResult result = receiptBackfillService.migrateOne(entity);

    assertEquals(MigrationResult.RETRYABLE_ERROR, result);
    verify(receiptBlobStore).upload(ORGANIZATION_CF, IUV, document);
  }

  @Test
  void shouldMigrateAllReceiptsForOrganization() {
    TableEntity entity1 =
        new TableEntity(ORGANIZATION_CF, "IUV001").addProperty("document", "<receipt>1</receipt>");
    TableEntity entity2 =
        new TableEntity(ORGANIZATION_CF, "IUV002").addProperty("document", "<receipt>2</receipt>");

    byte[] document1 = "<receipt>1</receipt>".getBytes(StandardCharsets.UTF_8);
    byte[] document2 = "<receipt>2</receipt>".getBytes(StandardCharsets.UTF_8);

    PagedIterable<TableEntity> pagedIterable = mock(PagedIterable.class);
    PagedResponse<TableEntity> page = mock(PagedResponse.class);

    when(tableClient.listEntities(any(ListEntitiesOptions.class), isNull(), isNull()))
        .thenReturn(pagedIterable);
    when(pagedIterable.iterableByPage()).thenReturn(List.of(page));
    when(page.getValue()).thenReturn(List.of(entity1, entity2));
    when(page.getContinuationToken()).thenReturn(null);

    when(receiptBlobStore.download(ORGANIZATION_CF, "IUV001")).thenReturn(document1);
    when(receiptBlobStore.download(ORGANIZATION_CF, "IUV002")).thenReturn(document2);

    MigrationSummary summary = receiptBackfillService.migrateOrganization(ORGANIZATION_CF, false);

    assertEquals(2, summary.getScanned());
    assertEquals(2, summary.getMigrated());
    assertEquals(0, summary.getAlreadyMigrated());
    assertEquals(0, summary.getInconsistent());
    assertEquals(0, summary.getHashMismatch());
    assertEquals(0, summary.getFailed());

    verify(receiptBlobStore).upload(ORGANIZATION_CF, "IUV001", document1);
    verify(receiptBlobStore).upload(ORGANIZATION_CF, "IUV002", document2);

    verify(tableClient, times(2))
        .updateEntityWithResponse(
            any(TableEntity.class), eq(TableEntityUpdateMode.REPLACE), eq(true), isNull(), isNull());
  }

  @Test
  void shouldAnalyzeReceiptsWithoutWritingWhenDryRun() {
    TableEntity legacyEntity =
        new TableEntity(ORGANIZATION_CF, "IUV001").addProperty("document", "<receipt>1</receipt>");
    TableEntity migratedEntity =
        new TableEntity(ORGANIZATION_CF, "IUV002").addProperty("contentHash", "abc123");
    TableEntity inconsistentEntity = new TableEntity(ORGANIZATION_CF, "IUV003");

    PagedIterable<TableEntity> pagedIterable = mock(PagedIterable.class);
    PagedResponse<TableEntity> page = mock(PagedResponse.class);

    when(tableClient.listEntities(any(ListEntitiesOptions.class), isNull(), isNull()))
        .thenReturn(pagedIterable);
    when(pagedIterable.iterableByPage()).thenReturn(List.of(page));
    when(page.getValue()).thenReturn(List.of(legacyEntity, migratedEntity, inconsistentEntity));
    when(page.getContinuationToken()).thenReturn(null);

    MigrationSummary summary = receiptBackfillService.migrateOrganization(ORGANIZATION_CF, true);

    assertEquals(3, summary.getScanned());
    assertEquals(1, summary.getMigrated());
    assertEquals(1, summary.getAlreadyMigrated());
    assertEquals(1, summary.getInconsistent());
    assertEquals(0, summary.getHashMismatch());
    assertEquals(0, summary.getFailed());

    verifyNoInteractions(receiptBlobStore);
    verify(tableClient, never())
        .updateEntityWithResponse(any(), any(), any(Boolean.class), isNull(), isNull());
  }

  @Test
  void shouldMigrateReceiptsAcrossMultiplePages() {
    TableEntity entity1 =
        new TableEntity(ORGANIZATION_CF, "IUV001").addProperty("document", "<receipt>1</receipt>");
    TableEntity entity2 =
        new TableEntity(ORGANIZATION_CF, "IUV002").addProperty("document", "<receipt>2</receipt>");

    byte[] document1 = "<receipt>1</receipt>".getBytes(StandardCharsets.UTF_8);
    byte[] document2 = "<receipt>2</receipt>".getBytes(StandardCharsets.UTF_8);

    PagedIterable<TableEntity> pagedIterable = mock(PagedIterable.class);
    PagedResponse<TableEntity> page1 = mock(PagedResponse.class);
    PagedResponse<TableEntity> page2 = mock(PagedResponse.class);

    when(tableClient.listEntities(any(ListEntitiesOptions.class), isNull(), isNull()))
        .thenReturn(pagedIterable);
    when(pagedIterable.iterableByPage()).thenReturn(List.of(page1, page2));

    when(page1.getValue()).thenReturn(List.of(entity1));
    when(page1.getContinuationToken()).thenReturn("token-page-2");
    when(page2.getValue()).thenReturn(List.of(entity2));
    when(page2.getContinuationToken()).thenReturn(null);

    when(receiptBlobStore.download(ORGANIZATION_CF, "IUV001")).thenReturn(document1);
    when(receiptBlobStore.download(ORGANIZATION_CF, "IUV002")).thenReturn(document2);

    MigrationSummary summary = receiptBackfillService.migrateOrganization(ORGANIZATION_CF, false);

    assertEquals(2, summary.getScanned());
    assertEquals(2, summary.getMigrated());
    assertEquals(0, summary.getFailed());

    verify(receiptBlobStore).upload(ORGANIZATION_CF, "IUV001", document1);
    verify(receiptBlobStore).upload(ORGANIZATION_CF, "IUV002", document2);

    verify(tableClient, times(2))
        .updateEntityWithResponse(
            any(TableEntity.class), eq(TableEntityUpdateMode.REPLACE), eq(true), isNull(), isNull());

    verify(page1).getContinuationToken();
    verify(page2).getContinuationToken();
  }
}