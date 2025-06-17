package it.gov.pagopa.payments.service;

import feign.FeignException;
import it.gov.pagopa.payments.config.feign.ApiConfigFeignConfig;
import it.gov.pagopa.payments.model.client.cache.ConfigDataV1;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.web.bind.annotation.*;

@FeignClient(value = "cache", url = "${service.cache.host}", configuration = ApiConfigFeignConfig.class)
public interface ApiConfigCacheClient {

    @Retryable(
            exclude = FeignException.FeignClientException.class,
            maxAttemptsExpression = "${retry.maxAttempts}",
            backoff = @Backoff(delayExpression = "${retry.maxDelay}", multiplierExpression = "${retry.multiplier}"))
    @GetMapping(value = "/cache")
    ConfigDataV1 getCacheByKeys(@RequestParam("keys") String keys);
}
