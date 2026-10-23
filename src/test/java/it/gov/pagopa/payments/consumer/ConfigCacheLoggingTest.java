package it.gov.pagopa.payments.consumer;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.gov.pagopa.payments.consumers.cache.CacheConsumerConfig;
import it.gov.pagopa.payments.consumers.cache.model.CacheUpdateEvent;
import it.gov.pagopa.payments.logging.StructuredEcsEncoder;
import it.gov.pagopa.payments.logging.StructuredLogFieldsConverter;
import it.gov.pagopa.payments.service.ConfigCacheService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ConfigCacheLoggingTest {
    private static final String HEADER_SECRET = "CACHE_HEADER_SECRET";
    private static final String BODY_SECRET = "CACHE_BODY_SECRET";
    private static final String FAILURE_SECRET = "CACHE_FAILURE_SECRET";
    private final ConfigCacheService service = mock(ConfigCacheService.class);
    private final CacheConsumerConfig consumer = new CacheConsumerConfig(service);
    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private final List<ILoggingEvent> events = new ArrayList<>();
    private final AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
        @Override
        protected void append(ILoggingEvent event) {
            if (event.getLoggerName().equals(CacheConsumerConfig.class.getName())
                    || event.getLoggerName().equals(
                            "it.gov.pagopa.payments.logging.BackgroundExecutionLogging")) {
                event.prepareForDeferredProcessing();
                events.add(event);
            }
        }
    };
    private Map<String, String> previous;

    @BeforeEach
    void capture() {
        previous = MDC.getCopyOfContextMap();
        MDC.clear();
        when(service.checkAndUpdateCache(any())).thenReturn(true);
        appender.setContext(root.getLoggerContext());
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void cleanup() {
        root.detachAppender(appender);
        appender.stop();
        if (previous == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(previous);
        }
    }

    @Test
    void createsFreshContextForEveryEventAndRestoresExactCallerContext() throws Exception {
        Map<String, String> caller = callerContext();
        MDC.setContextMap(caller);
        List<Map<String, String>> contexts = new ArrayList<>();
        doAnswer(invocation -> {
            contexts.add(MDC.getCopyOfContextMap());
            MDC.put("callback-only", "must-not-leak");
            return true;
        }).when(service).checkAndUpdateCache(any());

        consumer.consumerCacheEvent().accept(message());
        assertEquals(caller, MDC.getCopyOfContextMap());
        consumer.consumerCacheEvent().accept(message());
        assertEquals(caller, MDC.getCopyOfContextMap());

        assertEquals(2, contexts.size());
        assertNotEquals(contexts.get(0).get("correlation_id"), contexts.get(1).get("correlation_id"));
        for (Map<String, String> context : contexts) {
            assertFreshContext(context);
        }
        assertEquals(2, events.size());
        for (int index = 0; index < events.size(); index++) {
            assertSummary(events.get(index), contexts.get(index).get("correlation_id"), "OK");
        }
        verify(service, times(2)).checkAndUpdateCache(any());
        assertPrivateOutput();
    }

    @Test
    void restoresInitiallyEmptyContext() {
        consumer.handleCacheEvent(message());

        Map<String, String> restored = MDC.getCopyOfContextMap();
        assertTrue(restored == null || restored.isEmpty());
        assertEquals(1, events.size());
    }

    @Test
    void absorbedRefreshFailureMarksSummaryKoWithoutAnotherDiagnosis() throws Exception {
        Map<String, String> caller = callerContext();
        MDC.setContextMap(caller);
        when(service.checkAndUpdateCache(any())).thenReturn(false);

        assertDoesNotThrow(() -> consumer.handleCacheEvent(message()));

        assertEquals(caller, MDC.getCopyOfContextMap());
        assertEquals(1, events.size());
        String correlationId = events.get(0).getMDCPropertyMap().get("correlation_id");
        assertNotEquals(caller.get("correlation_id"), correlationId);
        assertSummary(events.get(0), correlationId, "KO");
        assertPrivateOutput();
    }

    @Test
    void diagnosesRuntimeFailureOnceAndRethrowsSameObjectWithContextRestored() throws Exception {
        RuntimeException failure = new IllegalStateException(
                FAILURE_SECRET, new IllegalArgumentException(FAILURE_SECRET));
        failure.addSuppressed(new RuntimeException(FAILURE_SECRET));
        assertFailure(failure);
    }

    @Test
    void diagnosesErrorOnceAndRethrowsSameObjectWithContextRestored() throws Exception {
        assertFailure(new AssertionError(FAILURE_SECRET));
    }

    private void assertFailure(Throwable failure) throws Exception {
        Map<String, String> caller = callerContext();
        MDC.setContextMap(caller);
        List<Map<String, String>> contexts = new ArrayList<>();
        doAnswer(invocation -> {
            contexts.add(MDC.getCopyOfContextMap());
            throw failure;
        }).when(service).checkAndUpdateCache(any());

        assertSame(failure, assertThrows(failure.getClass(), () -> consumer.handleCacheEvent(message())));

        assertEquals(caller, MDC.getCopyOfContextMap());
        assertEquals(1, contexts.size());
        assertFreshContext(contexts.get(0));
        assertEquals(2, events.size());
        String correlationId = contexts.get(0).get("correlation_id");
        List<ILoggingEvent> diagnoses = new ArrayList<>();
        List<ILoggingEvent> summaries = new ArrayList<>();
        for (ILoggingEvent event : events) {
            assertEquals(correlationId, event.getMDCPropertyMap().get("correlation_id"));
            assertEquals("handleCacheEvent", event.getMDCPropertyMap().get("method"));
            if (encoded(event).has("error.type")) {
                diagnoses.add(event);
            } else {
                summaries.add(event);
            }
        }
        assertEquals(1, diagnoses.size());
        JsonNode diagnosis = encoded(diagnoses.get(0));
        assertEquals(failure.getClass().getName(), diagnosis.path("error.type").asText());
        assertEquals(failure.getMessage(), diagnosis.path("error.message").asText());
        assertTrue(diagnosis.path("error.stack_trace").asText().contains(getClass().getName()));
        assertFalse(diagnosis.path("error.stack_trace").asText().contains(FAILURE_SECRET));
        assertEquals(1, summaries.size());
        assertSummary(summaries.get(0), correlationId, "KO");
        assertPrivateOutput();
    }

    private void assertFreshContext(Map<String, String> context) {
        assertNotNull(context.get("correlation_id"));
        assertFalse(context.get("correlation_id").isBlank());
        assertNotEquals("caller-correlation", context.get("correlation_id"));
        assertEquals("handleCacheEvent", context.get("method"));
        assertFalse(context.containsKey("ctx_creditor_id"));
        assertFalse(context.containsKey("ctx_notice_number"));
        assertFalse(context.containsKey("ctx_iuv"));
        assertFalse(context.containsKey("args"));
        assertFalse(context.containsKey("httpCode"));
        assertFalse(context.containsKey("ctx_soap_outcome"));
    }

    private void assertSummary(ILoggingEvent event, String correlationId, String status) throws Exception {
        JsonNode logEntry = encoded(event);
        assertEquals(correlationId, logEntry.path("correlation_id").asText());
        assertEquals("handleCacheEvent", logEntry.path("method").asText());
        assertEquals(status, logEntry.path("status").asText());
        assertTrue(logEntry.path("response_time").isIntegralNumber());
        assertTrue(logEntry.path("response_time").longValue() >= 0);
        assertFalse(logEntry.has("http_code"));
        assertFalse(logEntry.has("http.request.method"));
        assertFalse(logEntry.has("ctx_soap_outcome"));
        assertFalse(logEntry.has("args"));
        assertFalse(logEntry.has("error.type"));
    }

    private void assertPrivateOutput() {
        StructuredEcsEncoder encoder = new StructuredEcsEncoder();
        encoder.setContext(root.getLoggerContext());
        encoder.start();
        PatternLayout layout = new PatternLayout();
        layout.setContext(root.getLoggerContext());
        layout.getInstanceConverterMap().put("structuredFields", StructuredLogFieldsConverter.class.getName());
        layout.setPattern("%msg %mdc %structuredFields %ex");
        layout.start();
        try {
            for (ILoggingEvent event : events) {
                String output = new String(encoder.encode(event), StandardCharsets.UTF_8)
                        + layout.doLayout(event);
                assertFalse(output.contains(HEADER_SECRET), output);
                assertFalse(output.contains(BODY_SECRET), output);
            }
        } finally {
            layout.stop();
            encoder.stop();
        }
    }

    private JsonNode encoded(ILoggingEvent event) throws Exception {
        StructuredEcsEncoder encoder = new StructuredEcsEncoder();
        encoder.setContext(root.getLoggerContext());
        encoder.start();
        try {
            return new ObjectMapper().readTree(encoder.encode(event));
        } finally {
            encoder.stop();
        }
    }

    private static Map<String, String> callerContext() {
        return Map.of("correlation_id", "caller-correlation", "method", "caller-method",
                "status", "OK", "ctx_creditor_id", "caller-creditor",
                "ctx_notice_number", "caller-notice", "ctx_iuv", "caller-iuv",
                "args", BODY_SECRET, "httpCode", "500", "ctx_soap_outcome", "KO",
                "unrelated", "preserve-exactly");
    }

    private static Message<CacheUpdateEvent> message() {
        CacheUpdateEvent event = new CacheUpdateEvent(BODY_SECRET, BODY_SECRET, BODY_SECRET) {
            @Override
            public String toString() {
                return BODY_SECRET;
            }
        };
        return MessageBuilder.withPayload(event).setHeader("private-header", HEADER_SECRET).build();
    }
}
