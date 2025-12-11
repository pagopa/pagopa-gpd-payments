package it.gov.pagopa.payments.service;

import it.gov.pagopa.payments.client.ApiConfigCacheClient;
import it.gov.pagopa.payments.consumers.cache.model.CacheUpdateEvent;
import it.gov.pagopa.payments.model.client.cache.ConfigCacheData;
import it.gov.pagopa.payments.model.client.cache.ConfigDataV1;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.SpringBootTest;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;

import feign.FeignException;

@ExtendWith(MockitoExtension.class)
@Slf4j
@SpringBootTest
class ConfigCacheServiceTest {

    @InjectMocks
    private ConfigCacheService configCacheService;

    @Mock
    private ApiConfigCacheClient apiConfigCacheClient;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    // Test checkAndUpdateCache when FeignException is thrown
    @Test
    void testCheckAndUpdateCache_ApiFailure() {
        // Given
        ConfigCacheData.setConfigData(null); // Cache is null
        CacheUpdateEvent cacheUpdateEvent = getCacheUpdateEvent("787716055495419");
        doThrow(FeignException.class).when(apiConfigCacheClient).getCacheByKeys(anyString());

        // When
        configCacheService.checkAndUpdateCache(cacheUpdateEvent);

        // Then
        // Verify that the error was logged, the cache update call should be skipped
        verify(apiConfigCacheClient, times(1)).getCacheByKeys("creditorInstitutionStations,maintenanceStations,version");
        verifyNoMoreInteractions(apiConfigCacheClient);
    }

    // Test checkAndUpdateCache when call setConfigCacheData @PostConstruct
    @Test
    void testCheckAndUpdateCache_PostConstruct() {
        try (MockedStatic<ConfigCacheData> mockedConfigData = mockStatic(ConfigCacheData.class)) {
            // Given
            ConfigCacheData.setConfigData(null); // Cache is null
            mockedConfigData.when(ConfigCacheData::getVersion).thenReturn("787716055495419"); // Cache is not null
            ConfigDataV1 configDataV1 = getConfigDataV1("787716055495418");
            when(apiConfigCacheClient.getCacheByKeys(anyString())).thenReturn(configDataV1);
            when(apiConfigCacheClient.getCacheByKeys(anyString())).thenReturn(configDataV1);

            // When
            configCacheService.getConfigCacheData();

            // Then
            // Assert: Verify that checkAndUpdateCache was called
            mockedConfigData.verify(() -> configCacheService.checkAndUpdateCache(null), times(1));
        }
    }


    // Test checkAndUpdateCache when the version is up-to-date
    @Test
    void testCheckAndUpdateCache_CacheVersionUpToDate() {
        try (MockedStatic<ConfigCacheData> mockedConfigData = mockStatic(ConfigCacheData.class)) {
            // Given
            mockedConfigData.when(ConfigCacheData::getVersion).thenReturn("787716055495419"); // Cache is not null
            CacheUpdateEvent cacheUpdateEvent = this.getCacheUpdateEvent("787716055495418");
            ConfigDataV1 configDataV1 = getConfigDataV1("787716055495418");
            when(apiConfigCacheClient.getCacheByKeys(anyString())).thenReturn(configDataV1);

            // When
            configCacheService.checkAndUpdateCache(cacheUpdateEvent);

            // Then
            verify(apiConfigCacheClient, times(1)).getCacheByKeys("creditorInstitutionStations,maintenanceStations,version");
            // Assert: Verify that setConfigData was NOT called
            mockedConfigData.verify(() -> ConfigCacheData.setConfigData(any()), times(0));
        }
    }

    // Test checkAndUpdateCache when the version is out-to-date, so it must be updated
    @Test
    void testCheckAndUpdateCache_CacheVersionToUpToDate() {
        try (MockedStatic<ConfigCacheData> mockedConfigData = mockStatic(ConfigCacheData.class)) {
            // Given
            mockedConfigData.when(ConfigCacheData::getVersion).thenReturn("787716055495418"); // Cache is not null
            CacheUpdateEvent cacheUpdateEvent = this.getCacheUpdateEvent("787716055495419");
            ConfigDataV1 configDataV1 = getConfigDataV1("787716055495419");
            when(apiConfigCacheClient.getCacheByKeys(anyString())).thenReturn(configDataV1);

            // When
            configCacheService.checkAndUpdateCache(cacheUpdateEvent);

            // Then
            verify(apiConfigCacheClient, times(1)).getCacheByKeys("creditorInstitutionStations,maintenanceStations,version");
            // Assert: Verify that setConfigData was called
            mockedConfigData.verify(() -> ConfigCacheData.setConfigData(any()), times(1));
        }
    }


    private CacheUpdateEvent getCacheUpdateEvent(String version) {
        return CacheUpdateEvent.builder()
                .cacheVersion(version)
                .timestamp("1750337844.268590000")
                .version(version).build();
    }

    private ConfigDataV1 getConfigDataV1(String version) {
        return ConfigDataV1.builder()
                .stationCreditorInstitutionMap(null)
                .maintenanceStationMap(null)
                .version(version)
                .build();
    }
}
