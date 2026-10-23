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
import com.azure.core.util.BinaryData;
import com.azure.core.util.Context;
import com.azure.storage.queue.QueueClient;
import com.azure.storage.queue.models.QueueMessageItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import feign.Request;
import it.gov.pagopa.payments.endpoints.validation.exceptions.PartnerValidationException;
import it.gov.pagopa.payments.entity.ReceiptEntity;
import it.gov.pagopa.payments.exception.AppException;
import it.gov.pagopa.payments.logging.BackgroundExecutionLogging;
import it.gov.pagopa.payments.logging.StructuredEcsEncoder;
import it.gov.pagopa.payments.model.DeadLetterMessage;
import it.gov.pagopa.payments.model.PaaErrorEnum;
import it.gov.pagopa.payments.model.PaymentOptionModel;
import it.gov.pagopa.payments.model.enumeration.DeadLetterReason;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.xml.sax.SAXException;

class SchedulerLoggingTest {
  private static final String PAYLOAD_SECRET = "PAYLOAD_SECRET_SENTINEL";
  private static final String RECEIPT_SECRET = "POP_RECEIPT_SECRET_SENTINEL";
  private static final String FAILURE_SECRET = "EXCEPTION_SECRET_SENTINEL";
  private static final Map<String, String> OUTER = Map.of(
      "correlation_id", "outer-correlation", "method", "outer-method", "status", "outer-status",
      "ctx_creditor_id", "outer-creditor", "ctx_notice_number", "outer-nav", "ctx_iuv", "outer-iuv");

  private final QueueClient queue = mock(QueueClient.class);
  private final PartnerService partner = mock(PartnerService.class);
  private final DeadLetterService deadLetters = mock(DeadLetterService.class);
  private final SchedulerService scheduler =
      new SchedulerService(5, 31L, 7L, 20L, 2.0, queue, partner, deadLetters);
  private final List<JsonNode> records = new ArrayList<>();
  private final List<ILoggingEvent> events = new ArrayList<>();
  private final List<Logger> loggers = List.of(
      (Logger) LoggerFactory.getLogger(SchedulerService.class),
      (Logger) LoggerFactory.getLogger(BackgroundExecutionLogging.class));
  private final List<Level> previousLevels = new ArrayList<>();
  private final StructuredEcsEncoder encoder = new StructuredEcsEncoder();
  private final ObjectMapper json = new ObjectMapper();
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

  @BeforeEach
  void startCapture() {
    previousMdc = MDC.getCopyOfContextMap();
    MDC.setContextMap(OUTER);
    encoder.setContext(loggers.get(0).getLoggerContext());
    encoder.start();
    capture.setContext(loggers.get(0).getLoggerContext());
    capture.start();
    for (Logger logger : loggers) {
      previousLevels.add(logger.getLevel());
      logger.setLevel(Level.INFO);
      logger.addAppender(capture);
    }
  }

  @AfterEach
  void restoreCapture() {
    for (int index = 0; index < loggers.size(); index++) {
      loggers.get(index).detachAppender(capture);
      loggers.get(index).setLevel(previousLevels.get(index));
    }
    capture.stop();
    encoder.stop();
    if (previousMdc == null) {
      MDC.clear();
    } else {
      MDC.setContextMap(previousMdc);
    }
  }

  @Test
  void rescheduledMessageIsKoAndNextSuccessfulPaymentHasIndependentContext() throws Exception {
    QueueMessageItem failed = message("failed", 3, xml("creditor-one", "nav-one", "iuv-one"));
    QueueMessageItem successful = message("successful", 1, xml("creditor-two", "nav-two", "iuv-two"));
    receive(failed, successful);
    List<Map<String, String>> paymentContexts = new ArrayList<>();
    when(partner.getFeeInCent(any())).thenCallRealMethod();
    when(partner.getReceiptPaymentOptionScheduler(
        anyString(), anyString(), anyString(), anyBoolean(),
        any(PaymentOptionModel.class), any(ReceiptEntity.class))).thenAnswer(invocation -> {
          paymentContexts.add(MDC.getCopyOfContextMap());
          ReceiptEntity receipt = invocation.getArgument(5);
          PaymentOptionModel payment = invocation.getArgument(4);
          assertEquals("123", payment.getFee());
          if ("iuv-one".equals(receipt.getIuv())) {
            throw transportFailure();
          }
          return null;
        });

    scheduler.retryFailedPaSendRT();

    var order = inOrder(partner, queue);
    order.verify(partner).getReceiptPaymentOptionScheduler(
        eq("nav-one"), eq("creditor-one"), eq("iuv-one"), eq(false), any(), any());
    order.verify(queue).updateMessageWithResponse(
        failed.getMessageId(), failed.getPopReceipt(), failed.getBody().toString(),
        Duration.ofSeconds(20), null, Context.NONE);
    order.verify(partner).getReceiptPaymentOptionScheduler(
        eq("nav-two"), eq("creditor-two"), eq("iuv-two"), eq(false), any(), any());
    order.verify(queue).deleteMessage(successful.getMessageId(), successful.getPopReceipt());
    verify(queue, never()).deleteMessage(eq(failed.getMessageId()), anyString());
    verifyNoInteractions(deadLetters);
    assertEquals(2, paymentContexts.size());
    assertEquals("creditor-one", paymentContexts.get(0).get("ctx_creditor_id"));
    assertEquals("creditor-two", paymentContexts.get(1).get("ctx_creditor_id"));
    List<JsonNode> summaries = summaries();
    assertEquals(2, summaries.size());
    assertSummary(summaries.get(0), "KO", "creditor-one", "nav-one", "iuv-one");
    assertSummary(summaries.get(1), "OK", "creditor-two", "nav-two", "iuv-two");
    assertNotEquals(summaries.get(0).path("correlation_id"), summaries.get(1).path("correlation_id"));
    assertEquals(1, named("Receipt retry failed").size());
    assertEquals("ERROR", named("Receipt retry failed").get(0).path("log.level").asText());
    assertTrue(named("Receipt retry failed").get(0).path("error.stack_trace").asText()
        .contains(FeignException.InternalServerError.class.getName()));
    assertEquals(1, named("Receipt retry scheduled").size());
    assertEquals(OUTER, MDC.getCopyOfContextMap());
    assertSafeEvents();
  }

  @Test
  void thresholdMessageIsPaidAndOnlyExceededMessageMovesAfterDurablePersistence() throws Exception {
    QueueMessageItem atLimit = message("at-limit", 5, xml("creditor", "nav", "iuv"));
    QueueMessageItem exceeded = message("exceeded", 6, PAYLOAD_SECRET);
    receive(atLimit, exceeded);
    List<Map<String, String>> deadLetterContexts = new ArrayList<>();
    doAnswer(invocation -> {
      deadLetterContexts.add(MDC.getCopyOfContextMap());
      return null;
    }).when(deadLetters).sendToDeadLetter(any());

    scheduler.retryFailedPaSendRT();

    var order = inOrder(partner, queue, deadLetters);
    order.verify(partner).getReceiptPaymentOptionScheduler(
        eq("nav"), eq("creditor"), eq("iuv"), eq(false), any(), any());
    order.verify(queue).deleteMessage(atLimit.getMessageId(), atLimit.getPopReceipt());
    ArgumentCaptor<DeadLetterMessage> persisted = ArgumentCaptor.forClass(DeadLetterMessage.class);
    order.verify(deadLetters).sendToDeadLetter(persisted.capture());
    order.verify(queue).deleteMessage(exceeded.getMessageId(), exceeded.getPopReceipt());
    assertEquals(exceeded.getMessageId(), persisted.getValue().getMessageId());
    assertEquals(6L, persisted.getValue().getDequeueCount().longValue());
    assertEquals(DeadLetterReason.MAX_RETRY_ATTEMPTS_REACHED, persisted.getValue().getReason());
    assertEquals(PAYLOAD_SECRET, persisted.getValue().getOriginalMessage());
    assertNotNull(persisted.getValue().getDeadLetteredAt());
    assertFalse(deadLetterContexts.get(0).containsKey("ctx_creditor_id"));
    assertFalse(deadLetterContexts.get(0).containsKey("ctx_notice_number"));
    assertFalse(deadLetterContexts.get(0).containsKey("ctx_iuv"));
    assertEquals(2, summaries().size());
    assertSummary(summaries().get(0), "OK", "creditor", "nav", "iuv");
    assertSummary(summaries().get(1), "OK", null, null, null);
    assertNotEquals(summaries().get(0).path("correlation_id"), summaries().get(1).path("correlation_id"));
    assertEquals(1, named("Maximum retry attempts reached: message moved to dead-letter storage").size());
    verify(queue, never()).updateMessageWithResponse(
        anyString(), anyString(), anyString(), any(), any(), any());
    assertEquals(OUTER, MDC.getCopyOfContextMap());
    assertSafeEvents();
  }

  @Test
  void swallowedDeadLetterFailureLeavesQueueMessageAndMarksOnlyThatMessageKo() {
    QueueMessageItem exceeded = message("exceeded", 6, PAYLOAD_SECRET);
    QueueMessageItem successful = message("next", 1, xml("next-creditor", "next-nav", "next-iuv"));
    receive(exceeded, successful);
    doThrow(new IllegalStateException(FAILURE_SECRET))
        .when(deadLetters).sendToDeadLetter(any());

    assertDoesNotThrow(scheduler::retryFailedPaSendRT);

    verify(queue, never()).deleteMessage(eq(exceeded.getMessageId()), anyString());
    verify(queue).deleteMessage(successful.getMessageId(), successful.getPopReceipt());
    assertEquals(2, summaries().size());
    assertSummary(summaries().get(0), "KO", null, null, null);
    assertSummary(summaries().get(1), "OK", "next-creditor", "next-nav", "next-iuv");
    assertNotEquals(summaries().get(0).path("correlation_id"), summaries().get(1).path("correlation_id"));
    assertEquals(1, named("Dead-letter persistence failed; message retained in retry queue").size());
    assertEquals(1, records.stream().filter(logEntry ->
        "ERROR".equals(logEntry.path("log.level").asText())).count());
    assertEquals(OUTER, MDC.getCopyOfContextMap());
    assertSafeEvents();
  }

  @Test
  void malformedXmlPropagatesAppExceptionWithParserCauseWithoutQueueMutation() {
    receive(message("malformed", 1, "<receipt>" + PAYLOAD_SECRET),
        message("unprocessed-next", 1, xml("next-creditor", "next-nav", "next-iuv")));

    AppException failure = assertThrows(AppException.class, scheduler::retryFailedPaSendRT);

    assertInstanceOf(SAXException.class, failure.getCause());
    assertEquals(1, summaries().size());
    assertSummary(summaries().get(0), "KO", null, null, null);
    verifyNoInteractions(partner, deadLetters);
    verify(queue, never()).deleteMessage(anyString(), anyString());
    verify(queue, never()).updateMessageWithResponse(
        anyString(), anyString(), anyString(), any(), any(), any());
    assertEquals(OUTER, MDC.getCopyOfContextMap());
    assertSafeEvents();
  }

  @Test
  void expectedPartnerRejectionKeepsExistingDeleteSemanticsWithoutTechnicalDiagnosis() throws Exception {
    QueueMessageItem duplicate = message("duplicate", 1, xml("creditor", "nav", "iuv"));
    receive(duplicate);
    when(partner.getReceiptPaymentOptionScheduler(
        anyString(), anyString(), anyString(), anyBoolean(), any(), any()))
        .thenThrow(new PartnerValidationException(PaaErrorEnum.PAA_RECEIPT_DUPLICATA));

    scheduler.retryFailedPaSendRT();

    verify(queue).deleteMessage(duplicate.getMessageId(), duplicate.getPopReceipt());
    assertEquals(1, summaries().size());
    assertSummary(summaries().get(0), "KO", "creditor", "nav", "iuv");
    assertTrue(records.stream().noneMatch(logEntry ->
        "ERROR".equals(logEntry.path("log.level").asText())));
    assertEquals(OUTER, MDC.getCopyOfContextMap());
    assertSafeEvents();
  }

  @Test
  void emptyPollDoesNotInventMessageCompletion() {
    receive();
    scheduler.retryFailedPaSendRT();
    assertTrue(records.isEmpty());
    verifyNoInteractions(partner, deadLetters);
    assertEquals(OUTER, MDC.getCopyOfContextMap());
  }

  private void receive(QueueMessageItem... messages) {
    PagedIterable<QueueMessageItem> page = new PagedIterable<>(() ->
        new PagedResponseBase<Void, QueueMessageItem>(
            null, 200, new HttpHeaders(), List.of(messages), null, null));
    when(queue.receiveMessages(10, Duration.ofSeconds(31), null, Context.NONE)).thenReturn(page);
  }

  private static QueueMessageItem message(String label, long count, String xml) {
    return new QueueMessageItem().setMessageId("QUEUE_MESSAGE_SECRET_" + label)
        .setPopReceipt(RECEIPT_SECRET + label).setDequeueCount(count)
        .setBody(BinaryData.fromString(xml));
  }

  private static String xml(String creditor, String nav, String iuv) {
    return """
        <receipt><fiscalCode>%s</fiscalCode><noticeNumber>%s</noticeNumber>
        <creditorReferenceId>%s</creditorReferenceId>
        <paymentDateTime>2026-10-01T10:15:30</paymentDateTime>
        <receiptId>RECEIPT_ID_SECRET</receiptId><PSPCompanyName>%s</PSPCompanyName>
        <paymentMethod>METHOD_SECRET</paymentMethod><fee>1.23</fee>
        <entityUniqueIdentifierValue>DEBTOR_SECRET</entityUniqueIdentifierValue></receipt>
        """.formatted(creditor, nav, iuv, PAYLOAD_SECRET);
  }

  private static FeignException transportFailure() {
    Request request = Request.create(Request.HttpMethod.POST,
        "https://example.test/URL_SECRET?credential=URL_SECRET", Map.of(), null,
        StandardCharsets.UTF_8, null);
    return new FeignException.InternalServerError(FAILURE_SECRET, request,
        PAYLOAD_SECRET.getBytes(StandardCharsets.UTF_8), Map.of());
  }

  private List<JsonNode> named(String message) {
    return records.stream().filter(logEntry -> message.equals(logEntry.path("message").asText())).toList();
  }

  private List<JsonNode> summaries() {
    return named("Background execution completed");
  }

  private void assertSummary(
      JsonNode summary, String status, String creditor, String nav, String iuv) {
    assertEquals("retryPaSendRT.message", summary.path("method").asText());
    assertEquals(status, summary.path("status").asText());
    assertEquals("INFO", summary.path("log.level").asText());
    assertTrue(summary.path("event.duration").isIntegralNumber());
    assertTrue(summary.path("event.duration").longValue() >= 0);
    assertNotEquals("outer-correlation", summary.path("correlation_id").asText());
    assertDoesNotThrow(() -> UUID.fromString(summary.path("correlation_id").asText()));
    for (var field : Map.of("ctx_creditor_id", creditor == null ? "" : creditor,
        "ctx_notice_number", nav == null ? "" : nav, "ctx_iuv", iuv == null ? "" : iuv).entrySet()) {
      if (field.getValue().isEmpty()) {
        assertFalse(summary.has(field.getKey()));
      } else {
        assertEquals(field.getValue(), summary.path(field.getKey()).asText());
      }
    }
  }

  private void assertSafeEvents() {
    assertEquals(events.size(), records.size());
    for (ILoggingEvent event : events) {
      assertNull(event.getThrowableProxy());
    }
    for (JsonNode logEntry : records) {
      assertTrue(List.of(
          "Background execution completed",
          "Receipt retry failed",
          "Receipt retry processing failed",
          "Receipt retry scheduled",
          "Maximum retry attempts reached: message moved to dead-letter storage",
          "Dead-letter persistence failed; message retained in retry queue")
          .contains(logEntry.path("message").asText()));
      com.fasterxml.jackson.databind.node.ObjectNode withoutExceptionMessage = logEntry.deepCopy();
      withoutExceptionMessage.remove("error.message");
      String encoded = withoutExceptionMessage.toString();
      for (String secret : List.of(PAYLOAD_SECRET, RECEIPT_SECRET, FAILURE_SECRET,
          "QUEUE_MESSAGE_SECRET", "RECEIPT_ID_SECRET", "METHOD_SECRET", "DEBTOR_SECRET",
          "URL_SECRET", "<receipt>")) {
        assertFalse(encoded.contains(secret), encoded);
      }
      assertFalse(logEntry.has("args"));
      assertFalse(logEntry.has("httpCode"));
      assertFalse(logEntry.has("httpMethod"));
      assertFalse(logEntry.has("soap_method"));
      assertFalse(logEntry.has("fault_code"));
      logEntry.fieldNames().forEachRemaining(name -> {
        assertFalse(name.startsWith("http."), name);
        assertFalse(name.startsWith("soap."), name);
      });
    }
  }
}
