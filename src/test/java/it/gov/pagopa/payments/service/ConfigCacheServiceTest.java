package it.gov.pagopa.payments.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import feign.Request;
import it.gov.pagopa.payments.client.ApiConfigCacheClient;
import it.gov.pagopa.payments.consumers.cache.model.CacheUpdateEvent;
import it.gov.pagopa.payments.logging.LogEventWriter;
import it.gov.pagopa.payments.logging.FailureDiagnostics;
import it.gov.pagopa.payments.logging.OperationLogScope;
import it.gov.pagopa.payments.model.client.cache.ConfigCacheData;
import it.gov.pagopa.payments.model.client.cache.ConfigDataV1;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class ConfigCacheServiceTest {
    private final ApiConfigCacheClient client = mock(ApiConfigCacheClient.class);
    private final ConfigCacheService service = new ConfigCacheService(client);
    private final Logger logger = (Logger) LoggerFactory.getLogger(ConfigCacheService.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>() {
        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };
    private MockedStatic<ConfigCacheData> cache;
    private Level previousLevel;
    private Map<String, String> previousMdc;

    @BeforeEach
    void setUp() {
        previousMdc = MDC.getCopyOfContextMap();
        MDC.clear();
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logs.start();
        logger.addAppender(logs);
        cache = mockStatic(ConfigCacheData.class);
        cache.when(ConfigCacheData::isConfigDataNull).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        cache.close();
        logger.detachAppender(logs);
        logger.setLevel(previousLevel);
        logs.stop();
        if (previousMdc == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(previousMdc);
        }
    }

    @Test
    void unavailableCacheSwallowsFeignFailureAndReportsErrorWithoutScopeOrNewIds() throws Exception {
        FeignException failure = feignFailure();
        doThrow(failure).when(client).getCacheByKeys(anyString());

        assertDoesNotThrow(() -> service.checkAndUpdateCache(null));

        assertDependencyFailure(Level.ERROR, null);
        assertEquals(503, new ObjectMapper().readTree(
                logs.list.get(0).getMDCPropertyMap().get("ctx_details"))
                .path("dependencyHttpCode").asInt());
        assertNull(MDC.get("requestId"));
        assertNull(MDC.get("operationId"));
        assertNull(logs.list.get(0).getMDCPropertyMap().get("requestId"));
        cache.verify(() -> ConfigCacheData.setConfigData(any()), never());
    }

    @Test
    void staleUsableCacheReportsWarnWhereFeignFailureIsAbsorbed() throws Exception {
        cache.when(ConfigCacheData::isConfigDataNull).thenReturn(false);
        cache.when(ConfigCacheData::getVersion).thenReturn("10");
        FeignException failure = feignFailure();
        doThrow(failure).when(client).getCacheByKeys(anyString());

        try (OperationLogScope ignored = OperationLogScope.open("cache-request")) {
            service.checkAndUpdateCache(null);
            assertEquals("cache-request", MDC.get("requestId"));
        }

        assertDependencyFailure(Level.WARN, "USABLE_CACHE_RETAINED");
        assertEquals(503, new ObjectMapper().readTree(
                logs.list.get(0).getMDCPropertyMap().get("ctx_details"))
                .path("dependencyHttpCode").asInt());
        cache.verify(() -> ConfigCacheData.setConfigData(any()), never());
        cache.verify(() -> ConfigCacheData.setVersion(any()), never());
    }

    @Test
    void staleUsableCacheWarnsWithoutScope() throws Exception {
        cache.when(ConfigCacheData::isConfigDataNull).thenReturn(false);
        doThrow(feignFailure()).when(client).getCacheByKeys(anyString());
        service.checkAndUpdateCache(null);
        assertDependencyFailure(Level.WARN, "USABLE_CACHE_RETAINED");
        assertNull(MDC.get("operationId"));
    }

    @Test
    void postConstructDoesNotAnnounceSuccessWhenDownloadIsUnavailable() throws Exception {
        doThrow(feignFailure()).when(client).getCacheByKeys(anyString());
        service.setConfigCacheData();
        assertDependencyFailure(Level.ERROR, null);
    }

    @Test
    void postConstructOwnsUnexpectedDownloadFailureInItsInitializationScope() throws Exception {
        RuntimeException failure = new IllegalStateException("client failure");
        doThrow(failure).when(client).getCacheByKeys(anyString());
        try (OperationLogScope ignored = OperationLogScope.open("cache-request")) {
            service.setConfigCacheData();
        }
        assertDependencyFailure(Level.ERROR, null);
    }

    @Test
    void postConstructDoesNotDuplicateUnexpectedDownloadFailureWithoutScope() throws Exception {
        doThrow(new IllegalStateException("client failure")).when(client).getCacheByKeys(anyString());
        assertDoesNotThrow(service::setConfigCacheData);
        assertDependencyFailure(Level.ERROR, null);
        assertNull(MDC.get("operationId"));
    }

    @Test
    void unexpectedDownloadFailureIsRethrownUnchanged() throws Exception {
        RuntimeException failure = new IllegalArgumentException("client failure");
        doThrow(failure).when(client).getCacheByKeys(anyString());
        assertSame(failure, assertThrows(IllegalArgumentException.class,
                () -> service.checkAndUpdateCache(null)));
        assertTrue(logs.list.isEmpty(), "Unscoped propagation belongs to the caller");
    }

    @Test
    void usableCachePropagatesUnexpectedFailureSilentlyUntilBoundary() throws Exception {
        cache.when(ConfigCacheData::isConfigDataNull).thenReturn(false);
        cache.when(ConfigCacheData::getVersion).thenReturn("10");
        RuntimeException failure = new IllegalArgumentException("client failure");
        doThrow(failure).when(client).getCacheByKeys(anyString());

        try (OperationLogScope ignored = OperationLogScope.open("cache-request")) {
            assertSame(failure, assertThrows(IllegalArgumentException.class,
                    () -> service.checkAndUpdateCache(null)));
            assertTrue(logs.list.isEmpty());
            FailureDiagnostics.technicalFailure(new LogEventWriter(logger),
                    new IllegalStateException("caller conversion", failure));
            assertEquals("cache-request", MDC.get("requestId"));
        }

        assertDependencyFailure(Level.ERROR, null);
        assertFalse(new ObjectMapper().readTree(
                logs.list.get(0).getMDCPropertyMap().get("ctx_details")).has("dependencyHttpCode"));
        cache.verify(() -> ConfigCacheData.setConfigData(any()), never());
        cache.verify(() -> ConfigCacheData.setVersion(any()), never());
    }

    @Test
    void staleCacheWarningDoesNotNeedAnOperationScope() {
        cache.when(ConfigCacheData::isConfigDataNull).thenReturn(false);
        doThrow(feignFailure()).when(client).getCacheByKeys(anyString());
        RuntimeException diagnosticFailure = new IllegalStateException("diagnostic failure");

        try (MockedStatic<OperationLogScope> scope = mockStatic(OperationLogScope.class)) {
            scope.when(OperationLogScope::currentOrNull).thenThrow(diagnosticFailure);
            assertDoesNotThrow(() -> service.checkAndUpdateCache(null));
        }

        assertEquals(1, logs.list.size());
        assertEquals(Level.WARN, logs.list.get(0).getLevel());
        cache.verify(() -> ConfigCacheData.setConfigData(any()), never());
        cache.verify(() -> ConfigCacheData.setVersion(any()), never());
    }

    @Test
    void nullResponseIsLocalTechnicalFailureWithoutChangingCacheState() {
        service.setConfigCacheData();
        assertEquals(1, logs.list.size());
        assertEquals("OPERATION_TECHNICAL_FAILED",
                logs.list.get(0).getMDCPropertyMap().get("event.code"));
        assertNull(logs.list.get(0).getMDCPropertyMap().get("ctx_details"));
        assertNull(logs.list.get(0).getThrowableProxy());
        cache.verify(() -> ConfigCacheData.setConfigData(any()), never());
        cache.verify(() -> ConfigCacheData.setVersion(any()), never());
    }

    @Test
    void olderResponseDoesNotMutateCacheOrAnnounceSuccess() {
        cache.when(ConfigCacheData::isConfigDataNull).thenReturn(false);
        cache.when(ConfigCacheData::getVersion).thenReturn("20");
        when(client.getCacheByKeys(anyString())).thenReturn(configuration("10"));
        service.checkAndUpdateCache(null);
        cache.verify(() -> ConfigCacheData.setConfigData(any()), never());
        assertTrue(logs.list.isEmpty());
    }

    @Test
    void currentOrNewerResponseUpdatesCacheUnchanged() {
        cache.when(ConfigCacheData::getVersion).thenReturn("10");
        ConfigDataV1 response = configuration("20");
        when(client.getCacheByKeys(anyString())).thenReturn(response);
        service.checkAndUpdateCache(null);
        cache.verify(() -> ConfigCacheData.setConfigData(response));
        verify(client).getCacheByKeys("creditorInstitutionStations,maintenanceStations,version");
        assertTrue(logs.list.isEmpty());
    }

    @Test
    void matchingEventSkipsDownloadWhenCacheIsAvailable() {
        cache.when(ConfigCacheData::isConfigDataNull).thenReturn(false);
        cache.when(ConfigCacheData::getVersion).thenReturn("20");
        service.checkAndUpdateCache(event("20"));
        verifyNoInteractions(client);
        assertTrue(logs.list.isEmpty());
    }

    @Test
    void unavailableCacheRetainsExistingEventVersionMutationEvenWhenDownloadFails() {
        doThrow(feignFailure()).when(client).getCacheByKeys(anyString());
        service.checkAndUpdateCache(event("20"));
        cache.verify(() -> ConfigCacheData.setVersion("20"));
        cache.verify(() -> ConfigCacheData.setConfigData(any()), never());
        assertEquals(Level.ERROR, logs.list.get(0).getLevel());
    }

    private void assertDependencyFailure(Level level, String reason) throws Exception {
        assertEquals(1, logs.list.size());
        ILoggingEvent logged = logs.list.get(0);
        assertEquals(level, logged.getLevel());
        assertEquals("DEPENDENCY_CALL_FAILED", logged.getMDCPropertyMap().get("event.code"));
        JsonNode details = new ObjectMapper().readTree(logged.getMDCPropertyMap().get("ctx_details"));
        assertEquals("api-config-cache", details.path("dependency").asText());
        assertEquals("refreshConfiguration", details.path("dependencyOperation").asText());
        assertEquals("KO", details.path("dependencyOutcome").asText());
        if (reason == null) {
            assertFalse(details.has("reason"));
        } else {
            assertEquals(reason, details.path("reason").asText());
        }
        assertTrue(details.path("durationMs").asLong() >= 0);
        assertNull(logged.getThrowableProxy());
        assertNull(logged.getArgumentArray());
    }

    private FeignException feignFailure() {
        Request request = Request.create(Request.HttpMethod.GET, "https://cache.invalid/cache",
                Map.of(), new byte[0], StandardCharsets.UTF_8, null);
        return new FeignException.ServiceUnavailable("Cache unavailable", request, new byte[0], Map.of());
    }

    private ConfigDataV1 configuration(String version) {
        return ConfigDataV1.builder().version(version)
                .stationCreditorInstitutionMap(Map.of()).maintenanceStationMap(Map.of()).build();
    }

    private CacheUpdateEvent event(String version) {
        return CacheUpdateEvent.builder().cacheVersion(version).version(version)
                .timestamp("1750337844.268590000").build();
    }
}
