package it.gov.pagopa.payments.service;

import feign.FeignException;
import it.gov.pagopa.payments.consumers.cache.model.CacheUpdateEvent;
import it.gov.pagopa.payments.model.client.cache.ConfigCacheData;
import it.gov.pagopa.payments.model.client.cache.ConfigDataV1;
import it.gov.pagopa.payments.client.ApiConfigCacheClient;
import it.gov.pagopa.payments.logging.EventDetails;
import it.gov.pagopa.payments.logging.LogEventWriter;
import it.gov.pagopa.payments.logging.FailureDiagnostics;
import it.gov.pagopa.payments.logging.OperationLogScope;
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

    private static final LogEventWriter LOG_EVENTS = new LogEventWriter(log);

    public ApiConfigCacheClient apiConfigCacheClient;

    @Autowired
    public ConfigCacheService(ApiConfigCacheClient apiConfigCacheClient) {
        this.apiConfigCacheClient = apiConfigCacheClient;
    }

    @PostConstruct
    public void setConfigCacheData() {
        try (OperationLogScope ignored = OperationLogScope.open(null, "initializeConfigCache")) {
            try {
                if (ConfigCacheData.isConfigDataNull()) {
                    checkAndUpdateCache(null);
                }
            } catch (Exception e) {
                FailureDiagnostics.absorbedFailure(LOG_EVENTS, e);
            }
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

            boolean usableCache = !ConfigCacheData.isConfigDataNull();
            long startedNanos = System.nanoTime();
            try {
                configDataV1 = apiConfigCacheClient.getCacheByKeys("creditorInstitutionStations,maintenanceStations,version");
            } catch (FeignException feignException) {
                diagnoseDownloadFailure(startedNanos, feignException, usableCache);
                return;
            } catch (RuntimeException failure) {
                FailureDiagnostics.dependencyFailure(EventDetails.Dependency.API_CONFIG_CACHE,
                        EventDetails.DependencyOperation.REFRESH_CONFIGURATION, startedNanos, failure);
                throw failure;
            }

            if (configDataV1 == null) {
                FailureDiagnostics.absorbedFailure(
                        LOG_EVENTS, new IllegalStateException("Configuration cache response is null"));
            }

            if (configDataV1 != null && (configDataV1.getVersion() == null || ConfigCacheData.getVersion() == null ||
                    configDataV1.getVersion().compareTo(ConfigCacheData.getVersion()) >= 0)) {

                ConfigCacheData.setConfigData(configDataV1);
            }
        }
    }

    private void diagnoseDownloadFailure(long startedNanos, RuntimeException failure, boolean usableCache) {
        Integer status = failure instanceof FeignException feign && feign.status() > 0
                ? feign.status() : null;
        EventDetails.DependencyCall details = new EventDetails.DependencyCall(
                EventDetails.Dependency.API_CONFIG_CACHE,
                EventDetails.DependencyOperation.REFRESH_CONFIGURATION, EventDetails.Outcome.KO,
                status, (System.nanoTime() - startedNanos) / 1_000_000, null,
                usableCache ? EventDetails.Reason.USABLE_CACHE_RETAINED : null, null);
        LOG_EVENTS.dependencyFailed(details, usableCache
                ? LogEventWriter.FailureDisposition.RECOVERABLE
                : LogEventWriter.FailureDisposition.TERMINAL, failure);
        if (!usableCache && OperationLogScope.currentOrNull() != null) {
            OperationLogScope.current().recordDiagnosis();
        }
    }
}