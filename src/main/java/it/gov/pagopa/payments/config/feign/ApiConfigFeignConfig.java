package it.gov.pagopa.payments.config.feign;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

@Component
public class ApiConfigFeignConfig extends AuthFeignConfig {

    private static final String APICONFIG_SUBKEY_PLACEHOLDER = "${apiconfig.subscription-key}";

    @Autowired
    public ApiConfigFeignConfig(@Value(APICONFIG_SUBKEY_PLACEHOLDER) String subscriptionKey) {
        this.subscriptionKey = subscriptionKey;
    }
}
