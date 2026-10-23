package it.gov.pagopa.payments.consumers.cache;

import it.gov.pagopa.payments.consumers.cache.model.CacheUpdateEvent;
import it.gov.pagopa.payments.logging.BackgroundExecutionLogging;
import it.gov.pagopa.payments.logging.TechnicalFailureLogging;
import it.gov.pagopa.payments.service.ConfigCacheService;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

import java.util.function.Consumer;

@Configuration("CacheConsumerConfig")
@Slf4j
public class CacheConsumerConfig {

    public ConfigCacheService configCacheService;

    @Autowired
    public CacheConsumerConfig(ConfigCacheService configCacheService) {
        this.configCacheService = configCacheService;
    }

    @Bean("ConfigCacheEventConsumer")
    @NonNull
    public Consumer<Message<CacheUpdateEvent>> consumerCacheEvent() {
        return this::handleCacheEvent;
    }

    // Log fatal failures at the consumer boundary, then propagate the original Error unchanged.
    @SuppressWarnings("java:S1181")
    public void handleCacheEvent(Message<CacheUpdateEvent> cacheEventMessage) {
        BackgroundExecutionLogging.runWithLogging("handleCacheEvent", execution -> {
            try {
                if (!configCacheService.checkAndUpdateCache(cacheEventMessage.getPayload())) {
                    execution.markFailureForLogging();
                }
            } catch (RuntimeException | Error failure) {
                TechnicalFailureLogging.logTechnicalFailure(log, "Cache event processing failed", failure);
                throw failure;
            }
        });
    }
}