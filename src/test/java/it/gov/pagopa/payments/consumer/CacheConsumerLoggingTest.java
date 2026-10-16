package it.gov.pagopa.payments.consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import it.gov.pagopa.payments.client.ApiConfigCacheClient;
import it.gov.pagopa.payments.consumers.cache.CacheConsumerConfig;
import it.gov.pagopa.payments.consumers.cache.model.CacheUpdateEvent;
import it.gov.pagopa.payments.logging.LogEventWriter;
import it.gov.pagopa.payments.logging.FailureDiagnostics;
import it.gov.pagopa.payments.logging.OperationLogScope;
import it.gov.pagopa.payments.model.client.cache.ConfigCacheData;
import it.gov.pagopa.payments.service.ConfigCacheService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.messaging.Message;

class CacheConsumerLoggingTest {
    private final ConfigCacheService service = mock(ConfigCacheService.class);
    private final CacheConsumerConfig consumer = new CacheConsumerConfig(service);
    private final Message<CacheUpdateEvent> message = mock(Message.class);
    private final CacheUpdateEvent payload = mock(CacheUpdateEvent.class);
    private final Logger logger = (Logger) LoggerFactory.getLogger(CacheConsumerConfig.class);
    private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(ConfigCacheService.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>() {
        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };
    private Map<String, String> previousMdc;
    private Level previousLevel;
    private Level previousServiceLevel;

    @BeforeEach
    void setUp() {
        previousMdc = MDC.getCopyOfContextMap();
        MDC.clear();
        previousLevel = logger.getLevel();
        previousServiceLevel = serviceLogger.getLevel();
        logger.setLevel(Level.DEBUG);
        serviceLogger.setLevel(Level.DEBUG);
        logs.start();
        logger.addAppender(logs);
        serviceLogger.addAppender(logs);
        when(message.getPayload()).thenReturn(payload);
        when(message.toString()).thenThrow(new AssertionError("Message dump"));
        when(payload.toString()).thenThrow(new AssertionError("Payload dump"));
    }

    @AfterEach
    void tearDown() {
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
    void successfulMessageHasFreshContextWithoutLoggingPayloadOrInventingAnInfoEvent() {
        MDC.setContextMap(Map.of("requestId", "outer-request", "operationId", "outer-operation",
                "args", "old payload", "faultDetail", "old fault", "soapTechnicalErrorLogged", "true"));
        Map<String, String> outer = MDC.getCopyOfContextMap();
        doAnswer(invocation -> {
            assertNotNull(OperationLogScope.currentOrNull());
            assertNotEquals("outer-request", MDC.get("requestId"));
            assertNotEquals("outer-operation", MDC.get("operationId"));
            assertEquals("handleCacheEvent", MDC.get("method"));
            assertNull(MDC.get("args"));
            assertNull(MDC.get("faultDetail"));
            assertNull(MDC.get("soapTechnicalErrorLogged"));
            return null;
        }).when(service).checkAndUpdateCache(payload);

        consumer.consumerCacheEvent().accept(message);

        verify(service).checkAndUpdateCache(payload);
        verify(message).getPayload();
        verifyNoMoreInteractions(message);
        assertTrue(logs.list.isEmpty());
        assertEquals(outer, MDC.getCopyOfContextMap());
        assertNull(OperationLogScope.currentOrNull());
    }

    @Test
    void failureIsRethrownUnchangedWithBoundedOriginalDiagnosticAndRestoredContext() {
        RuntimeException failure = failureChain();
        StackTraceElement[] originalFrames = failure.getStackTrace();
        doAnswer(invocation -> {
            throw failure;
        }).when(service).checkAndUpdateCache(payload);
        MDC.put("sentinel", "outer");
        try (OperationLogScope outer = OperationLogScope.open("outer-request", "outer-method")) {
            Map<String, String> outerMdc = MDC.getCopyOfContextMap();

            assertSame(failure, assertThrows(RuntimeException.class,
                    () -> consumer.handleCacheEvent(message)));

            assertSame(outer, OperationLogScope.current());
            assertEquals(outerMdc, MDC.getCopyOfContextMap());
            assertFalse(outer.hasDiagnosis());
        }
        assertEquals(Map.of("sentinel", "outer"), MDC.getCopyOfContextMap());
        assertEquals(1, logs.list.size());
        ILoggingEvent logged = logs.list.get(0);
        assertEquals(Level.ERROR, logged.getLevel());
        assertEquals("OPERATION_TECHNICAL_FAILED", logged.getMDCPropertyMap().get("event.code"));
        assertEquals(failure.getMessage(), logged.getMDCPropertyMap().get("error.message"));
        assertEquals(failure.getClass().getName(), logged.getMDCPropertyMap().get("error.type"));
        String stack = logged.getMDCPropertyMap().get("error.stack_trace");
        assertTrue(stack.contains("middle original"));
        assertTrue(stack.contains("root original"));
        assertFalse(stack.contains("omitted original"));
        assertTrue(stack.contains("1 exceptions omitted"));
        assertEquals(9, stack.lines().filter(line -> line.startsWith("\tat ")).count());
        assertArrayEquals(originalFrames, failure.getStackTrace());
        assertSafeEvent(logged);
    }

    @Test
    void downstreamDiagnosisOwnsConvertedFailureButNotIndependentCleanupFailure() {
        RuntimeException original = new IllegalStateException("original download error");
        RuntimeException converted = new IllegalArgumentException("converted", original);
        RuntimeException cleanup = new IllegalStateException("independent cleanup error");
        original.addSuppressed(cleanup);
        doAnswer(invocation -> {
            FailureDiagnostics.absorbedFailure(new LogEventWriter(logger), original);
            FailureDiagnostics.absorbedFailure(new LogEventWriter(logger), cleanup);
            throw converted;
        }).when(service).checkAndUpdateCache(payload);

        assertSame(converted, assertThrows(IllegalArgumentException.class,
                () -> consumer.handleCacheEvent(message)));

        assertEquals(List.of("original download error", "independent cleanup error"),
                logs.list.stream().map(event -> event.getMDCPropertyMap().get("error.message")).toList());
        logs.list.forEach(this::assertSafeEvent);
        assertNull(OperationLogScope.currentOrNull());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void realDownloadFailureIsDiagnosedOnlyByConsumerRegardlessOfUsableCache(boolean cacheMissing) {
        ApiConfigCacheClient client = mock(ApiConfigCacheClient.class);
        RuntimeException failure = new IllegalStateException("download original");
        when(client.getCacheByKeys(anyString())).thenThrow(failure);
        try (MockedStatic<ConfigCacheData> cache = mockStatic(ConfigCacheData.class)) {
            cache.when(ConfigCacheData::isConfigDataNull).thenReturn(cacheMissing);
            CacheConsumerConfig realConsumer = new CacheConsumerConfig(new ConfigCacheService(client));

            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> realConsumer.handleCacheEvent(message)));
        }

        assertEquals(1, logs.list.size());
        ILoggingEvent logged = logs.list.get(0);
        assertEquals(Level.ERROR, logged.getLevel());
        assertEquals("DEPENDENCY_CALL_FAILED", logged.getMDCPropertyMap().get("event.code"));
        assertEquals(CacheConsumerConfig.class.getName(), logged.getLoggerName());
        assertTrue(logged.getMDCPropertyMap().get("ctx_details").contains("api-config-cache"));
        assertEquals("download original", logged.getMDCPropertyMap().get("error.message"));
        assertSafeEvent(logged);
    }

    @Test
    void repeatedFailureOnReusedConsumerThreadHasNewOwnershipAndIdentifiers() {
        RuntimeException failure = new IllegalStateException("same instance on two messages");
        doThrow(failure).when(service).checkAndUpdateCache(payload);
        assertThrows(IllegalStateException.class, () -> consumer.handleCacheEvent(message));
        assertThrows(IllegalStateException.class, () -> consumer.handleCacheEvent(message));

        assertEquals(2, logs.list.size());
        for (String id : List.of("requestId", "operationId")) {
            assertNotEquals(logs.list.get(0).getMDCPropertyMap().get(id),
                    logs.list.get(1).getMDCPropertyMap().get(id));
        }
        logs.list.forEach(this::assertSafeEvent);
        assertNull(OperationLogScope.currentOrNull());
        assertTrue(MDC.getCopyOfContextMap() == null || MDC.getCopyOfContextMap().isEmpty());
    }

    private void assertSafeEvent(ILoggingEvent event) {
        assertNull(event.getThrowableProxy());
        assertNull(event.getArgumentArray());
        assertNull(event.getMDCPropertyMap().get("args"));
        assertNull(event.getMDCPropertyMap().get("faultDetail"));
        assertNull(event.getMDCPropertyMap().get("soapTechnicalErrorLogged"));
        assertFalse(event.getFormattedMessage().contains(event.getMDCPropertyMap().get("error.message")));
    }

    private RuntimeException failureChain() {
        RuntimeException root = new IllegalStateException("root original");
        RuntimeException omitted = new IllegalStateException("omitted original", root);
        RuntimeException middle = new IllegalStateException("middle original", omitted);
        RuntimeException initial = new IllegalArgumentException(
                "original token=secret\nhttps://cache.invalid/body " + "untruncated ".repeat(300), middle);
        StackTraceElement[] frames = new StackTraceElement[8];
        for (int i = 0; i < frames.length; i++) {
            frames[i] = new StackTraceElement("it.gov.pagopa.payments.Cache", "load", "Cache.java", i + 1);
        }
        for (Throwable failure : List.of(initial, middle, omitted, root)) {
            failure.setStackTrace(frames);
        }
        return initial;
    }
}
