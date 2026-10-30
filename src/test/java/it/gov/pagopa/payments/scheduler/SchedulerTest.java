package it.gov.pagopa.payments.scheduler;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.junit.jupiter.api.Assertions.*;

import it.gov.pagopa.payments.config.LogCapture;
import it.gov.pagopa.payments.config.LogContext;
import it.gov.pagopa.payments.service.SchedulerService;
import it.gov.pagopa.payments.utils.SchedulerUtils;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

@ExtendWith(MockitoExtension.class)
class SchedulerTest {

    @Mock
    private SchedulerService schedulerService;

    @InjectMocks
    private Scheduler scheduler;

    @Test
    void retryPaSendRTShouldDelegateToSchedulerService() {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try (LogCapture logs = new LogCapture(SchedulerUtils.class)) {
            MDC.clear();
            MDC.put(LogContext.CORRELATION_ID, "caller-context");
            doAnswer(invocation -> {
                assertEquals("retryPaSendRT", MDC.get(LogContext.METHOD));
                UUID.fromString(MDC.get(LogContext.CORRELATION_ID));
                return null;
            }).when(schedulerService).retryFailedPaSendRT();
            scheduler.retryPaSendRT();
            verify(schedulerService).retryFailedPaSendRT();
            assertEquals(1, logs.events().size());
            assertEquals("retryPaSendRT", logs.events().get(0).path(LogContext.METHOD).asText());
            assertEquals("OK", logs.events().get(0).path(LogContext.STATUS).asText());
            assertTrue(logs.events().get(0).path(LogContext.RESPONSE_TIME).isIntegralNumber());
            assertEquals("caller-context", MDC.get(LogContext.CORRELATION_ID));
            assertNull(MDC.get(LogContext.METHOD));
        } finally {
            LogContext.restore(previous);
        }
    }

    @Test
    void failedJobPreservesExceptionAndRestoresCallerContext() {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        IllegalStateException failure = new IllegalStateException("Retry failed");
        doThrow(failure).when(schedulerService).retryFailedPaSendRT();
        try (LogCapture logs = new LogCapture(SchedulerUtils.class)) {
            MDC.clear();
            MDC.put(LogContext.METHOD, "caller-operation");
            assertSame(failure, assertThrows(IllegalStateException.class, scheduler::retryPaSendRT));
            assertEquals(2, logs.events().size());
            assertEquals("Retry failed", logs.events().get(0).path(LogContext.ERROR_MESSAGE).asText());
            assertEquals("KO", logs.events().get(1).path(LogContext.STATUS).asText());
            assertEquals("retryPaSendRT", logs.events().get(1).path(LogContext.METHOD).asText());
            assertEquals("caller-operation", MDC.get(LogContext.METHOD));
        } finally {
            LogContext.restore(previous);
        }
    }
}