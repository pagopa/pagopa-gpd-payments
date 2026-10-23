package it.gov.pagopa.payments.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import feign.Request;
import feign.Response;
import it.gov.pagopa.payments.client.ApiConfigCacheClient;
import it.gov.pagopa.payments.consumers.cache.CacheConsumerConfig;
import it.gov.pagopa.payments.consumers.cache.model.CacheUpdateEvent;
import it.gov.pagopa.payments.logging.BackgroundExecutionLogging;
import it.gov.pagopa.payments.logging.StructuredEcsEncoder;
import it.gov.pagopa.payments.logging.StructuredLogFieldsConverter;
import it.gov.pagopa.payments.model.client.cache.ConfigCacheData;
import it.gov.pagopa.payments.model.client.cache.ConfigDataV1;
import it.gov.pagopa.payments.model.client.cache.MaintenanceStation;
import it.gov.pagopa.payments.model.client.cache.StationCreditorInstitution;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ConfigCacheFailureLoggingTest {
    private static final String SECRET = "CACHE_ABSORBED_FAILURE_SECRET";
    private final ApiConfigCacheClient client = mock(ApiConfigCacheClient.class);
    private final ConfigCacheService service = new ConfigCacheService(client);
    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private final List<ILoggingEvent> events = new ArrayList<>();
    private final AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
        @Override
        protected void append(ILoggingEvent event) {
            if (event.getLoggerName().equals(ConfigCacheService.class.getName())
                    || event.getLoggerName().equals(ConfigCacheData.class.getName())
                    || event.getLoggerName().equals(CacheConsumerConfig.class.getName())
                    || event.getLoggerName().equals(BackgroundExecutionLogging.class.getName())) {
                event.prepareForDeferredProcessing();
                events.add(event);
            }
        }
    };
    private String previousVersion;
    private Object previousStations;
    private Object previousMaintenance;

    @BeforeEach
    void capture() {
        previousVersion = ConfigCacheData.getVersion();
        previousStations = ReflectionTestUtils.getField(ConfigCacheData.class, "stationCreditorInstitutionMap");
        previousMaintenance = ReflectionTestUtils.getField(ConfigCacheData.class, "activeStationMaintenanceMap");
        appender.setContext(root.getLoggerContext());
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void cleanup() {
        root.detachAppender(appender);
        appender.stop();
        ConfigCacheData.setVersion(previousVersion);
        ReflectionTestUtils.setField(ConfigCacheData.class, "stationCreditorInstitutionMap", previousStations);
        ReflectionTestUtils.setField(ConfigCacheData.class, "activeStationMaintenanceMap", previousMaintenance);
    }

    @Test
    void absorbedDownloadFailureRemainsVisibleWithoutThrowingOrRetrying() throws Exception {
        when(client.getCacheByKeys(anyString())).thenThrow(downloadFailure());

        assertFalse(assertDoesNotThrow(() -> service.checkAndUpdateCache(null)));

        verify(client).getCacheByKeys("creditorInstitutionStations,maintenanceStations,version");
        verifyNoMoreInteractions(client);
        assertOnePrivateDiagnosis();
    }

    @Test
    void startupAbsorbedDownloadFailureIsKoWithoutDuplicateDiagnosisOrSuccessMessage() throws Exception {
        when(client.getCacheByKeys(anyString())).thenThrow(downloadFailure());
        try (MockedStatic<ConfigCacheData> cache = mockStatic(ConfigCacheData.class)) {
            cache.when(ConfigCacheData::isConfigDataNull).thenReturn(true);

            assertDoesNotThrow(service::setConfigCacheData);
        }

        assertDiagnosisAndKoSummary(2, "initializeConfigCache");
        assertTrue(events.stream().noneMatch(
                event -> event.getFormattedMessage().contains("initialization completed")));
    }

    @Test
    void startupAbsorbedRebuildFailureIsKoWithoutDuplicateDiagnosis() throws Exception {
        ReflectionTestUtils.setField(ConfigCacheData.class, "stationCreditorInstitutionMap", null);
        ConfigCacheData.setVersion(null);
        ConfigDataV1 data = ConfigDataV1.builder().stationCreditorInstitutionMap(null)
                .maintenanceStationMap(Map.of()).version(null).build();
        when(client.getCacheByKeys(anyString())).thenReturn(data);

        assertDoesNotThrow(service::setConfigCacheData);

        assertDiagnosisAndKoSummary(3, "initializeConfigCache");
        assertNotNull(ReflectionTestUtils.getField(ConfigCacheData.class, "activeStationMaintenanceMap"));
    }

    @Test
    void populatedStartupSkipsRefreshAndRestoresCallerContextWithOkSummary() {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        Map<String, String> caller = Map.of("correlation_id", "startup-caller", "method", "caller",
                "unrelated", "preserve");
        MDC.setContextMap(caller);
        try (MockedStatic<ConfigCacheData> cache = mockStatic(ConfigCacheData.class)) {
            assertTrue(service.getConfigCacheData());

            service.setConfigCacheData();

            assertEquals(caller, MDC.getCopyOfContextMap());
            assertEquals(1, events.size());
            ILoggingEvent summary = events.get(0);
            assertEquals("initializeConfigCache", summary.getMDCPropertyMap().get("method"));
            assertEquals("OK", summary.getMDCPropertyMap().get("status"));
            assertNotEquals("startup-caller", summary.getMDCPropertyMap().get("correlation_id"));
            verifyNoInteractions(client);
            cache.verify(ConfigCacheData::isConfigDataNull, times(2));
            cache.verifyNoMoreInteractions();
        } finally {
            if (previous == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(previous);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void lazyInitializationReturnsRefreshOutcome(boolean rebuilt) {
        ConfigDataV1 data = ConfigDataV1.builder().version(null).build();
        when(client.getCacheByKeys(anyString())).thenReturn(data);
        try (MockedStatic<ConfigCacheData> cache = mockStatic(ConfigCacheData.class)) {
            cache.when(ConfigCacheData::isConfigDataNull).thenReturn(true);
            cache.when(() -> ConfigCacheData.setConfigData(data)).thenReturn(rebuilt);

            assertEquals(rebuilt, service.getConfigCacheData());
        }
    }

    @Test
    void startupDiagnosesAbsorbedFailureWithoutThrowing() throws Exception {
        when(client.getCacheByKeys(anyString())).thenThrow(new IllegalStateException(SECRET));
        try (MockedStatic<ConfigCacheData> cache = mockStatic(ConfigCacheData.class)) {
            cache.when(ConfigCacheData::isConfigDataNull).thenReturn(true);

            assertDoesNotThrow(service::setConfigCacheData);
        }

        assertDiagnosisAndKoSummary(2, "initializeConfigCache");
    }

    @Test
    void propagatedServiceFailureIsNotDiagnosedBeforeItsBoundary() {
        RuntimeException failure = new IllegalArgumentException(SECRET);
        when(client.getCacheByKeys(anyString())).thenThrow(failure);

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.checkAndUpdateCache(null)));
        assertTrue(events.isEmpty());
    }

    @Test
    void absorbedOuterRebuildFailureRemainsVisible() throws Exception {
        assertFalse(assertDoesNotThrow(() -> ConfigCacheData.setConfigData(null)));

        assertOnePrivateDiagnosis();
    }

    @Test
    @SuppressWarnings("unchecked")
    void absorbedStationRebuildFailureStillResetsStationMap() throws Exception {
        Map<String, StationCreditorInstitution> stations = mock(Map.class);
        when(stations.values()).thenThrow(new IllegalStateException(SECRET));
        ConfigDataV1 data = ConfigDataV1.builder().stationCreditorInstitutionMap(stations)
                .maintenanceStationMap(Map.of()).version(null).build();

        assertFalse(assertDoesNotThrow(() -> ConfigCacheData.setConfigData(data)));

        assertNull(ConfigCacheData.getCreditorInstitutionStation("creditor", "station"));
        assertTrue(ConfigCacheData.isConfigDataNull());
        assertNotNull(ReflectionTestUtils.getField(ConfigCacheData.class, "activeStationMaintenanceMap"));
        assertOnePrivateDiagnosis();
    }

    @Test
    @SuppressWarnings("unchecked")
    void absorbedMaintenanceRebuildFailureStillResetsMaintenanceMap() throws Exception {
        Map<String, MaintenanceStation> maintenance = mock(Map.class);
        when(maintenance.entrySet()).thenThrow(new IllegalStateException(SECRET));
        ConfigDataV1 data = ConfigDataV1.builder().stationCreditorInstitutionMap(Map.of())
                .maintenanceStationMap(maintenance).version(null).build();

        assertFalse(assertDoesNotThrow(() -> ConfigCacheData.setConfigData(data)));

        assertNull(ConfigCacheData.getStationInMaintenance("station"));
        assertTrue(ConfigCacheData.isConfigDataNull());
        assertNotNull(ReflectionTestUtils.getField(ConfigCacheData.class, "stationCreditorInstitutionMap"));
        assertOnePrivateDiagnosis();
    }

    @Test
    void successfulRebuildReturnsTrue() {
        ConfigDataV1 data = ConfigDataV1.builder().stationCreditorInstitutionMap(Map.of())
                .maintenanceStationMap(Map.of()).version("test-version").build();

        assertTrue(ConfigCacheData.setConfigData(data));

        assertEquals("test-version", ConfigCacheData.getVersion());
        assertFalse(ConfigCacheData.isConfigDataNull());
        assertTrue(events.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void refreshReturnsExplicitRebuildOutcome(boolean rebuilt) {
        ConfigDataV1 data = ConfigDataV1.builder().version("test-version").build();
        when(client.getCacheByKeys(anyString())).thenReturn(data);
        try (MockedStatic<ConfigCacheData> cache = mockStatic(ConfigCacheData.class)) {
            cache.when(() -> ConfigCacheData.setConfigData(data)).thenReturn(rebuilt);

            assertEquals(rebuilt, service.checkAndUpdateCache(null));

            cache.verify(() -> ConfigCacheData.setConfigData(data));
        }
    }

    @Test
    void skippedRefreshReturnsTrueWithoutFetchingOrRebuilding() {
        CacheUpdateEvent event = new CacheUpdateEvent("same-version", "same-version", "timestamp");
        try (MockedStatic<ConfigCacheData> cache = mockStatic(ConfigCacheData.class)) {
            cache.when(ConfigCacheData::getVersion).thenReturn("same-version");

            assertTrue(service.checkAndUpdateCache(event));

            cache.verify(() -> ConfigCacheData.setConfigData(any()), never());
        }
        verifyNoInteractions(client);
    }

    @Test
    void nullDownloadResponseKeepsExistingSkipBehavior() {
        assertTrue(service.checkAndUpdateCache(null));
        verify(client).getCacheByKeys("creditorInstitutionStations,maintenanceStations,version");
        assertTrue(events.isEmpty());
    }

    @Test
    void consumerMarksAbsorbedDownloadFailureKoWithOneDiagnosisAndOneSummary() throws Exception {
        when(client.getCacheByKeys(anyString())).thenThrow(downloadFailure());
        try (MockedStatic<ConfigCacheData> cache = mockStatic(ConfigCacheData.class)) {
            cache.when(ConfigCacheData::isConfigDataNull).thenReturn(true);

            assertDoesNotThrow(() -> new CacheConsumerConfig(service).handleCacheEvent(
                    MessageBuilder.withPayload(new CacheUpdateEvent()).build()));
        }

        assertDiagnosisAndKoSummary(2);
    }

    @Test
    void consumerMarksAbsorbedRebuildFailureKoWithoutDuplicateDiagnosis() throws Exception {
        ConfigCacheData.setVersion(null);
        ConfigDataV1 data = ConfigDataV1.builder().stationCreditorInstitutionMap(null)
                .maintenanceStationMap(Map.of()).version(null).build();
        when(client.getCacheByKeys(anyString())).thenReturn(data);
        CacheUpdateEvent event = new CacheUpdateEvent("new-version", "new-version", "timestamp");

        assertDoesNotThrow(() -> new CacheConsumerConfig(service).handleCacheEvent(
                MessageBuilder.withPayload(event).build()));

        assertDiagnosisAndKoSummary(3);
        assertNotNull(ReflectionTestUtils.getField(ConfigCacheData.class, "activeStationMaintenanceMap"));
    }

    private void assertDiagnosisAndKoSummary(int expectedEvents) throws Exception {
        assertDiagnosisAndKoSummary(expectedEvents, "handleCacheEvent");
    }

    private void assertDiagnosisAndKoSummary(int expectedEvents, String method) throws Exception {
        assertEquals(expectedEvents, events.size());
        List<ILoggingEvent> diagnoses = new ArrayList<>();
        List<JsonNode> summaries = new ArrayList<>();
        StructuredEcsEncoder encoder = new StructuredEcsEncoder();
        encoder.setContext(root.getLoggerContext());
        encoder.start();
        try {
            for (ILoggingEvent event : events) {
                JsonNode logEntry = new ObjectMapper().readTree(encoder.encode(event));
                if (logEntry.has("error.type")) {
                    diagnoses.add(event);
                } else if (logEntry.has("response_time")) {
                    summaries.add(logEntry);
                }
            }
        } finally {
            encoder.stop();
        }
        assertEquals(1, diagnoses.size());
        assertPrivateDiagnosis(diagnoses.get(0));
        assertEquals(1, summaries.size());
        JsonNode summary = summaries.get(0);
        assertEquals("KO", summary.path("status").asText());
        assertEquals(method, summary.path("method").asText());
        assertFalse(summary.has("http_code"));
        assertEquals(summary.path("correlation_id").asText(),
                diagnoses.get(0).getMDCPropertyMap().get("correlation_id"));
    }

    private void assertOnePrivateDiagnosis() throws Exception {
        assertEquals(1, events.size());
        assertPrivateDiagnosis(events.get(0));
    }

    private static FeignException downloadFailure() {
        Request request = Request.create(Request.HttpMethod.GET, "http://cache.invalid",
                Map.of(), null, StandardCharsets.UTF_8, null);
        Response response = Response.builder().request(request).status(500).reason(SECRET)
                .headers(Map.of()).body(SECRET, StandardCharsets.UTF_8).build();
        return FeignException.errorStatus("getCacheByKeys", response);
    }

    private void assertPrivateDiagnosis(ILoggingEvent event) throws Exception {
        assertEquals("ERROR", event.getLevel().toString());
        StructuredEcsEncoder encoder = new StructuredEcsEncoder();
        encoder.setContext(root.getLoggerContext());
        encoder.start();
        PatternLayout layout = new PatternLayout();
        layout.setContext(root.getLoggerContext());
        layout.getInstanceConverterMap().put("structuredFields", StructuredLogFieldsConverter.class.getName());
        layout.setPattern("%msg %structuredFields %ex");
        layout.start();
        try {
            String ecs = new String(encoder.encode(event), StandardCharsets.UTF_8);
            JsonNode logEntry = new ObjectMapper().readTree(ecs);
            assertTrue(logEntry.has("error.type"));
            assertTrue(logEntry.has("error.message"));
            assertTrue(logEntry.has("error.stack_trace"));
            assertFalse(logEntry.path("error.stack_trace").asText().contains(SECRET));
            assertTrue(layout.doLayout(event).contains(logEntry.get("error.message").toString()));
        } finally {
            layout.stop();
            encoder.stop();
        }
    }
}
