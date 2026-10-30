package it.gov.pagopa.payments.endpoints.validation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import it.gov.pagopa.payments.config.LogCapture;
import it.gov.pagopa.payments.config.LogContext;
import java.util.Map;
import javax.xml.soap.SOAPException;
import javax.xml.soap.SOAPMessage;
import javax.xml.soap.SOAPPart;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.soap.saaj.SaajSoapMessage;
import org.xml.sax.SAXParseException;

class SoapValidatingInterceptorTest {
  @Test
  void emptyResponseValidationErrorsStopChainWithoutLoggingAnError() {
    try (LogCapture logs = new LogCapture(SoapValidatingInterceptor.class)) {
      assertFalse(new SoapValidatingInterceptor().handleResponseValidationErrors(
          mock(MessageContext.class), new SAXParseException[0]));
      assertTrue(logs.events().isEmpty());
    }
  }

  @Test
  void normalizationFailureIsLoggedWithoutPreventingResponseHandling() throws Exception {
    SOAPException failure = new SOAPException("Envelope unavailable");
    SOAPPart part = mock(SOAPPart.class);
    SOAPMessage message = mock(SOAPMessage.class);
    SaajSoapMessage response = mock(SaajSoapMessage.class);
    MessageContext context = mock(MessageContext.class);
    when(message.getSOAPPart()).thenReturn(part);
    when(part.getEnvelope()).thenThrow(failure);
    when(response.getSaajMessage()).thenReturn(message);
    when(context.getResponse()).thenReturn(response);
    SoapValidatingInterceptor interceptor = new SoapValidatingInterceptor();
    interceptor.setValidateResponse(false);

    try (LogCapture logs = new LogCapture(SoapValidatingInterceptor.class)) {
      assertTrue(interceptor.handleResponse(context, new Object()));
      assertEquals(1, logs.events().size());
      JsonNode event = logs.events().get(0);
      assertEquals("WARN", event.path(LogContext.LOG_LEVEL).asText());
      assertEquals("SOAP response normalization failed", event.path(LogContext.MESSAGE).asText());
      assertEquals(SOAPException.class.getName(), event.path(LogContext.ERROR_TYPE).asText());
      assertEquals("Envelope unavailable", event.path(LogContext.ERROR_MESSAGE).asText());
    }
  }

  @Test
  void responseValidationEmitsOneErrorWithAllOriginalMessagesAndStopsChain() {
    Map<String, String> previous = MDC.getCopyOfContextMap();
    try (LogCapture logs = new LogCapture(SoapValidatingInterceptor.class)) {
      MDC.clear();
      MDC.put(LogContext.CORRELATION_ID, "request-123");
      MDC.put(LogContext.METHOD, "paGetPaymentV2");
      SAXParseException[] errors = {
          new SAXParseException("cvc-pattern-valid: invalid '{{ec-test-2}}'", null),
          new SAXParseException("cvc-type.3.1.3: fiscalCodePA is not valid", null)
      };
      assertFalse(new SoapValidatingInterceptor()
          .handleResponseValidationErrors(mock(MessageContext.class), errors));
      assertEquals(1, logs.events().size());
      JsonNode event = logs.events().get(0);
      assertEquals("ERROR", event.path(LogContext.LOG_LEVEL).asText());
      assertEquals("SOAP response XML validation failed", event.path(LogContext.MESSAGE).asText());
      assertEquals(LogContext.STATUS_KO, event.path(LogContext.STATUS).asText());
      assertEquals("paGetPaymentV2", event.path(LogContext.METHOD).asText());
      assertEquals("request-123", event.path(LogContext.CORRELATION_ID).asText());
      assertEquals(errors[0].getMessage() + "\n" + errors[1].getMessage(),
          event.path(LogContext.ERROR_MESSAGE).asText());
      assertEquals(2, event.path(LogContext.CTX_DETAILS).path("validation_error_count").intValue());
      assertEquals(1, logs.lines().get(0).chars().filter(character -> character == '\n').count());
    } finally {
      LogContext.restore(previous);
    }
  }
}
