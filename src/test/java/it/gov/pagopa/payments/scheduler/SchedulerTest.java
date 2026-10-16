package it.gov.pagopa.payments.scheduler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.azure.core.http.rest.PagedIterable;
import com.azure.core.util.Context;
import com.azure.core.util.BinaryData;
import com.azure.storage.queue.QueueClient;
import com.azure.storage.queue.models.QueueMessageItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import it.gov.pagopa.payments.endpoints.validation.exceptions.PartnerValidationException;
import it.gov.pagopa.payments.entity.ReceiptEntity;
import it.gov.pagopa.payments.logging.ContextSnapshot;
import it.gov.pagopa.payments.logging.OperationLogScope;
import it.gov.pagopa.payments.model.PaaErrorEnum;
import it.gov.pagopa.payments.model.PaymentOptionModel;
import it.gov.pagopa.payments.service.DeadLetterService;
import it.gov.pagopa.payments.service.PartnerService;
import it.gov.pagopa.payments.service.SchedulerService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

@ExtendWith(MockitoExtension.class)
class SchedulerTest {

    @Mock
    private SchedulerService schedulerService;

    @InjectMocks
    private Scheduler scheduler;

    private final Logger logger = (Logger) LoggerFactory.getLogger(Scheduler.class);
    private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(SchedulerService.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>() {
        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };
    private final QueueClient queue = mock(QueueClient.class);
    private final PartnerService partner = mock(PartnerService.class);
    private final DeadLetterService deadLetters = mock(DeadLetterService.class);
    private Level previousLevel;
    private Level previousServiceLevel;
    private Map<String, String> previousMdc;

    @BeforeEach
    void captureLogs() {
        previousMdc = MDC.getCopyOfContextMap();
        MDC.clear();
        previousLevel = logger.getLevel();
        previousServiceLevel = serviceLogger.getLevel();
        logger.setLevel(Level.DEBUG);
        serviceLogger.setLevel(Level.DEBUG);
        logs.start();
        logger.addAppender(logs);
        serviceLogger.addAppender(logs);
    }

    @AfterEach
    void restoreLogging() {
        logger.detachAppender(logs);
        serviceLogger.detachAppender(logs);
        logger.setLevel(previousLevel);
        serviceLogger.setLevel(previousServiceLevel);
        logs.stop();
        if (previousMdc == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(previousMdc);
        }
    }

    @Test
    void retryPaSendRTShouldDelegateToSchedulerService() {
        assertNull(scheduler.getThreadOfExecution());
        scheduler.retryPaSendRT();

        verify(schedulerService).retryFailedPaSendRT(any(SchedulerService.JobCounts.class));
        verifyNoMoreInteractions(schedulerService);
        assertSame(Thread.currentThread(), scheduler.getThreadOfExecution());
    }

    @Test
    void eachCronStartsFreshScopeAndIdsAndRestoresExactOuterContext() {
        List<Map<String, String>> contexts = new ArrayList<>();
        List<OperationLogScope> scopes = new ArrayList<>();
        doAnswer(invocation -> {
            contexts.add(MDC.getCopyOfContextMap());
            scopes.add(OperationLogScope.current());
            assertNull(OperationLogScope.current().summaryArgs());
            assertFalse(OperationLogScope.current().hasDiagnosis());
            return null;
        }).when(schedulerService).retryFailedPaSendRT(any(SchedulerService.JobCounts.class));

        MDC.put("outside", "preserved");
        Map<String, String> beforeOuter = MDC.getCopyOfContextMap();
        try (OperationLogScope outer = outerScope()) {
            outer.summaryArgs("outer arguments");
            outer.recordDiagnosis();
            Map<String, String> beforeCron = MDC.getCopyOfContextMap();
            for (int run = 0; run < 2; run++) {
                scheduler.retryPaSendRT();
                assertEquals(beforeCron, MDC.getCopyOfContextMap());
                assertSame(outer, OperationLogScope.current());
                assertEquals("outer arguments", outer.summaryArgs());
                assertTrue(outer.hasDiagnosis());
            }
            assertNotSame(outer, scopes.get(0));
            assertNotSame(outer, scopes.get(1));
            assertNotSame(scopes.get(0), scopes.get(1));
            for (String key : List.of("requestId", "operationId", "jobRunId")) {
                String first = contexts.get(0).get(key);
                String second = contexts.get(1).get(key);
                assertEquals(first, UUID.fromString(first).toString());
                assertEquals(second, UUID.fromString(second).toString());
                assertNotEquals(first, second);
                assertNotEquals(beforeCron.get(key), first);
                assertNotEquals(beforeCron.get(key), second);
            }
            for (int run = 0; run < 2; run++) {
                Map<String, String> context = contexts.get(run);
                assertEquals(4, context.size());
                assertEquals("retryPaSendRT", context.get("method"));
                Map<String, String> completed = completions().get(run).getMDCPropertyMap();
                context.forEach((key, value) -> assertEquals(value, completed.get(key)));
                assertNull(completed.get("messageId"));
                assertNull(completed.get("noticeNumber"));
                assertNull(completed.get("creditorFiscalCode"));
            }
        }
        assertEquals(beforeOuter, MDC.getCopyOfContextMap());
        assertNull(OperationLogScope.currentOrNull());
    }

    @Test
    void emptyRunEmitsOneDebugCompletionWithZeroCountsAndLeavesNoContext() throws Exception {
        scheduler.retryPaSendRT();

        assertCompletion(0, Level.DEBUG, "OK", "201", 0, 0, 0, 0, 0);
        assertTrue(MDC.getCopyOfContextMap() == null || MDC.getCopyOfContextMap().isEmpty());
        assertNull(OperationLogScope.currentOrNull());
        assertSame(Thread.currentThread(), scheduler.getThreadOfExecution());
    }

    @Test
    void successfulMessageDoesNotLeakItsHttpCodeOrPaymentContextIntoJobCompletion() throws Exception {
        delegateToRealService(message("processed", 1));
        doAnswer(invocation -> {
            assertEquals("retryReceipt", MDC.get("method"));
            assertEquals("processed", MDC.get("messageId"));
            assertNull(MDC.get("httpCode"));
            MDC.put("httpCode", "418");
            MDC.put("status", "KO");
            MDC.put("responseTime", "999999");
            return null;
        }).when(partner).getReceiptPaymentOptionScheduler(
                anyString(), anyString(), anyString(), anyBoolean(),
                any(PaymentOptionModel.class), any(ReceiptEntity.class));

        scheduler.retryPaSendRT();

        assertCompletion(0, Level.INFO, "OK", "201", 1, 0, 0, 0, 0);
        ILoggingEvent receipt = logs.list.stream()
                .filter(event -> "RECEIPT_PROCESSING_COMPLETED".equals(
                        event.getMDCPropertyMap().get("event.code")))
                .findFirst().orElseThrow();
        Map<String, String> completed = completions().get(0).getMDCPropertyMap();
        assertEquals("418", receipt.getMDCPropertyMap().get("httpCode"));
        assertEquals(completed.get("jobRunId"), receipt.getMDCPropertyMap().get("jobRunId"));
        assertNotEquals(completed.get("requestId"), receipt.getMDCPropertyMap().get("requestId"));
        assertNotEquals(completed.get("operationId"), receipt.getMDCPropertyMap().get("operationId"));
        for (String field : List.of("messageId", "noticeNumber", "creditorFiscalCode", "faultCode")) {
            assertNull(completed.get(field), field);
        }
        verify(queue).deleteMessage("processed", "pop-processed");
        assertSame(Thread.currentThread(), scheduler.getThreadOfExecution());
    }

    @Test
    void successfulNonemptyRunReportsEverySuccessfulDisposition() throws Exception {
        delegateToRealService(message("processed", 1), message("retry", 1),
                message("discarded", 1), message("dead-lettered", 6));
        when(partner.getReceiptPaymentOptionScheduler(
                anyString(), anyString(), anyString(), anyBoolean(),
                any(PaymentOptionModel.class), any(ReceiptEntity.class)))
                .thenReturn(null)
                .thenThrow(mock(FeignException.class))
                .thenThrow(new PartnerValidationException(PaaErrorEnum.PAA_RECEIPT_DUPLICATA));

        scheduler.retryPaSendRT();

        assertCompletion(0, Level.INFO, "OK", "201", 1, 1, 1, 1, 0);
        assertNull(completions().get(0).getMDCPropertyMap().get("faultCode"));
        verify(deadLetters).sendToDeadLetter(any());
        verify(queue).updateMessageWithResponse(eq("retry"), eq("pop-retry"), anyString(),
                eq(Duration.ofSeconds(1)), isNull(), eq(Context.NONE));
    }

    @Test
    void swallowedMessageFailureStillMakesJobKoAndRestoresOuterScope() throws Exception {
        delegateToRealService(message("dead-letter-failed", 6));
        doThrow(new IllegalStateException("persistence failed"))
                .when(deadLetters).sendToDeadLetter(any());

        try (OperationLogScope outer = outerScope()) {
            Map<String, String> before = MDC.getCopyOfContextMap();
            assertDoesNotThrow(scheduler::retryPaSendRT);
            assertEquals(before, MDC.getCopyOfContextMap());
            assertSame(outer, OperationLogScope.current());
        }

        assertCompletion(0, Level.INFO, "KO", "500", 0, 0, 0, 0, 1);
        assertSame(Thread.currentThread(), scheduler.getThreadOfExecution());
        verify(queue, never()).deleteMessage(anyString(), anyString());
    }

    @Test
    void thrownBatchAbortKeepsPartialCountsEmitsOnceAndRestoresOuterScope() throws Exception {
        delegateToRealService(message("processed", 1), message("failed", 1),
                message("not-reached", 1));
        IllegalStateException failure = new IllegalStateException("processing failed");
        when(partner.getReceiptPaymentOptionScheduler(
                anyString(), anyString(), anyString(), anyBoolean(),
                any(PaymentOptionModel.class), any(ReceiptEntity.class)))
                .thenReturn(null).thenThrow(failure);

        try (OperationLogScope outer = outerScope()) {
            Map<String, String> before = MDC.getCopyOfContextMap();
            assertSame(failure, assertThrows(IllegalStateException.class, scheduler::retryPaSendRT));
            assertEquals(before, MDC.getCopyOfContextMap());
            assertSame(outer, OperationLogScope.current());
        }

        assertCompletion(0, Level.INFO, "KO", "500", 1, 0, 0, 0, 1);
        assertNull(scheduler.getThreadOfExecution());
        verify(partner, times(2)).getReceiptPaymentOptionScheduler(
                anyString(), anyString(), anyString(), anyBoolean(),
                any(PaymentOptionModel.class), any(ReceiptEntity.class));
        verify(queue, never()).deleteMessage(eq("not-reached"), anyString());
    }

    @Test
    void abortBeforeAnyMessageIsInfoKoEvenWithZeroCounts() throws Exception {
        IllegalStateException failure = new IllegalStateException("receive failed");
        doThrow(failure).when(schedulerService)
                .retryFailedPaSendRT(any(SchedulerService.JobCounts.class));
        MDC.put("outside", "preserved");
        Map<String, String> before = MDC.getCopyOfContextMap();

        assertSame(failure, assertThrows(IllegalStateException.class, scheduler::retryPaSendRT));

        assertCompletion(0, Level.INFO, "KO", "500", 0, 0, 0, 0, 0);
        assertEquals(before, MDC.getCopyOfContextMap());
        assertNull(OperationLogScope.currentOrNull());
        assertNull(scheduler.getThreadOfExecution());
    }

    @Test
    void everyRunGetsNewCountsAndAnEarlierFailureCannotContaminateEmptySuccess() throws Exception {
        delegateToRealService(message("dead-letter-failed", 6));
        doThrow(new IllegalStateException("persistence failed"))
                .when(deadLetters).sendToDeadLetter(any());

        scheduler.retryPaSendRT();
        assertCompletion(0, Level.INFO, "KO", "500", 0, 0, 0, 0, 1);
        doNothing().when(schedulerService).retryFailedPaSendRT(any(SchedulerService.JobCounts.class));
        scheduler.retryPaSendRT();

        ArgumentCaptor<SchedulerService.JobCounts> counts =
                ArgumentCaptor.forClass(SchedulerService.JobCounts.class);
        verify(schedulerService, times(2)).retryFailedPaSendRT(counts.capture());
        assertNotSame(counts.getAllValues().get(0), counts.getAllValues().get(1));
        assertEquals(1L, counts.getAllValues().get(0).summary().failedCount().longValue());
        assertEquals(0L, counts.getAllValues().get(1).summary().failedCount().longValue());
        assertCompletion(1, Level.DEBUG, "OK", "201", 0, 0, 0, 0, 0);
    }

    private OperationLogScope outerScope() {
        OperationLogScope outer = new ContextSnapshot("outer-request", "outer-operation",
                "outer-job", "outer-message", "outer-notice", "outer-creditor")
                .openOperation("outer-method");
        MDC.put("status", "outer-status");
        MDC.put("httpCode", "409");
        MDC.put("responseTime", "outer-time");
        MDC.put("event.code", "outer-event");
        MDC.put("ctx_details", "outer-details");
        MDC.put("error.type", "outer-error");
        MDC.put("unrelated", "preserved");
        return outer;
    }

    @SuppressWarnings("unchecked")
    private void delegateToRealService(QueueMessageItem... messages) {
        PagedIterable<QueueMessageItem> page = mock(PagedIterable.class);
        when(page.stream()).thenAnswer(invocation -> List.of(messages).stream());
        when(queue.receiveMessages(10, Duration.ofSeconds(1), null, Context.NONE)).thenReturn(page);
        SchedulerService realService = new SchedulerService(
                5, 1L, 1L, 8L, 2.0, queue, partner, deadLetters);
        doAnswer(invocation -> {
            realService.retryFailedPaSendRT(invocation.getArgument(0));
            return null;
        }).when(schedulerService).retryFailedPaSendRT(any(SchedulerService.JobCounts.class));
    }

    private QueueMessageItem message(String id, long dequeueCount) {
        QueueMessageItem message = mock(QueueMessageItem.class);
        // A batch abort can leave a later message entirely untouched.
        lenient().when(message.getMessageId()).thenReturn(id);
        lenient().when(message.getPopReceipt()).thenReturn("pop-" + id);
        lenient().when(message.getDequeueCount()).thenReturn(dequeueCount);
        lenient().when(message.getBody()).thenReturn(BinaryData.fromString("""
                <receipt>
                  <fiscalCode>77777777777</fiscalCode>
                  <creditorReferenceId>reference</creditorReferenceId>
                  <noticeNumber>302000000000000001</noticeNumber>
                  <paymentDateTime>2026-01-01T12:00:00</paymentDateTime>
                  <receiptId>receipt-id</receiptId>
                  <PSPCompanyName>PSP</PSPCompanyName>
                  <paymentMethod>card</paymentMethod>
                  <fee>1.00</fee>
                  <entityUniqueIdentifierValue>debtor</entityUniqueIdentifierValue>
                </receipt>
                """));
        return message;
    }

    private List<ILoggingEvent> completions() {
        return logs.list.stream()
                .filter(event -> "SCHEDULED_JOB_COMPLETED".equals(
                        event.getMDCPropertyMap().get("event.code")))
                .toList();
    }

    private void assertCompletion(int index, Level level, String status, String httpCode,
            long processed, long retried, long discarded, long deadLettered, long failed)
            throws Exception {
        assertEquals(index + 1, completions().size());
        ILoggingEvent event = completions().get(index);
        Map<String, String> fields = event.getMDCPropertyMap();
        assertEquals(level, event.getLevel());
        assertEquals("Scheduled job completed", event.getFormattedMessage());
        assertEquals(Scheduler.class.getName(), event.getLoggerName());
        assertEquals("retryPaSendRT", fields.get("method"));
        assertEquals(status, fields.get("status"));
        assertEquals(httpCode, fields.get("httpCode"));
        assertTrue(Long.parseLong(fields.get("responseTime")) >= 0);
        JsonNode details = new ObjectMapper().readTree(fields.get("ctx_details"));
        assertEquals(5, details.size());
        String[] names = {"processedCount", "retryScheduledCount", "discardedCount",
                "deadLetteredCount", "failedCount"};
        long[] values = {processed, retried, discarded, deadLettered, failed};
        for (int i = 0; i < names.length; i++) {
            assertTrue(details.get(names[i]).isIntegralNumber(), names[i]);
            assertEquals(values[i], details.get(names[i]).longValue(), names[i]);
        }
        assertNull(fields.get("error.type"));
        assertNull(fields.get("error.message"));
        assertNull(fields.get("error.stack_trace"));
        for (ILoggingEvent logged : logs.list) {
            assertNull(logged.getThrowableProxy());
            assertTrue(logged.getArgumentArray() == null || logged.getArgumentArray().length == 0);
        }
    }
}