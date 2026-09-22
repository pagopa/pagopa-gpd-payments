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
      // expected negative business outcome: no stack trace, the fault code is the useful signal
      log.info(
          "Request rejected [faultCode={}]",
          partnerValidationException.getError().getFaultCode());
      throw partnerValidationException;
    }

    log.error("an Error occurred: {}", ex.getMessage(), ex);
    return false;
  }
}
