package it.gov.pagopa.payments.config.feign;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ApiConfigFeignConfig extends AuthFeignConfig {

  private static final String APICONFIG_SUBKEY_PLACEHOLDER = "${config-cache.subscription-key}";

  @Autowired
  public ApiConfigFeignConfig(@Value(APICONFIG_SUBKEY_PLACEHOLDER) String subscriptionKey) {
    this.subscriptionKey = subscriptionKey;
  }
}
