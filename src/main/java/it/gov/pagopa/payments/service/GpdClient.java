package it.gov.pagopa.payments.service;

import feign.FeignException;
import it.gov.pagopa.payments.config.feign.GPDFeignConfig;
import it.gov.pagopa.payments.model.PaymentOptionModel;
import it.gov.pagopa.payments.model.PaymentOptionModelResponse;
import it.gov.pagopa.payments.model.PaymentsModelResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(value = "gpd", url = "${service.gpd.host}", configuration = GPDFeignConfig.class)
public interface GpdClient {

  @Retryable(
      exclude = FeignException.FeignClientException.class,
      maxAttemptsExpression = "${retry.maxAttempts}",
      backoff = @Backoff(delayExpression = "${retry.maxDelay}"))
  @GetMapping(value = "/organizations/{organizationfiscalcode}/paymentoptions/{nav}")
  PaymentsModelResponse getPaymentOption(
      @PathVariable("organizationfiscalcode") String organizationFiscalCode,
      @PathVariable("nav") String nav);

  @Retryable(
      exclude = FeignException.FeignClientException.class,
      maxAttemptsExpression = "${retry.maxAttempts}",
      backoff = @Backoff(delayExpression = "${retry.maxDelay}"))
  @PostMapping(
      value = "/organizations/{organizationfiscalcode}/paymentoptions/{nav}/pay",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  PaymentOptionModelResponse receiptPaymentOption(
      @PathVariable("organizationfiscalcode") String organizationFiscalCode,
      @PathVariable("nav") String nav,
      @RequestBody PaymentOptionModel body);
}
