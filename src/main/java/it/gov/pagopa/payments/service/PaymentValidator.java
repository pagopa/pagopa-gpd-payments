package it.gov.pagopa.payments.service;

import feign.FeignException;
import it.gov.pagopa.payments.endpoints.validation.exceptions.PartnerValidationException;
import it.gov.pagopa.payments.model.PaaErrorEnum;
import it.gov.pagopa.payments.service.ApiConfigClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class PaymentValidator {

  @Value("${pt.id_intermediario}")
  private String ptIdIntermediario;

  @Value("${pt.id_stazione}")
  private String ptIdStazione;

  @Autowired private ApiConfigClient apiConfigClient;

  public void isAuthorize(
      String ptIdDominioReq, String ptIdIntermediarioReq, String ptIdStazioneReq)
      throws PartnerValidationException {
    try {
      apiConfigClient.getOrganization(ptIdStazioneReq, ptIdDominioReq);
    } catch (Exception e) {
      log.error(
          "[isAuthorize ERROR] error during API Config call [station = "
              + ptIdStazioneReq
              + "; idPA = "
              + ptIdDominioReq
              + "]",
          e);
      if (e instanceof FeignException.FeignClientException) {
        throw new PartnerValidationException(PaaErrorEnum.PAA_ID_DOMINIO_ERRATO);
      } else {
        throw new PartnerValidationException(PaaErrorEnum.PAA_SYSTEM_ERROR);
      }
    }
  }
}