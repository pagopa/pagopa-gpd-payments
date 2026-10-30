package it.gov.pagopa.payments.config.feign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import feign.Logger;
import feign.RequestTemplate;
import it.gov.pagopa.payments.config.LogContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class AuthFeignConfigTest {
  @Test
  void interceptorPropagatesCorrelationAndSubscriptionKeyWithoutChangingContext() {
    Map<String, String> previous = MDC.getCopyOfContextMap();
    try {
      MDC.clear();
      MDC.put(LogContext.CORRELATION_ID, "request-123");
      GPDFeignConfig config = new GPDFeignConfig("test-key");
      RequestTemplate request = new RequestTemplate();

      config.requestIdInterceptor().apply(request);

      assertEquals(List.of("request-123"),
          List.copyOf(request.headers().get(AuthFeignConfig.HEADER_REQUEST_ID)));
      assertEquals(List.of("test-key"),
          List.copyOf(request.headers().get(AuthFeignConfig.HEADER_SUBSCRIBTION_KEY)));
      assertEquals("request-123", MDC.get(LogContext.CORRELATION_ID));
    } finally {
      LogContext.restore(previous);
    }
  }

  @Test
  void interceptorDoesNotInventCorrelationOutsideARequest() {
    Map<String, String> previous = MDC.getCopyOfContextMap();
    try {
      MDC.clear();
      GPDFeignConfig config = new GPDFeignConfig("test-key");
      RequestTemplate request = new RequestTemplate();

      config.requestIdInterceptor().apply(request);

      assertTrue(request.headers()
          .getOrDefault(AuthFeignConfig.HEADER_REQUEST_ID, List.of()).isEmpty());
    } finally {
      LogContext.restore(previous);
    }
  }

  @Test
  void configurationUsesStructuredObserverInsteadOfWireLogger() {
    GPDFeignConfig config = new GPDFeignConfig("test-key");

    assertInstanceOf(FeignLoggingCapability.class, config.feignLoggingCapability());
    assertInstanceOf(Logger.NoOpLogger.class, config.feignLogger());
  }
}
