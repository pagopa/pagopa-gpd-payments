package it.gov.pagopa.payments.consumers.cache;

import it.gov.pagopa.payments.consumers.cache.model.CacheUpdateEvent;
import it.gov.pagopa.payments.service.ConfigCacheService;
import it.gov.pagopa.payments.config.LogContext;
import it.gov.pagopa.payments.utils.SchedulerUtils;
import java.util.Map;
import java.util.UUID;
import lombok.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.slf4j.MDC;

import java.util.function.Consumer;

@Configuration("CacheConsumerConfig")
public class CacheConsumerConfig {
    private static final String CACHE_EVENT_CONSUMER = "ConfigCacheEventConsumer";

    public ConfigCacheService configCacheService;

    @Autowired
    public CacheConsumerConfig(ConfigCacheService configCacheService) {
        this.configCacheService = configCacheService;
    }

    @Bean(CACHE_EVENT_CONSUMER)
    // This name is the existing Spring Cloud Function and Stream binding contract.
    @SuppressWarnings("java:S6830")
    @NonNull
    public Consumer<Message<CacheUpdateEvent>> consumerCacheEvent() {
        return this::handleCacheEvent;
    }

    public void handleCacheEvent(Message<CacheUpdateEvent> cacheEventMessage) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        long started = System.nanoTime();
        try {
            MDC.clear();
            MDC.put(LogContext.CORRELATION_ID, UUID.randomUUID().toString());
            configCacheService.checkAndUpdateCache(cacheEventMessage.getPayload());
            SchedulerUtils.logCompletion(started, null);
        } catch (Exception e) {
            SchedulerUtils.logCompletion(started, e);
            throw e;
        } finally {
            LogContext.restore(previous);
        }
    }
}