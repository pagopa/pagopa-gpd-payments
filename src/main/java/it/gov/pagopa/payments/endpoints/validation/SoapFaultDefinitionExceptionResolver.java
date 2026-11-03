package it.gov.pagopa.payments.endpoints.validation;

import it.gov.pagopa.payments.endpoints.validation.exceptions.PartnerValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.server.EndpointExceptionResolver;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class SoapFaultDefinitionExceptionResolver implements EndpointExceptionResolver {

  public boolean resolveException(MessageContext messageContext, Object endpoint, Exception ex) {

    if (ex instanceof PartnerValidationException partnerValidationException) {
      throw partnerValidationException;
    }
    log.error("SOAP endpoint failed", ex);
    return false;
  }
}
