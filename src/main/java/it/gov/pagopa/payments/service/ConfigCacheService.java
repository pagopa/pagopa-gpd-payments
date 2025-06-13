package it.gov.pagopa.payments.service;

import it.gov.pagopa.payments.consumers.cache.model.CacheUpdateEvent;
import it.gov.pagopa.payments.model.client.cache.ConfigCacheData;
import it.gov.pagopa.payments.model.client.cache.ConfigDataV1;
import lombok.SneakyThrows;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
/**
 * Service to manage local instance of the config cache, to contain and update
 * based on retrieved data
 */
@Service
public class ConfigCacheService {

    public ApiConfigCacheClient apiConfigCacheClient;

    private ConfigCacheData configCacheData;

    @PostConstruct
    public void setConfigCacheData() {
        try {
            getConfigCacheData();
        } catch (Exception e) {
        }
    }

    /**
     * Provides instance of the local cache data, if not yet provided,
     * it will call the checkAndUpdate method
     * @return version of local instance of the configCacheData
     */
    public String getConfigCacheData() {
        return this.configCacheData != null && !this.configCacheData.isNull() ?
                this.configCacheData.getCacheVersion() :
                checkAndUpdateCache(null).getCacheVersion();
    }

    /**
     * Executes a check and update process based on the update event (if provided). If the
     * input event is null it will always execute a call using the client instance
     *
     * If the event is provided, it will check if the version is obsolete, and will execute
     * the update only when a new cache version is passed through the update event
     *
     * @param cacheUpdateEvent contains version of the update event
     * @return instance of the configCacheData
     */
    @SneakyThrows
    public ConfigCacheData checkAndUpdateCache(CacheUpdateEvent cacheUpdateEvent) {

        if (configCacheData == null || cacheUpdateEvent == null ||
                configCacheData.getVersion() == null ||
                configCacheData.getCacheVersion() == null ||
                !cacheUpdateEvent.getCacheVersion().equals(configCacheData.getCacheVersion()) ||
                cacheUpdateEvent.getVersion().compareTo(configCacheData.getVersion()) > 0)
        {

            if (configCacheData == null) {
                configCacheData = ConfigCacheData.builder().cacheVersion(
                                cacheUpdateEvent != null ?
                                        cacheUpdateEvent.getCacheVersion() :
                                        null
                        )
                        .version(cacheUpdateEvent != null ?
                                cacheUpdateEvent.getVersion() :
                                null)
                        .build();
            }

            ConfigDataV1 configDataV1 = apiConfigCacheClient.getCacheByKeys("creditorInstitutionStations,maintenanceStations,version");

            if (configDataV1.getVersion() == null || configCacheData.getVersion() == null ||
                    configDataV1.getVersion().compareTo(configCacheData.getVersion()) >= 0) {
                configCacheData.set(configDataV1);
                if (configDataV1.getVersion() != null) {
                    configCacheData.setVersion(configDataV1.getVersion());
                }
                configCacheData.setCacheVersion(cacheUpdateEvent != null &&
                        cacheUpdateEvent.getCacheVersion() != null ?
                        cacheUpdateEvent.getCacheVersion() : null);
            }

        }

        return this.configCacheData;
    }
}