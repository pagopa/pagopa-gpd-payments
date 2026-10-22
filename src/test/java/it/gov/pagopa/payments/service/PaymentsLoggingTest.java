package it.gov.pagopa.payments.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.rest.PagedIterable;
import com.azure.core.http.rest.PagedResponseBase;
import com.azure.data.tables.TableClient;
import com.azure.data.tables.models.ListEntitiesOptions;
import com.azure.data.tables.models.TableEntity;
import com.azure.data.tables.models.TableErrorCode;
import com.azure.data.tables.models.TableServiceError;
import com.azure.data.tables.models.TableServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import feign.Request;
import it.gov.pagopa.payments.client.GpdClient;
import it.gov.pagopa.payments.entity.Status;
import it.gov.pagopa.payments.exception.AppError;
import it.gov.pagopa.payments.exception.AppException;
import it.gov.pagopa.payments.logging.StructuredEcsEncoder;
import it.gov.pagopa.payments.model.PaymentOptionStatus;
import it.gov.pagopa.payments.model.PaymentsModelResponse;
import it.gov.pagopa.payments.model.PaymentsResult;
import it.gov.pagopa.payments.model.ReceiptModelResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class PaymentsLoggingTest {
  private static final String SECRET = "RAW_EXCEPTION_SECRET_SENTINEL";
  private static final String DOCUMENT = "<receipt>RECEIPT_PAYLOAD_SECRET</receipt>";
  private static final Map<String, String> OUTER = Map.of(
      "correlation_id", "list-correlation", "method", "listReceipts", "status", "outer-status",
      "ctx_creditor_id", "outer-creditor", "ctx_notice_number", "outer-nav", "ctx_iuv", "outer-iuv");

  private final TableClient table = mock(TableClient.class);
  private final GpdClient gpd = mock(GpdClient.class);
  private final PaymentsService payments = new PaymentsService(gpd, table);
  private final Logger logger = (Logger) LoggerFactory.getLogger(PaymentsService.class);
  private final StructuredEcsEncoder encoder = new StructuredEcsEncoder();
  private final ObjectMapper json = new ObjectMapper();
  private final List<JsonNode> records = new ArrayList<>();
  private final List<ILoggingEvent> events = new ArrayList<>();
  private final AppenderBase<ILoggingEvent> capture = new AppenderBase<>() {
    @Override
    protected void append(ILoggingEvent event) {
      event.prepareForDeferredProcessing();
      events.add(event);
      try {
        records.add(json.readTree(encoder.encode(event)));
      } catch (Exception failure) {
        throw new IllegalStateException(failure);
      }
    }
  };
  private Map<String, String> previousMdc;
  private Level previousLevel;

  @BeforeEach
  void startCapture() {
    previousMdc = MDC.getCopyOfContextMap();
    MDC.setContextMap(OUTER);
    previousLevel = logger.getLevel();
    logger.setLevel(Level.INFO);
    encoder.setContext(logger.getLoggerContext());
    encoder.start();
    capture.setContext(logger.getLoggerContext());
    capture.start();
    logger.addAppender(capture);
  }

  @AfterEach
  void restoreCapture() {
    logger.detachAppender(capture);
    logger.setLevel(previousLevel);
    capture.stop();
    encoder.stop();
    if (previousMdc == null) {
      MDC.clear();
    } else {
      MDC.setContextMap(previousMdc);
    }
  }

  @Test
  void receiptLookupDbFailurePreservesCauseAndPublicResponseMetadataWithoutDuplicateDiagnosis() {
    TableServiceException original = tableFailure(false);
    when(table.getEntity("creditor", "iuv")).thenThrow(original);

    AppException converted = assertThrows(AppException.class,
        () -> payments.getReceiptByOrganizationFCAndIUV("creditor", "iuv", null));

    assertConverted(converted, original, new AppException(AppError.DB_ERROR));
    assertTrue(records.isEmpty());
    verifyNoInteractions(gpd);
    assertEquals(OUTER, MDC.getCopyOfContextMap());
  }

  @Test
  void listQueryDbFailurePreservesCauseAndPublicResponseMetadata() {
    TableServiceException original = tableFailure(false);
    when(table.listEntities(any(ListEntitiesOptions.class), isNull(), isNull())).thenThrow(original);

    AppException converted = assertThrows(AppException.class,
        () -> payments.getOrganizationReceipts("creditor", null, null,
            "2026-10-01", "2026-10-02", 0, 10, null, null));

    assertConverted(converted, original, new AppException(AppError.DB_ERROR, "ALL"));
    assertTrue(records.isEmpty());
    assertEquals(OUTER, MDC.getCopyOfContextMap());
  }

  @Test
  void receiptStatusUpdateDbFailurePreservesCauseAndResponseMetadata() {
    TableEntity receipt = row("creditor", "iuv", Status.CREATED);
    TableServiceException original = tableFailure(false);
    PaymentsModelResponse paid = new PaymentsModelResponse();
    paid.setStatus(PaymentOptionStatus.PO_PAID);
    when(table.getEntity("creditor", "iuv")).thenReturn(receipt);
    when(gpd.getPaymentOption("creditor", "iuv")).thenReturn(paid);
    doThrow(original).when(table).updateEntity(receipt);

    AppException converted = assertThrows(AppException.class,
        () -> payments.getReceiptByOrganizationFCAndIUV("creditor", "iuv", null));

    assertConverted(converted, original,
        new AppException(AppError.DB_ERROR, "Error when updating receipt status"));
    assertTrue(records.isEmpty());
    assertEquals(OUTER, MDC.getCopyOfContextMap());
  }

  @Test
  void missingReceiptAndSegregationRejectionStayBusinessFailuresWithoutErrorDiagnosis() {
    TableServiceException original = tableFailure(true);
    when(table.getEntity("creditor", "00123")).thenThrow(original);

    AppException missing = assertThrows(AppException.class,
        () -> payments.getReceiptByOrganizationFCAndIUV("creditor", "00123", null));
    assertConverted(missing, original,
        new AppException(AppError.RECEIPT_NOT_FOUND, "creditor", "00123"));
    AppException forbidden = assertThrows(AppException.class,
        () -> payments.getReceiptByOrganizationFCAndIUV("creditor", "99123", List.of("00")));
    assertEquals(AppError.FORBIDDEN_SEGREGATION_CODE.getHttpStatus(), forbidden.getHttpStatus());
    assertTrue(records.isEmpty());
    verify(table, never()).getEntity("creditor", "99123");
    verifyNoInteractions(gpd);
    assertEquals(OUTER, MDC.getCopyOfContextMap());
  }

  @Test
  void mixedListRestoresEachRowContextAndDiagnosesOnlyAbsorbedDbFailure() {
    TableEntity first = row("creditor-one", "iuv-one", Status.CREATED);
    TableEntity dbFailureRow = row("creditor-db", "iuv-db", Status.CREATED);
    TableEntity notFoundRow = row("creditor-missing", "iuv-missing", Status.CREATED);
    TableEntity unpaidRow = row("creditor-unpaid", "iuv-unpaid", Status.CREATED);
    TableEntity last = row("creditor-last", "iuv-last", Status.CREATED);
    List<TableEntity> rows = List.of(first, dbFailureRow, notFoundRow, unpaidRow, last);
    PagedIterable<TableEntity> page = new PagedIterable<>(() ->
        new PagedResponseBase<Void, TableEntity>(null, 200, new HttpHeaders(), rows, null, null));
    when(table.listEntities(any(ListEntitiesOptions.class), isNull(), isNull())).thenReturn(page);
    TableServiceException databaseFailure = tableFailure(false);
    List<Map<String, String>> observedContexts = new ArrayList<>();
    when(gpd.getPaymentOption(anyString(), anyString())).thenAnswer(invocation -> {
      String creditor = invocation.getArgument(0);
      String iuv = invocation.getArgument(1);
      assertRowContext(creditor, iuv);
      observedContexts.add(MDC.getCopyOfContextMap());
      if ("iuv-missing".equals(iuv)) {
        throw notFound();
      }
      PaymentsModelResponse payment = new PaymentsModelResponse();
      payment.setStatus("iuv-unpaid".equals(iuv) ? PaymentOptionStatus.PO_UNPAID : PaymentOptionStatus.PO_PAID);
      return payment;
    });
    doAnswer(invocation -> {
      TableEntity entity = invocation.getArgument(0);
      assertRowContext(entity.getPartitionKey(), entity.getRowKey());
      if (entity == dbFailureRow) {
        throw databaseFailure;
      }
      return null;
    }).when(table).updateEntity(any(TableEntity.class));

    PaymentsResult<ReceiptModelResponse> result = payments.getOrganizationReceipts(
        "requested-creditor", null, null, "2026-10-01", "2026-10-02", 0, 10, null, null);

    assertEquals(List.of("iuv-one", "iuv-last"), result.getResults().stream()
        .map(ReceiptModelResponse::getIuv).toList());
    assertEquals(List.of("creditor-one", "creditor-last"), result.getResults().stream()
        .map(ReceiptModelResponse::getOrganizationFiscalCode).toList());
    assertEquals(2, result.getLength());
    assertEquals(0, result.getCurrentPageNumber());
    assertEquals(1, result.getTotalPages());
    assertEquals(5, rows.size());
    for (ReceiptModelResponse receipt : result.getResults()) {
      assertEquals("DEBTOR_SECRET", receipt.getDebtor());
      assertEquals("2026-10-01T10:15:30", receipt.getPaymentDateTime());
      assertEquals(Status.PAID.name(), receipt.getStatus());
    }
    assertEquals(5, observedContexts.size());
    assertEquals(List.of("iuv-one", "iuv-db", "iuv-missing", "iuv-unpaid", "iuv-last"),
        observedContexts.stream().map(context -> context.get("ctx_iuv")).toList());
    assertEquals(OUTER, MDC.getCopyOfContextMap());
    verify(table, never()).updateEntity(notFoundRow);
    verify(table, never()).updateEntity(unpaidRow);

    assertEquals(3, records.size());
    JsonNode diagnosis = named("Receipt check failed");
    assertEquals("ERROR", diagnosis.path("log.level").asText());
    assertEquals("creditor-db", diagnosis.path("ctx_creditor_id").asText());
    assertEquals("iuv-db", diagnosis.path("ctx_iuv").asText());
    assertEquals(AppException.class.getName(), diagnosis.path("error.type").asText());
    assertEquals(new AppException(AppError.DB_ERROR).getMessage(),
        diagnosis.path("error.message").asText());
    assertTrue(diagnosis.path("error.stack_trace").asText().contains(TableServiceException.class.getName()));
    assertTrue(diagnosis.path("error.stack_trace").asText().contains("at "));
    assertEquals("WARN", named("Receipt omitted: payment option not found").path("log.level").asText());
    assertEquals("iuv-missing", named("Receipt omitted: payment option not found").path("ctx_iuv").asText());
    assertEquals("WARN", named("Receipt omitted: ineligible payment state").path("log.level").asText());
    assertEquals("iuv-unpaid", named("Receipt omitted: ineligible payment state").path("ctx_iuv").asText());
    assertEquals(1, records.stream().filter(record ->
        "ERROR".equals(record.path("log.level").asText())).count());
    assertSafeEvents();
  }

  @Test
  void unexpectedRowFailurePropagatesUnchangedAndStillRestoresOuterContext() {
    IllegalStateException failure = new IllegalStateException(SECRET);
    when(gpd.getPaymentOption("creditor", "iuv")).thenAnswer(invocation -> {
      assertRowContext("creditor", "iuv");
      throw failure;
    });

    assertSame(failure, assertThrows(IllegalStateException.class,
        () -> payments.getGPDCheckedReceiptsList(List.of(row("creditor", "iuv", Status.CREATED)))));

    assertEquals(OUTER, MDC.getCopyOfContextMap());
    assertTrue(records.isEmpty());
    verifyNoInteractions(table);
  }

  @Test
  void alreadyPaidRowsKeepResultOrderWithoutRemoteChecksOrExtraLogs() {
    List<TableEntity> rows = List.of(
        row("creditor-one", "iuv-one", Status.PAID), row("creditor-two", "iuv-two", Status.PAID));

    List<ReceiptModelResponse> result = payments.getGPDCheckedReceiptsList(rows);

    assertEquals(List.of("iuv-one", "iuv-two"),
        result.stream().map(ReceiptModelResponse::getIuv).toList());
    assertEquals(OUTER, MDC.getCopyOfContextMap());
    assertTrue(records.isEmpty());
    verifyNoInteractions(gpd, table);
  }

  private static TableEntity row(String creditor, String iuv, Status status) {
    return new TableEntity(creditor, iuv).addProperty("status", status.name())
        .addProperty("document", DOCUMENT).addProperty("debtor", "DEBTOR_SECRET")
        .addProperty("paymentDate", "2026-10-01T10:15:30");
  }

  private static TableServiceException tableFailure(boolean missing) {
    return new TableServiceException(SECRET, null, new TableServiceError(
        missing ? TableErrorCode.RESOURCE_NOT_FOUND.toString() : "InternalError", SECRET));
  }

  private static FeignException.NotFound notFound() {
    Request request = Request.create(Request.HttpMethod.GET,
        "https://example.test/URL_SECRET?credential=URL_SECRET", Map.of(), null,
        StandardCharsets.UTF_8, null);
    return new FeignException.NotFound(SECRET, request,
        DOCUMENT.getBytes(StandardCharsets.UTF_8), Map.of());
  }

  private static void assertConverted(AppException actual, Throwable original, AppException expected) {
    assertSame(original, actual.getCause());
    assertEquals(expected.getHttpStatus(), actual.getHttpStatus());
    assertEquals(expected.getTitle(), actual.getTitle());
    assertEquals(expected.getMessage(), actual.getMessage());
  }

  private static void assertRowContext(String creditor, String iuv) {
    assertEquals(creditor, MDC.get("ctx_creditor_id"));
    assertEquals(iuv, MDC.get("ctx_iuv"));
    assertNull(MDC.get("ctx_notice_number"));
    assertEquals(OUTER.get("correlation_id"), MDC.get("correlation_id"));
    assertEquals(OUTER.get("method"), MDC.get("method"));
    assertEquals(OUTER.get("status"), MDC.get("status"));
  }

  private JsonNode named(String message) {
    List<JsonNode> matching = records.stream()
        .filter(record -> message.equals(record.path("message").asText())).toList();
    assertEquals(1, matching.size(), message);
    return matching.get(0);
  }

  private void assertSafeEvents() {
    assertEquals(events.size(), records.size());
    for (ILoggingEvent event : events) {
      assertNull(event.getThrowableProxy());
    }
    for (JsonNode record : records) {
      String encoded = record.toString();
      for (String secret : List.of(SECRET, "RECEIPT_PAYLOAD_SECRET", "DEBTOR_SECRET",
          "URL_SECRET", "<receipt>")) {
        assertFalse(encoded.contains(secret), encoded);
      }
      assertEquals(OUTER.get("method"), record.path("method").asText());
      assertEquals(OUTER.get("correlation_id"), record.path("correlation_id").asText());
      assertFalse(record.has("ctx_notice_number"));
      assertFalse(record.has("args"));
      assertFalse(record.has("httpCode"));
      assertFalse(record.has("httpMethod"));
      assertFalse(record.has("soap_method"));
      assertFalse(record.has("fault_code"));
      record.fieldNames().forEachRemaining(name -> {
        assertFalse(name.startsWith("http."), name);
        assertFalse(name.startsWith("soap."), name);
      });
    }
  }
}
