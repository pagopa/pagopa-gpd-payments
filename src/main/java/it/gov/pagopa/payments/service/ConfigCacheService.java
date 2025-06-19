package it.gov.pagopa.payments.service;

import feign.FeignException;
import it.gov.pagopa.payments.consumers.cache.model.CacheUpdateEvent;
import it.gov.pagopa.payments.model.client.cache.ConfigCacheData;
import it.gov.pagopa.payments.model.client.cache.ConfigDataV1;
import it.gov.pagopa.payments.client.ApiConfigCacheClient;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
/**
 * Service to manage local instance of the config cache, to contain and update
 * based on retrieved data
 */
@Slf4j
@Service
public class ConfigCacheService {

    @Autowired
    public ApiConfigCacheClient apiConfigCacheClient;

    @PostConstruct
    public void setConfigCacheData() {
        try {
            getConfigCacheData();
            log.info("[PostConstruct] Successful getConfigCacheData, version: {}", ConfigCacheData.getVersion());
        } catch (Exception e) {
            log.error("[PostConstruct] Exception while setConfigCacheData: ", e);
        }
    }

    /**
     * Provides instance of the local cache data, if not yet provided,
     * it will call the checkAndUpdate method
     */
    public void getConfigCacheData() {
        if (ConfigCacheData.isConfigDataNull()) {
            checkAndUpdateCache(null);
        }
    }

    /**
     * Executes a check and update process based on the update event (if provided). If the
     * input event is null it will always execute a call using the client instance
     *
     * If the event is provided, it will check if the version is obsolete, and will execute
     * the update only when a new cache version is passed through the update event
     *
     * @param cacheUpdateEvent contains version of the update event
     */
    @SneakyThrows
    public void checkAndUpdateCache(CacheUpdateEvent cacheUpdateEvent) {

        if (ConfigCacheData.isConfigDataNull() || cacheUpdateEvent == null ||
                ConfigCacheData.getVersion() == null ||
                !cacheUpdateEvent.getCacheVersion().equals(ConfigCacheData.getVersion()) ||
                cacheUpdateEvent.getVersion().compareTo(ConfigCacheData.getVersion()) > 0)
        {

            if (ConfigCacheData.isConfigDataNull() && cacheUpdateEvent != null) {
                ConfigCacheData.setVersion(cacheUpdateEvent.getVersion());
            }

            ConfigDataV1 configDataV1;

            try {
                configDataV1 = apiConfigCacheClient.getCacheByKeys("creditorInstitutionStations,maintenanceStations,version");
            } catch (FeignException feignException) {
                log.error("[apiconfig-cache-update] Feign Exception while download cache {}.", feignException.getMessage());
                return;
            }

            if (configDataV1 != null && (configDataV1.getVersion() == null || ConfigCacheData.getVersion() == null ||
                    configDataV1.getVersion().compareTo(ConfigCacheData.getVersion()) >= 0)) {

                ConfigCacheData.setConfigData(configDataV1);

                log.info("[apiconfig-cache-update] Cache version: {}", ConfigCacheData.getVersion());
            }
        }
    }
}