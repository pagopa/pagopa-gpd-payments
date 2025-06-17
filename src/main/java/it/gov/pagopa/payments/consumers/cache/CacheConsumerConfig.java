package it.gov.pagopa.payments.consumers.cache;

import it.gov.pagopa.payments.consumers.cache.model.CacheUpdateEvent;
import it.gov.pagopa.payments.service.ConfigCacheService;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

import java.util.function.Consumer;

@Configuration("CacheEventHandler")
@Slf4j
public class CacheConsumerConfig {
    // todo config application properties
    @Autowired
    public ConfigCacheService configCacheService;

    @Bean("ConfigCacheEventConsumer")
    @NonNull
    public Consumer<Message<CacheUpdateEvent>> consumerCacheEvent() {
        return this::handleCacheEvent;
    }

    private void handleCacheEvent(Message<CacheUpdateEvent> cacheEventMessage) {
        try {
            configCacheService.checkAndUpdateCache(cacheEventMessage.getPayload());
        } catch (Exception e) {
            throw e;
        }
    }
}