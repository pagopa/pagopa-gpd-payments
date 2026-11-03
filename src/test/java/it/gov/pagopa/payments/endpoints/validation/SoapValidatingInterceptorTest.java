package it.gov.pagopa.payments.endpoints.validation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import it.gov.pagopa.payments.config.LogCapture;
import it.gov.pagopa.payments.config.RequestFilter;
import it.gov.pagopa.payments.config.logging.LogContext;
import java.util.Map;
import javax.xml.soap.SOAPException;
import javax.xml.soap.SOAPMessage;
import javax.xml.soap.SOAPPart;
import javax.servlet.ServletRequestEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.soap.saaj.SaajSoapMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.xml.sax.SAXParseException;

class SoapValidatingInterceptorTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void finalHttp200SummaryReflectsResponseValidationFailure(boolean invalidXml) throws Exception {
    RequestAttributes previous = RequestContextHolder.getRequestAttributes();
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    RequestFilter filter = new RequestFilter("/actuator");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));
    try (LogCapture summaries = new LogCapture(RequestFilter.class);
        LogCapture diagnostics = new LogCapture(SoapValidatingInterceptor.class)) {
      filter.doFilter(request, response, (req, res) -> {
        LogContext.fields().put(LogContext.STATUS, LogContext.STATUS_OK)
            .put(LogContext.CTX_SOAP_OUTCOME, "OK")
            .put(LogContext.METHOD, "paGetPaymentV2");
        SAXParseException[] errors = invalidXml
            ? new SAXParseException[] {new SAXParseException("Invalid response XML", null)}
            : new SAXParseException[0];
        assertFalse(new SoapValidatingInterceptor()
            .handleResponseValidationErrors(mock(MessageContext.class), errors));
      });
      filter.requestLoggingListener().getListener().requestDestroyed(
          new ServletRequestEvent(new MockServletContext(), request));

      assertEquals(1, summaries.events().size());
      JsonNode summary = summaries.events().get(0);
      assertEquals(200, summary.path(LogContext.HTTP_CODE).intValue());
      assertEquals(invalidXml ? "KO" : "OK", summary.path(LogContext.STATUS).asText());
      assertEquals("OK", summary.path(LogContext.CTX_SOAP_OUTCOME).asText());
      assertEquals("Completed API operation", summary.path(LogContext.MESSAGE).asText());
      assertFalse(summary.has(LogContext.FAULT_CODE));
      assertEquals(invalidXml ? 1 : 0, diagnostics.events().size());
    } finally {
      RequestContextHolder.setRequestAttributes(previous);
    }
  }

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
