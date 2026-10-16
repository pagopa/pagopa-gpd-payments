package it.gov.pagopa.payments.model.client.cache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import it.gov.pagopa.payments.logging.LogEventWriter;
import it.gov.pagopa.payments.logging.FailureDiagnostics;
import it.gov.pagopa.payments.logging.OperationLogScope;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;

class ConfigCacheDataTest {
    private final Logger logger = (Logger) LoggerFactory.getLogger(ConfigCacheData.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>() {
        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };
    private Level previousLevel;
    private Map<String, String> previousMdc;
    private String previousVersion;
    private Object previousStations;
    private Object previousMaintenance;

    @BeforeEach
    void setUp() {
        previousMdc = MDC.getCopyOfContextMap();
        MDC.clear();
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logs.start();
        logger.addAppender(logs);
        previousVersion = ConfigCacheData.getVersion();
        previousStations = ReflectionTestUtils.getField(ConfigCacheData.class, "stationCreditorInstitutionMap");
        previousMaintenance = ReflectionTestUtils.getField(ConfigCacheData.class, "activeStationMaintenanceMap");
    }

    @AfterEach
    void tearDown() {
        ConfigCacheData.setVersion(previousVersion);
        ReflectionTestUtils.setField(ConfigCacheData.class, "stationCreditorInstitutionMap", previousStations);
        ReflectionTestUtils.setField(ConfigCacheData.class, "activeStationMaintenanceMap", previousMaintenance);
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
    void bothMapFailuresContinueAndRemainIndependentWithinOneScope() {
        ConfigDataV1 configuration = mock(ConfigDataV1.class);
        RuntimeException stationFailure = new IllegalArgumentException("station original token=secret");
        RuntimeException maintenanceFailure = new IllegalStateException("maintenance original URL/body");
        stationFailure.addSuppressed(maintenanceFailure);
        when(configuration.getVersion()).thenReturn("new-version");
        when(configuration.getStationCreditorInstitutionMap()).thenThrow(stationFailure);
        when(configuration.getMaintenanceStationMap()).thenThrow(maintenanceFailure);

        try (OperationLogScope ignored = OperationLogScope.open("cache-model-request")) {
            Map<String, String> context = MDC.getCopyOfContextMap();
            assertDoesNotThrow(() -> ConfigCacheData.setConfigData(configuration));
            FailureDiagnostics.technicalFailure(new LogEventWriter(logger),
                    new IllegalStateException("converted", stationFailure));
            assertEquals(context, MDC.getCopyOfContextMap());
        }

        verify(configuration).getMaintenanceStationMap();
        assertEquals("new-version", ConfigCacheData.getVersion());
        assertTrue(ConfigCacheData.isConfigDataNull());
        assertNull(ConfigCacheData.getCreditorInstitutionStation("creditor", "station"));
        assertNull(ConfigCacheData.getStationInMaintenance("station"));
        assertEquals(2, logs.list.size());
        assertDiagnostic(logs.list.get(0), stationFailure);
        assertDiagnostic(logs.list.get(1), maintenanceFailure);
        assertEquals("cache-model-request", logs.list.get(0).getMDCPropertyMap().get("requestId"));
    }

    @Test
    void outerFailureIsSwallowedWithoutMutatingExistingCacheOrInventingScope() {
        ConfigCacheData.setConfigData(validConfiguration("retained"));
        Object stations = ReflectionTestUtils.getField(ConfigCacheData.class, "stationCreditorInstitutionMap");
        Object maintenance = ReflectionTestUtils.getField(ConfigCacheData.class, "activeStationMaintenanceMap");
        ConfigDataV1 configuration = mock(ConfigDataV1.class);
        RuntimeException failure = new IllegalStateException(
                "original credentials/body\n" + "fully retained ".repeat(300),
                new IllegalArgumentException("root original"));
        StackTraceElement[] frames = new StackTraceElement[9];
        for (int i = 0; i < frames.length; i++) {
            frames[i] = new StackTraceElement("it.gov.pagopa.payments.Cache", "compute", "Cache.java", i + 1);
        }
        failure.setStackTrace(frames);
        failure.getCause().setStackTrace(frames);
        doAnswer(invocation -> {
            throw failure;
        }).when(configuration).getVersion();

        assertDoesNotThrow(() -> ConfigCacheData.setConfigData(configuration));

        assertEquals("retained", ConfigCacheData.getVersion());
        assertSame(stations, ReflectionTestUtils.getField(ConfigCacheData.class, "stationCreditorInstitutionMap"));
        assertSame(maintenance, ReflectionTestUtils.getField(ConfigCacheData.class, "activeStationMaintenanceMap"));
        verify(configuration, never()).getStationCreditorInstitutionMap();
        assertEquals(1, logs.list.size());
        assertDiagnostic(logs.list.get(0), failure);
        String stack = logs.list.get(0).getMDCPropertyMap().get("error.stack_trace");
        assertTrue(stack.contains("root original"));
        assertEquals(6, stack.lines().filter(line -> line.startsWith("\tat ")).count());
        assertTrue(stack.contains("6 frames omitted"));
        assertArrayEquals(frames, failure.getStackTrace());
        assertNull(OperationLogScope.currentOrNull());
        assertNull(logs.list.get(0).getMDCPropertyMap().get("operationId"));
    }

    @Test
    void failedStationMappingStillBuildsMaintenanceAndSubsequentValidDataRecovers() {
        ConfigDataV1 configuration = validConfiguration("failed");
        configuration.setStationCreditorInstitutionMap(null);

        assertDoesNotThrow(() -> ConfigCacheData.setConfigData(configuration));

        assertNull(ReflectionTestUtils.getField(ConfigCacheData.class, "stationCreditorInstitutionMap"));
        assertNotNull(ReflectionTestUtils.getField(ConfigCacheData.class, "activeStationMaintenanceMap"));
        assertEquals(1, logs.list.size());
        assertEquals("OPERATION_TECHNICAL_FAILED", logs.list.get(0).getMDCPropertyMap().get("event.code"));
        ConfigCacheData.setConfigData(validConfiguration("recovered"));
        assertFalse(ConfigCacheData.isConfigDataNull());
        assertEquals("recovered", ConfigCacheData.getVersion());
        assertEquals(1, logs.list.size());
    }

    @Test
    void failedMaintenanceMappingRetainsSuccessfulStationMappingAndDoesNotEscape() {
        ConfigDataV1 configuration = validConfiguration("maintenance-failed");
        configuration.setMaintenanceStationMap(null);

        assertDoesNotThrow(() -> ConfigCacheData.setConfigData(configuration));

        assertNotNull(ReflectionTestUtils.getField(ConfigCacheData.class, "stationCreditorInstitutionMap"));
        assertNull(ReflectionTestUtils.getField(ConfigCacheData.class, "activeStationMaintenanceMap"));
        assertTrue(ConfigCacheData.isConfigDataNull());
        assertEquals(1, logs.list.size());
        assertEquals("OPERATION_TECHNICAL_FAILED", logs.list.get(0).getMDCPropertyMap().get("event.code"));
        assertNull(logs.list.get(0).getMDCPropertyMap().get("ctx_details"));
        assertNull(logs.list.get(0).getThrowableProxy());
    }

    @Test
    void validDataHasNoPayloadDumpOrSuccessEventAndNullInputRemainsSwallowed() {
        ConfigCacheData.setConfigData(validConfiguration("valid"));
        assertTrue(logs.list.isEmpty());

        assertDoesNotThrow(() -> ConfigCacheData.setConfigData(null));

        assertEquals("valid", ConfigCacheData.getVersion());
        assertFalse(ConfigCacheData.isConfigDataNull());
        assertEquals(1, logs.list.size());
        assertNull(logs.list.get(0).getThrowableProxy());
        assertNull(logs.list.get(0).getArgumentArray());
    }

    private ConfigDataV1 validConfiguration(String version) {
        return ConfigDataV1.builder().version(version)
                .stationCreditorInstitutionMap(Map.of()).maintenanceStationMap(Map.of()).build();
    }

    private void assertDiagnostic(ILoggingEvent logged, Throwable failure) {
        assertEquals(Level.ERROR, logged.getLevel());
        assertEquals("OPERATION_TECHNICAL_FAILED", logged.getMDCPropertyMap().get("event.code"));
        assertEquals("Operation failed technically", logged.getFormattedMessage());
        assertEquals(failure.getClass().getName(), logged.getMDCPropertyMap().get("error.type"));
        assertEquals(failure.getMessage(), logged.getMDCPropertyMap().get("error.message"));
        assertTrue(logged.getMDCPropertyMap().get("error.stack_trace").contains(failure.getMessage()));
        assertNull(logged.getMDCPropertyMap().get("ctx_details"));
        assertNull(logged.getMDCPropertyMap().get("soapTechnicalErrorLogged"));
        assertNull(logged.getThrowableProxy());
        assertNull(logged.getArgumentArray());
    }
}
