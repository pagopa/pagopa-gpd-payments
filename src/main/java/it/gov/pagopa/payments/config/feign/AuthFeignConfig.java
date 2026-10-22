package it.gov.pagopa.payments.config.feign;

import feign.Capability;
import feign.RequestInterceptor;
import it.gov.pagopa.payments.logging.FeignDependencyLoggingCapability;
import it.gov.pagopa.payments.logging.HttpRequestLogContext;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;

public abstract class AuthFeignConfig {

  protected String subscriptionKey;

  static final String HEADER_REQUEST_ID = HttpRequestLogContext.REQUEST_ID_HEADER;

  static final String HEADER_SUBSCRIBTION_KEY = "Ocp-Apim-Subscription-Key";

  /** Structured dependency events replace wire logging, including FULL header/body dumps. */
  @Bean
  public feign.Logger feignLogger() {
    return new feign.Logger.NoOpLogger();
  }

  @Bean
  public Capability dependencyLoggingCapability() {
    return new FeignDependencyLoggingCapability();
  }

  @Bean
  public RequestInterceptor requestIdInterceptor() {
    return requestTemplate ->
        requestTemplate
            .header(HEADER_REQUEST_ID, MDC.get(HttpRequestLogContext.CORRELATION_ID))
            .header(HEADER_SUBSCRIBTION_KEY, subscriptionKey);
  }
}
