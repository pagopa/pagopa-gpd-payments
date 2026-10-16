package it.gov.pagopa.payments.consumers.cache;

import it.gov.pagopa.payments.consumers.cache.model.CacheUpdateEvent;
import it.gov.pagopa.payments.logging.LogEventWriter;
import it.gov.pagopa.payments.logging.FailureDiagnostics;
import it.gov.pagopa.payments.logging.OperationLogScope;
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

    private static final LogEventWriter LOG_EVENTS = new LogEventWriter(log);

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

    public void handleCacheEvent(Message<CacheUpdateEvent> cacheEventMessage) {
        try (OperationLogScope ignored = OperationLogScope.open(null, "handleCacheEvent")) {
            try {
                configCacheService.checkAndUpdateCache(cacheEventMessage.getPayload());
            } catch (Exception e) {
                FailureDiagnostics.technicalFailure(LOG_EVENTS, e);
                throw e;
            }
        }
    }
}