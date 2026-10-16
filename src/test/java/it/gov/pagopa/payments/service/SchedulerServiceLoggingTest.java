package it.gov.pagopa.payments.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.azure.core.http.HttpResponse;
import com.azure.core.http.rest.PagedIterable;
import com.azure.core.util.BinaryData;
import com.azure.core.util.Context;
import com.azure.data.tables.TableClient;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.queue.QueueClient;
import com.azure.storage.queue.models.QueueMessageItem;
import com.microsoft.azure.storage.StorageException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import feign.Request;
import feign.Response;
import it.gov.pagopa.payments.client.GpdClient;
import it.gov.pagopa.payments.client.impl.BlobStorageClientImpl;
import it.gov.pagopa.payments.config.VerticalServicesConfig;
import it.gov.pagopa.payments.endpoints.validation.exceptions.PartnerValidationException;
import it.gov.pagopa.payments.entity.ReceiptEntity;
import it.gov.pagopa.payments.logging.ContextSnapshot;
import it.gov.pagopa.payments.logging.EventDetails;
import it.gov.pagopa.payments.logging.LogEventWriter;
import it.gov.pagopa.payments.logging.FailureDiagnostics;
import it.gov.pagopa.payments.logging.OperationLogScope;
import it.gov.pagopa.payments.model.DeadLetterMessage;
import it.gov.pagopa.payments.model.PaaErrorEnum;
import it.gov.pagopa.payments.model.PaymentOptionModel;
import it.gov.pagopa.payments.model.enumeration.DeadLetterReason;
import it.gov.pagopa.payments.model.partner.ObjectFactory;
import it.gov.pagopa.payments.service.impl.DeadLetterServiceImpl;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.client.RestTemplate;
import org.xml.sax.SAXParseException;

class SchedulerServiceLoggingTest {
  private static final String FISCAL_CODE = "77777777777";
  private static final String NOTICE = "302000000000000001";
  private static final String POP_RECEIPT = "private-pop-receipt";
  private static final String XML = """
      <receipt>
        <fiscalCode>77777777777</fiscalCode>
        <creditorReferenceId>reference</creditorReferenceId>
        <noticeNumber>302000000000000001</noticeNumber>
        <paymentDateTime>2026-01-01T12:00:00</paymentDateTime>
        <receiptId>receipt-id</receiptId>
        <PSPCompanyName>private-psp-company</PSPCompanyName>
        <paymentMethod>card</paymentMethod>
        <fee>1.25</fee>
        <entityUniqueIdentifierValue>private-debtor</entityUniqueIdentifierValue>
        <standIn>true</standIn>
      </receipt>
      """;

  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  private final Map<Logger, Level> levels = new HashMap<>();
  private Map<String, String> previousMdc;
  private ListAppender<ILoggingEvent> logs;
  private QueueClient queue;
  private PartnerService partner;
  private DeadLetterService deadLetters;
  private SchedulerService service;
  private SchedulerService.JobCounts counts;

  @BeforeEach
  void setUp() {
    previousMdc = MDC.getCopyOfContextMap();
    MDC.clear();
    queue = mock(QueueClient.class);
    partner = mock(PartnerService.class);
    deadLetters = mock(DeadLetterService.class);
    service = service(deadLetters);
    counts = new SchedulerService.JobCounts();
    logs = new ListAppender<>() {
      @Override
      protected void append(ILoggingEvent event) {
        event.prepareForDeferredProcessing();
        super.append(event);
      }
    };
    logs.start();
    for (Class<?> type : List.of(
        SchedulerService.class, PartnerService.class,
        BlobStorageClientImpl.class, DeadLetterServiceImpl.class)) {
      Logger logger = (Logger) LoggerFactory.getLogger(type);
      levels.put(logger, logger.getLevel());
      logger.setLevel(Level.DEBUG);
      logger.addAppender(logs);
    }
  }

  @AfterEach
  void tearDown() {
    try {
      for (ILoggingEvent event : logs.list) {
        assertNull(event.getThrowableProxy(), "Never pass a raw throwable to Logback");
        assertTrue(event.getArgumentArray() == null || event.getArgumentArray().length == 0);
        Map<String, String> fields = event.getMDCPropertyMap();
        assertFalse(fields.containsKey("args"));
        assertFalse(fields.containsKey("popReceipt"));
        assertFalse(fields.containsKey("httpCode"), "Message events are not HTTP completions");
        String rendered = fields + event.getFormattedMessage();
        for (String secret : List.of(POP_RECEIPT, "private-debtor", "private-psp-company",
            "<receipt>", "originalMessage", "queueEnvelope")) {
          assertFalse(rendered.contains(secret), secret);
        }
      }
    } finally {
      levels.forEach((logger, level) -> {
        logger.detachAppender(logs);
        logger.setLevel(level);
      });
      logs.stop();
      if (previousMdc == null) {
        MDC.clear();
      } else {
        MDC.setContextMap(previousMdc);
      }
    }
  }

  @Test
  void successfulReceiptIsProcessedOnlyAfterPartnerAndDeleteSucceed() throws Exception {
    receive(message("processed", 1, XML));
    when(partner.getFeeInCent(new BigDecimal("1.25"))).thenReturn(125L);
    when(partner.getReceiptPaymentOptionScheduler(eq(NOTICE), eq(FISCAL_CODE),
        eq("reference"), eq(true), any(), any())).thenAnswer(invocation -> {
          assertTrue(logs.list.isEmpty());
          return null;
        });
    doAnswer(invocation -> {
      assertTrue(logs.list.isEmpty(), "Completion must follow successful deletion");
      assertCounts(0, 0, 0, 0, 0);
      return null;
    }).when(queue).deleteMessage("processed", POP_RECEIPT);

    service.retryFailedPaSendRT(counts);

    var ordered = inOrder(partner, queue);
    ArgumentCaptor<PaymentOptionModel> body = ArgumentCaptor.forClass(PaymentOptionModel.class);
    ArgumentCaptor<ReceiptEntity> receipt = ArgumentCaptor.forClass(ReceiptEntity.class);
    ordered.verify(partner).getReceiptPaymentOptionScheduler(
        eq(NOTICE), eq(FISCAL_CODE), eq("reference"), eq(true), body.capture(), receipt.capture());
    ordered.verify(queue).deleteMessage("processed", POP_RECEIPT);
    assertEquals("receipt-id", body.getValue().getIdReceipt());
    assertEquals(LocalDateTime.parse("2026-01-01T12:00:00"), body.getValue().getPaymentDate());
    assertEquals("125", body.getValue().getFee());
    assertEquals(XML, receipt.getValue().getDocument());
    assertEquals("private-debtor", receipt.getValue().getDebtor());
    assertCodes("RECEIPT_PROCESSING_COMPLETED");
    assertEquals(Level.INFO, logs.list.get(0).getLevel());
    assertEquals(json.readTree("""
        {"messageId":"processed","attempt":1}
        """), details(0));
    assertEquals(NOTICE, fields(0).get("noticeNumber"));
    assertEquals(FISCAL_CODE, fields(0).get("creditorFiscalCode"));
    assertCounts(1, 0, 0, 0, 0);
    verifyNoInteractions(deadLetters);
    assertNull(OperationLogScope.currentOrNull());
  }

  @ParameterizedTest
  @CsvSource({"1,120", "2,240", "5,1920", "6,3600", "10,3600"})
  void retryableFeignFailureSchedulesExactOriginalXmlWithUnchangedBackoff(
      long attempt, long seconds) throws Exception {
    service = new SchedulerService(10, 30L, 120L, 3600L, 2.0, queue, partner, deadLetters);
    receive(message("retry", attempt, XML));
    partnerFails(feignFailure());
    when(queue.updateMessageWithResponse(eq("retry"), eq(POP_RECEIPT), eq(XML),
        eq(Duration.ofSeconds(seconds)), isNull(), eq(Context.NONE))).thenAnswer(invocation -> {
          assertTrue(logs.list.isEmpty(), "Do not claim a retry until visibility was updated");
          return null;
        });

    service.retryFailedPaSendRT(counts);

    verify(queue).updateMessageWithResponse("retry", POP_RECEIPT, XML,
        Duration.ofSeconds(seconds), null, Context.NONE);
    verify(queue, never()).deleteMessage(anyString(), anyString());
    assertCodes("RECEIPT_RETRY_SCHEDULED");
    assertEquals(Level.INFO, logs.list.get(0).getLevel());
    assertEquals(json.readTree("""
        {"dependency":"gpd-core","attempt":%d,"retryDelayMs":%d,
         "reason":"RETRYABLE_FAILURE","messageId":"retry"}
        """.formatted(attempt, seconds * 1000)), details(0));
    assertCounts(0, 1, 0, 0, 0);
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 429, 500, 503})
  void actualPartnerFeignFailureIsRecoveredOnlyAfterVisibilityUpdate(int status) throws Exception {
    GpdClient gpd = actualPartner();
    receive(message("retry", 2, XML));
    when(gpd.sendPaymentOptionReceipt(eq(FISCAL_CODE), eq(NOTICE), any()))
        .thenThrow(feignFailure(status));
    when(queue.updateMessageWithResponse(eq("retry"), eq(POP_RECEIPT), eq(XML),
        eq(Duration.ofSeconds(240)), isNull(), eq(Context.NONE))).thenAnswer(invocation -> {
          assertTrue(logs.list.isEmpty(), "Recovery requires a successful visibility update");
          assertCounts(0, 0, 0, 0, 0);
          return null;
        });

    service.retryFailedPaSendRT(counts);

    verify(gpd).sendPaymentOptionReceipt(eq(FISCAL_CODE), eq(NOTICE), any());
    verify(queue).updateMessageWithResponse(
        "retry", POP_RECEIPT, XML, Duration.ofSeconds(240), null, Context.NONE);
    verify(queue, never()).deleteMessage(anyString(), anyString());
    assertCodes("RECEIPT_RETRY_SCHEDULED");
    assertEquals(Level.INFO, logs.list.get(0).getLevel());
    assertEquals(json.readTree("""
        {"dependency":"gpd-core","attempt":2,"retryDelayMs":240000,
         "reason":"RETRYABLE_FAILURE","messageId":"retry"}
        """), details(0));
    assertFalse(fields(0).containsKey("error.type"));
    assertFalse(fields(0).containsKey("error.message"));
    assertFalse(fields(0).containsKey("error.stack_trace"));
    assertCounts(0, 1, 0, 0, 0);
    verifyNoInteractions(deadLetters);
    assertNull(OperationLogScope.currentOrNull());
  }

  @Test
  void actualPartnerFeignFailureWithFailedVisibilityUpdateHasOnlyQueueError() throws Exception {
    GpdClient gpd = actualPartner();
    receive(message("retry", 2, XML));
    FeignException initial = feignFailure();
    when(gpd.sendPaymentOptionReceipt(anyString(), anyString(), any())).thenThrow(initial);
    IllegalStateException original = new IllegalStateException("visibility update failed");
    when(queue.updateMessageWithResponse(anyString(), anyString(), anyString(),
        any(Duration.class), isNull(), eq(Context.NONE))).thenThrow(original);

    assertSame(original, assertThrows(IllegalStateException.class,
        () -> service.retryFailedPaSendRT(counts)));

    assertCodes("DEPENDENCY_CALL_FAILED");
    assertDependency(0, "azure-queue-storage", "rescheduleReceiptRetry", original);
    assertEquals(SchedulerService.class.getName(), logs.list.get(0).getLoggerName());
    assertArrayEquals(new Throwable[] {initial}, original.getSuppressed());
    assertTrue(fields(0).get("error.stack_trace").contains(initial.getMessage()));
    assertCounts(0, 0, 0, 0, 1);
    verify(queue, never()).deleteMessage(anyString(), anyString());
    verifyNoInteractions(deadLetters);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void directXmlRecoveryOwnsFeignFailureOnlyInAnExplicitScope(boolean scoped) throws Exception {
    GpdClient gpd = actualPartner();
    FeignException original = feignFailure();
    when(gpd.sendPaymentOptionReceipt(anyString(), anyString(), any())).thenThrow(original);

    try (OperationLogScope scope = scoped ? OperationLogScope.open("receipt-recovery") : null) {
      service.handlingXml(XML, XPathFactory.newInstance().newXPath(), message("retry", 2, XML));

      assertSame(scope, OperationLogScope.currentOrNull());
      if (scope != null) {
        assertTrue(scope.isHandled(original));
      }
      assertCodes("RECEIPT_RETRY_SCHEDULED");
      assertEquals(Level.INFO, logs.list.get(0).getLevel());
      assertEquals("gpd-core", details(0).get("dependency").asText());
    }
    assertNull(OperationLogScope.currentOrNull());
  }

  @Test
  void actualPartnerNonFeignRuntimeFailureKeepsOneGpdErrorAndCountsFailed() throws Exception {
    GpdClient gpd = actualPartner();
    receive(message("failed", 1, XML));
    IllegalStateException original = new IllegalStateException("GPD receipt call failed");
    when(gpd.sendPaymentOptionReceipt(anyString(), anyString(), any())).thenThrow(original);

    assertSame(original, assertThrows(IllegalStateException.class,
        () -> service.retryFailedPaSendRT(counts)));

    assertCodes("DEPENDENCY_CALL_FAILED");
    assertDependency(0, "gpd-core", "sendPaymentOptionReceipt", original);
    assertEquals(SchedulerService.class.getName(), logs.list.get(0).getLoggerName());
    assertCounts(0, 0, 0, 0, 1);
    verify(queue, never()).updateMessageWithResponse(anyString(), anyString(), anyString(),
        any(Duration.class), isNull(), eq(Context.NONE));
    verify(queue, never()).deleteMessage(anyString(), anyString());
    verifyNoInteractions(deadLetters);
  }

  @ParameterizedTest
  @ValueSource(strings = {"uri", "key", "storage"})
  void checkedFailureIsDiagnosedWhereRetryAbsorbsItWithoutGpdAttribution(String type)
      throws Exception {
    Exception original = switch (type) {
      case "uri" -> new URISyntaxException("receipt", "invalid URI");
      case "key" -> new InvalidKeyException("invalid key");
      default -> new StorageException("InternalError", "storage failed", 500, null, null);
    };
    receive(message("retry", 2, XML));
    when(partner.getReceiptPaymentOptionScheduler(
        anyString(), anyString(), anyString(), anyBoolean(), any(), any()))
        .thenAnswer(invocation -> { throw original; });

    service.retryFailedPaSendRT(counts);

    assertCodes("OPERATION_TECHNICAL_FAILED", "RECEIPT_RETRY_SCHEDULED");
    assertTechnical(0, original);
    assertEquals(Level.INFO, logs.list.get(1).getLevel());
    assertFalse(details(1).has("dependency"));
    assertFalse(fields(1).containsKey("error.type"));
    verify(queue).updateMessageWithResponse(
        "retry", POP_RECEIPT, XML, Duration.ofSeconds(240), null, Context.NONE);
    verify(queue, never()).deleteMessage(anyString(), anyString());
    assertCounts(0, 1, 0, 0, 0);
  }

  @Test
  void visibilityUpdateFailureHasOneQueueDiagnosticAndNoRetrySuccess() throws Exception {
    receive(message("retry", 2, XML));
    partnerFails(feignFailure());
    IllegalStateException original = new IllegalStateException("visibility update failed");
    when(queue.updateMessageWithResponse(anyString(), anyString(), anyString(),
        any(Duration.class), isNull(), eq(Context.NONE))).thenThrow(original);

    assertSame(original, assertThrows(IllegalStateException.class,
        () -> service.retryFailedPaSendRT(counts)));

    assertDependency(0, "azure-queue-storage", "rescheduleReceiptRetry", original);
    assertCodes("DEPENDENCY_CALL_FAILED");
    assertCounts(0, 0, 0, 0, 1);
    verify(queue, never()).deleteMessage(anyString(), anyString());
  }

  @Test
  void deleteFailureDoesNotClaimReceiptProcessingSuccess() throws Exception {
    receive(message("delete-failed", 1, XML));
    IllegalStateException original = new IllegalStateException("source delete failed");
    doThrow(original).when(queue).deleteMessage("delete-failed", POP_RECEIPT);

    assertSame(original, assertThrows(IllegalStateException.class,
        () -> service.retryFailedPaSendRT(counts)));

    assertCodes("DEPENDENCY_CALL_FAILED");
    assertDependency(0, "azure-queue-storage", "deleteReceiptRetry", original);
    assertEquals("delete-failed", details(0).get("messageId").asText());
    assertFalse(details(0).has("reason"));
    assertCounts(0, 0, 0, 0, 1);
  }

  @ParameterizedTest
  @EnumSource(value = PaaErrorEnum.class,
      names = {"PAA_RECEIPT_DUPLICATA", "PAA_PAGAMENTO_SCONOSCIUTO"})
  void businessRejectionIsDeletedAndDiscardedAtInfoWithControlledFaultCode(
      PaaErrorEnum error) throws Exception {
    receive(message("discarded", 1, XML));
    partnerFails(new PartnerValidationException(error));
    doAnswer(invocation -> {
      assertTrue(logs.list.isEmpty());
      return null;
    }).when(queue).deleteMessage("discarded", POP_RECEIPT);

    service.retryFailedPaSendRT(counts);

    verify(queue).deleteMessage("discarded", POP_RECEIPT);
    assertCodes("RECEIPT_PROCESSING_COMPLETED");
    assertEquals(Level.INFO, logs.list.get(0).getLevel());
    assertEquals("BUSINESS_REJECTION", details(0).get("reason").asText());
    assertEquals(error.getFaultCode(), fields(0).get("faultCode"));
    assertFalse(fields(0).containsKey("error.type"));
    assertFalse(fields(0).containsKey("error.message"));
    assertCounts(0, 0, 1, 0, 0);
    assertNull(MDC.get("faultCode"));
  }

  @Test
  void rejectedReceiptDeleteFailureIsFailedNotDiscarded() throws Exception {
    receive(message("discard-delete-failed", 1, XML));
    partnerFails(new PartnerValidationException(PaaErrorEnum.PAA_RECEIPT_DUPLICATA));
    IllegalStateException original = new IllegalStateException("discard delete failed");
    doThrow(original).when(queue).deleteMessage("discard-delete-failed", POP_RECEIPT);

    assertSame(original, assertThrows(IllegalStateException.class,
        () -> service.retryFailedPaSendRT(counts)));

    assertCodes("DEPENDENCY_CALL_FAILED");
    assertDependency(0, "azure-queue-storage", "deleteReceiptRetry", original);
    assertFalse(fields(0).containsKey("faultCode"));
    assertCounts(0, 0, 0, 0, 1);
  }

  @Test
  void exhaustedReceiptIsPersistedBeforeDeleteAndWarnSuccess() throws Exception {
    receive(message("exhausted", 6, XML));
    Instant before = Instant.now();
    doAnswer(invocation -> {
      assertTrue(logs.list.isEmpty());
      return null;
    }).when(deadLetters).sendToDeadLetter(any());
    doAnswer(invocation -> {
      assertTrue(logs.list.isEmpty(), "Dead-letter success requires both actions");
      return null;
    }).when(queue).deleteMessage("exhausted", POP_RECEIPT);

    service.retryFailedPaSendRT(counts);

    var ordered = inOrder(deadLetters, queue);
    ArgumentCaptor<DeadLetterMessage> deadLetter = ArgumentCaptor.forClass(DeadLetterMessage.class);
    ordered.verify(deadLetters).sendToDeadLetter(deadLetter.capture());
    ordered.verify(queue).deleteMessage("exhausted", POP_RECEIPT);
    assertEquals(XML, deadLetter.getValue().getOriginalMessage());
    assertEquals("exhausted", deadLetter.getValue().getMessageId());
    assertEquals(6L, deadLetter.getValue().getDequeueCount());
    assertEquals(DeadLetterReason.MAX_RETRY_ATTEMPTS_REACHED, deadLetter.getValue().getReason());
    assertFalse(deadLetter.getValue().getDeadLetteredAt().isBefore(before));
    assertFalse(deadLetter.getValue().getDeadLetteredAt().isAfter(Instant.now()));
    assertCodes("RECEIPT_DEAD_LETTERED");
    assertEquals(Level.WARN, logs.list.get(0).getLevel());
    assertEquals(json.readTree("""
        {"messageId":"exhausted","attempt":6,"reason":"RETRY_LIMIT_EXCEEDED"}
        """), details(0));
    assertCounts(0, 0, 0, 1, 0);
    verifyNoInteractions(partner);
  }

  @Test
  void persistedDeadLetterDeleteFailureIsSwallowedFailedAndNextMessageContinues()
      throws Exception {
    receive(message("partial", 6, XML), message("next", 1, XML));
    IllegalStateException original = new IllegalStateException("dead-letter source delete failed");
    doThrow(original).when(queue).deleteMessage("partial", POP_RECEIPT);

    assertDoesNotThrow(() -> service.retryFailedPaSendRT(counts));

    var ordered = inOrder(deadLetters, queue, partner);
    ordered.verify(deadLetters).sendToDeadLetter(any());
    ordered.verify(queue).deleteMessage("partial", POP_RECEIPT);
    ordered.verify(partner).getReceiptPaymentOptionScheduler(
        anyString(), anyString(), anyString(), anyBoolean(), any(), any());
    ordered.verify(queue).deleteMessage("next", POP_RECEIPT);
    assertCodes("DEPENDENCY_CALL_FAILED", "RECEIPT_PROCESSING_COMPLETED");
    assertDependency(0, "azure-queue-storage", "deleteReceiptRetry", original);
    assertEquals("DEAD_LETTER_PERSISTED_SOURCE_DELETE_FAILED",
        details(0).get("reason").asText());
    assertEquals("partial", details(0).get("messageId").asText());
    assertEquals("next", details(1).get("messageId").asText());
    assertNotEquals(fields(0).get("operationId"), fields(1).get("operationId"));
    assertFalse(fields(1).containsKey("error.message"));
    assertCounts(1, 0, 0, 0, 1);
  }

  @Test
  void actualBlobAdapterPersistenceFailureKeepsSourceAndIsDiagnosedOnceWhileBatchContinues()
      throws Exception {
    BlobContainerClient container = mock(BlobContainerClient.class);
    BlobClient blob = mock(BlobClient.class);
    HttpResponse response = mock(HttpResponse.class);
    when(response.getStatusCode()).thenReturn(503);
    BlobStorageException original = new BlobStorageException("blob upload failed", response, null);
    when(container.getBlobClient(anyString())).thenReturn(blob);
    doThrow(original).when(blob).upload(any(InputStream.class), anyLong(), eq(true));
    DeadLetterService actualDeadLetters =
        new DeadLetterServiceImpl(new BlobStorageClientImpl(container), json);
    service = service(actualDeadLetters);
    receive(message("blob-failed", 6, XML), message("next", 1, XML));

    assertDoesNotThrow(() -> service.retryFailedPaSendRT(counts));

    verify(blob).upload(any(InputStream.class), anyLong(), eq(true));
    verify(queue, never()).deleteMessage("blob-failed", POP_RECEIPT);
    verify(queue).deleteMessage("next", POP_RECEIPT);
    assertCodes("DEPENDENCY_CALL_FAILED", "RECEIPT_PROCESSING_COMPLETED");
    assertDependency(0, "azure-blob-storage", "persistDeadLetter", original);
    assertEquals(503, details(0).get("dependencyHttpCode").asInt());
    assertEquals(SchedulerService.class.getName(), logs.list.get(0).getLoggerName());
    assertEquals("blob-failed", fields(0).get("messageId"));
    assertCounts(1, 0, 0, 0, 1);
  }

  @Test
  void undiagnosedDeadLetterPersistenceFailureIsTechnicalAndSwallowed() {
    receive(message("persist-failed", 6, XML));
    IllegalStateException original = new IllegalStateException("dead-letter persistence failed");
    doThrow(original).when(deadLetters).sendToDeadLetter(any());

    assertDoesNotThrow(() -> service.retryFailedPaSendRT(counts));

    assertCodes("OPERATION_TECHNICAL_FAILED");
    assertTechnical(0, original);
    assertCounts(0, 0, 0, 0, 1);
    verify(queue, never()).deleteMessage(anyString(), anyString());
  }

  @Test
  void malformedXmlLogsOriginalSaxAndDistinctConversionFailureAndAbortsWithPartialCounts()
      throws Exception {
    QueueMessageItem unattempted = message("unattempted", 1, XML);
    receive(message("processed", 1, XML), message("malformed", 1, "<receipt>"), unattempted);
    NullPointerException converted;
    try (OperationLogScope outer = new ContextSnapshot(
        "outer-request", null, "job-run", null, null, null).openOperation("job")) {
      Map<String, String> before = MDC.getCopyOfContextMap();
      converted = assertThrows(NullPointerException.class,
          () -> service.retryFailedPaSendRT(counts));
      assertEquals(before, MDC.getCopyOfContextMap());
      assertSame(outer, OperationLogScope.current());
    }

    assertEquals(SAXParseException.class.getName(), fields(1).get("error.type"));
    assertEquals("XML document structures must start and end within the same entity.",
        fields(1).get("error.message"));
    assertTrue(fields(1).get("error.stack_trace").contains(fields(1).get("error.message")));
    assertEquals("malformed", fields(1).get("messageId"));
    assertEquals("job-run", fields(1).get("jobRunId"));
    assertFalse(fields(1).containsKey("noticeNumber"));
    assertTechnical(2, converted);
    assertEquals(fields(1).get("operationId"), fields(2).get("operationId"));
    assertEquals("malformed", fields(2).get("messageId"));
    assertCounts(1, 0, 0, 0, 1);
    verify(partner, times(1)).getReceiptPaymentOptionScheduler(
        anyString(), anyString(), anyString(), anyBoolean(), any(), any());
    verifyNoInteractions(unattempted);
    verify(queue, times(1)).deleteMessage(anyString(), anyString());
    assertAll(
        () -> assertNull(converted.getCause(), "Preserve the cause-less conversion contract"),
        () -> assertCodes("RECEIPT_PROCESSING_COMPLETED",
            "OPERATION_TECHNICAL_FAILED", "OPERATION_TECHNICAL_FAILED"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void absorbedTechnicalFailureCountsAsFailedEvenWhenSourceDeletionCompletes(boolean rejected)
      throws Exception {
    receive(message("absorbed", 1, XML));
    IllegalStateException original = new IllegalStateException("absorbed reconciliation failure");
    when(partner.getReceiptPaymentOptionScheduler(
        anyString(), anyString(), anyString(), anyBoolean(), any(), any()))
        .thenAnswer(invocation -> {
          FailureDiagnostics.technicalFailure(
              new LogEventWriter(LoggerFactory.getLogger(SchedulerService.class)), original);
          if (rejected) {
            throw new PartnerValidationException(PaaErrorEnum.PAA_RECEIPT_DUPLICATA);
          }
          return null;
        });

    service.retryFailedPaSendRT(counts);

    verify(queue).deleteMessage("absorbed", POP_RECEIPT);
    assertCodes("OPERATION_TECHNICAL_FAILED", "RECEIPT_PROCESSING_COMPLETED");
    assertTechnical(0, original);
    assertCounts(0, 0, 0, 0, 1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing-node", "invalid-date", "invalid-number"})
  void missingNodesDateAndNumberFailuresAreTechnicalAndAbort(String scenario) {
    String broken = switch (scenario) {
      case "missing-node" -> XML.replace("<receiptId>receipt-id</receiptId>", "");
      case "invalid-date" -> XML.replace("2026-01-01T12:00:00", "not-a-date");
      default -> XML.replace("<fee>1.25</fee>", "<fee>not-a-number</fee>");
    };
    Class<? extends RuntimeException> expected = switch (scenario) {
      case "missing-node" -> NullPointerException.class;
      case "invalid-date" -> DateTimeParseException.class;
      default -> NumberFormatException.class;
    };
    QueueMessageItem unattempted = message("unattempted", 1, XML);
    receive(message("invalid", 1, broken), unattempted);

    RuntimeException original = assertThrows(expected, () -> service.retryFailedPaSendRT(counts));

    assertCodes("OPERATION_TECHNICAL_FAILED");
    assertTechnical(0, original);
    assertCounts(0, 0, 0, 0, 1);
    verifyNoInteractions(unattempted);
    verify(queue, never()).deleteMessage(anyString(), anyString());
    assertNull(OperationLogScope.currentOrNull());
  }

  @Test
  void xpathFailureIsDiagnosedWithOriginalMessageSwallowedAndNextMessageProcessed()
      throws Exception {
    XPath realXpath = XPathFactory.newInstance().newXPath();
    XPathFactory factory = mock(XPathFactory.class);
    XPath xpath = mock(XPath.class);
    XPathExpressionException original = new XPathExpressionException("XPath evaluation failed");
    when(factory.newXPath()).thenReturn(xpath);
    when(xpath.compile("/receipt")).thenThrow(original).thenReturn(realXpath.compile("/receipt"));
    receive(message("xpath-failed", 1, XML), message("next", 1, XML));

    try (var factories = mockStatic(XPathFactory.class)) {
      factories.when(XPathFactory::newInstance).thenReturn(factory);
      assertDoesNotThrow(() -> service.retryFailedPaSendRT(counts));
    }

    assertCodes("OPERATION_TECHNICAL_FAILED", "RECEIPT_PROCESSING_COMPLETED");
    assertTechnical(0, original);
    assertEquals("xpath-failed", fields(0).get("messageId"));
    assertEquals("next", fields(1).get("messageId"));
    assertFalse(fields(1).containsKey("error.type"));
    assertCounts(1, 0, 0, 0, 1);
    verify(queue, never()).deleteMessage("xpath-failed", POP_RECEIPT);
    verify(queue).deleteMessage("next", POP_RECEIPT);
  }

  @Test
  void everyMessageGetsFreshScopeAndRequestOperationIdsButSameJobRunAndRestoresOuterMdc()
      throws Exception {
    receive(message("first", 1, XML), message("second", 1, XML));
    List<Map<String, String>> contexts = new ArrayList<>();
    List<OperationLogScope> scopes = new ArrayList<>();
    when(partner.getReceiptPaymentOptionScheduler(
        anyString(), anyString(), anyString(), anyBoolean(), any(), any()))
        .thenAnswer(invocation -> {
          scopes.add(OperationLogScope.current());
          contexts.add(MDC.getCopyOfContextMap());
          assertFalse(OperationLogScope.current().hasDiagnosis());
          assertNull(OperationLogScope.current().summaryArgs());
          OperationLogScope.current().summaryArgs("must-not-leak");
          return null;
        });
    MDC.put("outside", "preserved");
    Map<String, String> outside = MDC.getCopyOfContextMap();
    try (OperationLogScope outer = new ContextSnapshot("outer-request", null, "job-run",
        "outer-message", "outer-notice", "outer-creditor").openOperation("job")) {
      outer.summaryArgs("outer-args");
      MDC.put("httpCode", "500");
      MDC.put("args", "outer-args");
      MDC.put("faultCode", "outer-fault");
      MDC.put("error.message", "outer-error");
      MDC.put("ctx_details", "outer-details");
      Map<String, String> before = MDC.getCopyOfContextMap();

      service.retryFailedPaSendRT(counts);

      assertEquals(before, MDC.getCopyOfContextMap());
      assertSame(outer, OperationLogScope.current());
      assertEquals("outer-args", outer.summaryArgs());
      assertNotSame(outer, scopes.get(0));
      assertNotSame(outer, scopes.get(1));
      for (String key : List.of("requestId", "operationId")) {
        assertNotEquals(before.get(key), contexts.get(0).get(key));
        assertNotEquals(contexts.get(0).get(key), contexts.get(1).get(key));
        for (Map<String, String> context : contexts) {
          assertEquals(context.get(key), UUID.fromString(context.get(key)).toString());
        }
      }
    }
    assertEquals(outside, MDC.getCopyOfContextMap());
    assertNotSame(scopes.get(0), scopes.get(1));
    for (int i = 0; i < 2; i++) {
      assertEquals("job-run", contexts.get(i).get("jobRunId"));
      assertEquals(i == 0 ? "first" : "second", contexts.get(i).get("messageId"));
      assertEquals("retryReceipt", contexts.get(i).get("method"));
      assertEquals(FISCAL_CODE, contexts.get(i).get("creditorFiscalCode"));
      assertEquals(NOTICE, contexts.get(i).get("noticeNumber"));
      assertFalse(contexts.get(i).containsKey("httpCode"));
      assertFalse(contexts.get(i).containsKey("args"));
      assertFalse(fields(i).containsKey("faultCode"));
      assertFalse(fields(i).containsKey("error.message"));
    }
    assertCodes("RECEIPT_PROCESSING_COMPLETED", "RECEIPT_PROCESSING_COMPLETED");
    assertCounts(2, 0, 0, 0, 0);
  }

  @Test
  void sameFailureInstanceIsDiagnosedIndependentlyForEachMessageAndNeverDoubleCounted()
      throws Exception {
    receive(message("first-failed", 6, XML), message("second-failed", 6, XML),
        message("processed", 1, XML));
    IllegalStateException original = new IllegalStateException("shared source delete failure");
    doThrow(original).when(queue).deleteMessage("first-failed", POP_RECEIPT);
    doThrow(original).when(queue).deleteMessage("second-failed", POP_RECEIPT);

    assertDoesNotThrow(() -> service.retryFailedPaSendRT(counts));

    assertCodes("DEPENDENCY_CALL_FAILED", "DEPENDENCY_CALL_FAILED", "RECEIPT_PROCESSING_COMPLETED");
    assertDependency(0, "azure-queue-storage", "deleteReceiptRetry", original);
    assertDependency(1, "azure-queue-storage", "deleteReceiptRetry", original);
    assertEquals("first-failed", fields(0).get("messageId"));
    assertEquals("second-failed", fields(1).get("messageId"));
    assertEquals(fields(0).get("jobRunId"), fields(1).get("jobRunId"));
    assertNotEquals(fields(0).get("requestId"), fields(1).get("requestId"));
    assertNotEquals(fields(0).get("operationId"), fields(1).get("operationId"));
    verify(deadLetters, times(2)).sendToDeadLetter(any());
    verify(queue).deleteMessage("processed", POP_RECEIPT);
    assertCounts(1, 0, 0, 0, 2);
  }

  @Test
  void standaloneVoidEntryPointPreservesApiAndCreatesNewJobIdForEachInvocation() {
    receive(message("standalone", 1, XML));

    service.retryFailedPaSendRT();
    service.retryFailedPaSendRT();

    assertCodes("RECEIPT_PROCESSING_COMPLETED", "RECEIPT_PROCESSING_COMPLETED");
    for (String key : List.of("jobRunId", "requestId", "operationId")) {
      assertNotEquals(fields(0).get(key), fields(1).get(key));
      assertNotNull(UUID.fromString(fields(0).get(key)));
      assertNotNull(UUID.fromString(fields(1).get(key)));
    }
    assertNull(OperationLogScope.currentOrNull());
    assertTrue(MDC.getCopyOfContextMap() == null || MDC.getCopyOfContextMap().isEmpty());
  }

  @Test
  void receiveFailureIsQueueDiagnosticRethrownWithoutCountingUnreceivedMessages() throws Exception {
    IllegalStateException original = new IllegalStateException("queue receive failed");
    when(queue.receiveMessages(10, Duration.ofSeconds(30), null, Context.NONE))
        .thenThrow(original);
    try (OperationLogScope outer = OperationLogScope.open("job-request", "job")) {
      Map<String, String> before = MDC.getCopyOfContextMap();

      assertSame(original, assertThrows(IllegalStateException.class,
          () -> service.retryFailedPaSendRT(counts)));

      assertEquals(before, MDC.getCopyOfContextMap());
      assertSame(outer, OperationLogScope.current());
    }
    assertCodes("DEPENDENCY_CALL_FAILED");
    assertDependency(0, "azure-queue-storage", "receiveReceiptRetries", original);
    assertCounts(0, 0, 0, 0, 0);
    verifyNoInteractions(partner, deadLetters);
  }

  @Test
  void emptyBatchHasNoMessageEventsAndDoesNotChangeCountersOrMdc() {
    receive();
    MDC.put("outside", "preserved");
    Map<String, String> before = MDC.getCopyOfContextMap();

    service.retryFailedPaSendRT(counts);

    assertEquals(before, MDC.getCopyOfContextMap());
    assertCodes();
    assertCounts(0, 0, 0, 0, 0);
    verifyNoInteractions(partner, deadLetters);
  }

  private SchedulerService service(DeadLetterService deadLetterService) {
    return new SchedulerService(5, 30L, 120L, 3600L, 2.0, queue, partner, deadLetterService);
  }

  private GpdClient actualPartner() {
    GpdClient gpd = mock(GpdClient.class);
    partner = new PartnerService(30L, List.of(), List.of(), new ObjectFactory(), gpd,
        mock(TableClient.class), queue, null, mock(VerticalServicesConfig.class),
        mock(RestTemplate.class));
    service = service(deadLetters);
    return gpd;
  }

  @SuppressWarnings("unchecked")
  private void receive(QueueMessageItem... messages) {
    PagedIterable<QueueMessageItem> page = mock(PagedIterable.class);
    when(page.stream()).thenAnswer(invocation -> List.of(messages).stream());
    when(queue.receiveMessages(10, Duration.ofSeconds(30), null, Context.NONE)).thenReturn(page);
  }

  private QueueMessageItem message(String id, long dequeueCount, String xml) {
    QueueMessageItem message = mock(QueueMessageItem.class);
    when(message.getMessageId()).thenReturn(id);
    when(message.getPopReceipt()).thenReturn(POP_RECEIPT);
    when(message.getDequeueCount()).thenReturn(dequeueCount);
    when(message.getBody()).thenReturn(BinaryData.fromString(xml));
    return message;
  }

  private void partnerFails(RuntimeException original) throws Exception {
    when(partner.getReceiptPaymentOptionScheduler(
        anyString(), anyString(), anyString(), anyBoolean(), any(), any())).thenThrow(original);
  }

  private FeignException feignFailure() {
    return feignFailure(503);
  }

  private FeignException feignFailure(int status) {
    Request request = Request.create(Request.HttpMethod.POST, "http://localhost/receipt",
        Map.of(), null, StandardCharsets.UTF_8, null);
    return FeignException.errorStatus("sendPaymentOptionReceipt", Response.builder()
        .request(request).status(status).reason("partner unavailable").headers(Map.of()).build());
  }

  private Map<String, String> fields(int index) {
    return logs.list.get(index).getMDCPropertyMap();
  }

  private JsonNode details(int index) throws Exception {
    return json.readTree(fields(index).get("ctx_details"));
  }

  private void assertCodes(String... expected) {
    assertEquals(List.of(expected), logs.list.stream()
        .map(event -> event.getMDCPropertyMap().get("event.code")).toList());
  }

  private void assertTechnical(int index, Throwable original) {
    assertEquals(Level.ERROR, logs.list.get(index).getLevel());
    assertEquals(original.getClass().getName(), fields(index).get("error.type"));
    assertEquals(original.getMessage(), fields(index).get("error.message"));
    assertTrue(fields(index).get("error.stack_trace").contains(original.getClass().getName()));
    assertFalse(fields(index).containsKey("ctx_details"));
  }

  private void assertDependency(int index, String dependency, String operation, Throwable original)
      throws Exception {
    assertEquals(Level.ERROR, logs.list.get(index).getLevel());
    assertEquals(original.getClass().getName(), fields(index).get("error.type"));
    assertEquals(original.getMessage(), fields(index).get("error.message"));
    assertTrue(fields(index).get("error.stack_trace").contains(original.getMessage()));
    JsonNode details = details(index);
    assertEquals(dependency, details.get("dependency").asText());
    assertEquals(operation, details.get("dependencyOperation").asText());
    assertEquals("KO", details.get("dependencyOutcome").asText());
    assertTrue(details.get("durationMs").isIntegralNumber());
    assertTrue(details.get("durationMs").longValue() >= 0);
  }

  private void assertCounts(long processed, long retried, long discarded, long deadLettered,
      long failed) {
    assertEquals(new EventDetails.JobSummary(processed, retried, discarded, deadLettered, failed),
        counts.summary());
  }
}
