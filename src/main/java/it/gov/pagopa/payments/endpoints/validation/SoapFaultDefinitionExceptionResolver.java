package it.gov.pagopa.payments.endpoints.validation;

import it.gov.pagopa.payments.endpoints.validation.exceptions.PartnerValidationException;
import it.gov.pagopa.payments.logging.OperationLogScope;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.server.EndpointExceptionResolver;

@Component
public class SoapFaultDefinitionExceptionResolver implements EndpointExceptionResolver, Ordered {

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }

  @Override
  public boolean resolveException(MessageContext messageContext, Object endpoint, Exception ex) {

    if (ex instanceof PartnerValidationException partnerValidationException) {
      throw partnerValidationException;
    }
    OperationLogScope scope = OperationLogScope.currentOrNull();
    if (scope != null) {
      scope.rememberBoundaryFailure(ex);
    }
    return false;
  }
}
