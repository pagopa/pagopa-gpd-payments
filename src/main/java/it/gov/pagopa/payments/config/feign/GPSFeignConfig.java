package it.gov.pagopa.payments.config.feign;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GPSFeignConfig extends AuthFeignConfig {

    private static final String GPS_SUBKEY_PLACEHOLDER = "${gps.subscription-key}";

    @Autowired
    public GPSFeignConfig(@Value(GPS_SUBKEY_PLACEHOLDER) String subscriptionKey) {
        this.subscriptionKey = subscriptionKey;
    }
}
