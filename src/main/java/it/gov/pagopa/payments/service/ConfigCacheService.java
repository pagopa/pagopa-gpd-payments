package it.gov.pagopa.payments.service;

import feign.FeignException;
import it.gov.pagopa.payments.consumers.cache.model.CacheUpdateEvent;
import it.gov.pagopa.payments.logging.BackgroundExecutionLogging;
import it.gov.pagopa.payments.logging.TechnicalFailureLogging;
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

    public ApiConfigCacheClient apiConfigCacheClient;

    @Autowired
    public ConfigCacheService(ApiConfigCacheClient apiConfigCacheClient) {
        this.apiConfigCacheClient = apiConfigCacheClient;
    }

    @PostConstruct
    public void setConfigCacheData() {
        BackgroundExecutionLogging.runWithLogging("initializeConfigCache", execution -> {
            try {
                if (!getConfigCacheData()) {
                    execution.markFailureForLogging();
                }
            } catch (Exception e) {
                execution.markFailureForLogging();
                TechnicalFailureLogging.logTechnicalFailure(log, "Config cache initialization failed", e);
            }
        });
    }

    /**
     * Provides instance of the local cache data, if not yet provided,
     * it will call the checkAndUpdate method
     *
     * @return false if the required refresh absorbed a failure; true if successful or already populated
     */
    public boolean getConfigCacheData() {
        if (ConfigCacheData.isConfigDataNull()) {
            return checkAndUpdateCache(null);
        }
        return true;
    }

    /**
     * Executes a check and update process based on the update event (if provided). If the
     * input event is null it will always execute a call using the client instance
     *
     * If the event is provided, it will check if the version is obsolete, and will execute
     * the update only when a new cache version is passed through the update event
     *
     * @param cacheUpdateEvent contains version of the update event
     * @return false if a download or rebuild failure was absorbed; true for a successful update or skip
     */
    @SneakyThrows
    public boolean checkAndUpdateCache(CacheUpdateEvent cacheUpdateEvent) {

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
                TechnicalFailureLogging.logTechnicalFailure(log, "Config cache download failed", feignException);
                return false;
            }

            if (configDataV1 != null && (configDataV1.getVersion() == null || ConfigCacheData.getVersion() == null ||
                    configDataV1.getVersion().compareTo(ConfigCacheData.getVersion()) >= 0)) {

                boolean updated = ConfigCacheData.setConfigData(configDataV1);

                log.info("[apiconfig-cache-update] Config cache update attempt completed");
                return updated;
            }
        }
        return true;
    }
}