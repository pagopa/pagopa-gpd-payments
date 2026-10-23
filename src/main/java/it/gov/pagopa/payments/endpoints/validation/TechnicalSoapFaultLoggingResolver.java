package it.gov.pagopa.payments.endpoints.validation;

import it.gov.pagopa.payments.logging.TechnicalFailureLogging;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.server.EndpointExceptionResolver;
import org.springframework.ws.soap.server.endpoint.SimpleSoapExceptionResolver;

/** Diagnoses only faults handled here, after annotation resolvers and before the identical fallback. */
@Component
@Slf4j
public class TechnicalSoapFaultLoggingResolver implements EndpointExceptionResolver, Ordered {
  private final SimpleSoapExceptionResolver delegate = new SimpleSoapExceptionResolver();

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE - 1;
  }

  @Override
  public boolean resolveException(MessageContext context, Object endpoint, Exception failure) {
    boolean resolved = delegate.resolveException(context, endpoint, failure);
    if (resolved) {
      TechnicalFailureLogging.logTechnicalFailure(log, "SOAP request failed", failure);
    }
    return resolved;
  }
}
