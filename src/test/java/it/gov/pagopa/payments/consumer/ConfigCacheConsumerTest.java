package it.gov.pagopa.payments.consumer;

import it.gov.pagopa.payments.consumers.cache.CacheConsumerConfig;
import it.gov.pagopa.payments.consumers.cache.model.CacheUpdateEvent;
import it.gov.pagopa.payments.service.ConfigCacheService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.Message;

import java.util.function.Consumer;

import static org.assertj.core.api.Fail.fail;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@Slf4j
@SpringBootTest
public class ConfigCacheConsumerTest {
    @InjectMocks
    private CacheConsumerConfig cacheConsumerConfig;

    @Mock
    private ConfigCacheService configCacheService;

    @Mock
    private Message<CacheUpdateEvent> mockCacheEventMessage;

    @Mock
    private CacheUpdateEvent cacheUpdateEvent;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testConsumerCacheEventBeanCreation() {
        // Ensure that the bean is created successfully and can consume the message
        Consumer<Message<CacheUpdateEvent>> consumer = cacheConsumerConfig.consumerCacheEvent();
        assertNotNull(consumer);
    }

    @Test
    void testHandleCacheEvent_Success() {
        // Arrange
        when(mockCacheEventMessage.getPayload()).thenReturn(cacheUpdateEvent);

        // Act
        cacheConsumerConfig.handleCacheEvent(mockCacheEventMessage);

        // Assert
        verify(configCacheService, times(1)).checkAndUpdateCache(cacheUpdateEvent);
    }

    @Test
    void testHandleCacheEvent_ExceptionHandling() {
        // Arrange
        when(mockCacheEventMessage.getPayload()).thenReturn(cacheUpdateEvent);
        doThrow(new RuntimeException("Error updating cache")).when(configCacheService).checkAndUpdateCache(any());

        // Act & Assert
        try {
            cacheConsumerConfig.handleCacheEvent(mockCacheEventMessage);
            fail("Expected exception to be thrown");
        } catch (Exception e) {
            // Expected exception to be thrown
            assertEquals("Error updating cache", e.getMessage());
        }
    }

    @Test
    void testConsumerCacheEvent_ShouldCallHandleCacheEvent() {
        // Arrange
        Consumer<Message<CacheUpdateEvent>> consumer = cacheConsumerConfig.consumerCacheEvent();
        when(mockCacheEventMessage.getPayload()).thenReturn(cacheUpdateEvent);

        // Act
        consumer.accept(mockCacheEventMessage);

        // Assert
        verify(configCacheService, times(1)).checkAndUpdateCache(cacheUpdateEvent);
    }
}
