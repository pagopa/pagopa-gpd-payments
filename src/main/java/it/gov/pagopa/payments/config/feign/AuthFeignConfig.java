package it.gov.pagopa.payments.config.feign;

import feign.RequestInterceptor;
import feign.Logger;
import it.gov.pagopa.payments.config.LogContext;
import it.gov.pagopa.payments.utils.CommonUtil;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;

public abstract class AuthFeignConfig {

  protected String subscriptionKey;

  static final String HEADER_REQUEST_ID = LogContext.HEADER_REQUEST_ID;

  static final String HEADER_SUBSCRIBTION_KEY = CommonUtil.SUBSCRIPTION_KEY_HEADER;

  @Bean
  public RequestInterceptor requestIdInterceptor() {
    return requestTemplate ->
        requestTemplate
            .header(HEADER_REQUEST_ID, MDC.get(LogContext.CORRELATION_ID))
            .header(HEADER_SUBSCRIBTION_KEY, subscriptionKey);
  }

  @Bean
  public Logger feignLogger() {
    return new Logger.NoOpLogger();
  }
}
