package it.gov.pagopa.payments.config;

import static it.gov.pagopa.payments.utils.SoapActions.PA_VERIFY_PAYMENT_NOTICE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import it.gov.pagopa.payments.endpoints.PartnerEndpoint;
import it.gov.pagopa.payments.logging.HttpRequestLogContext;
import it.gov.pagopa.payments.model.partner.ObjectFactory;
import it.gov.pagopa.payments.service.PartnerService;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.StringReader;
import java.util.Locale;
import javax.servlet.DispatcherType;
import javax.xml.bind.JAXBElement;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.ws.server.endpoint.MethodEndpoint;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.context.DefaultMessageContext;
import org.springframework.ws.soap.SoapMessage;
import org.springframework.ws.soap.saaj.SaajSoapMessageFactory;
import org.springframework.ws.transport.context.DefaultTransportContext;
import org.springframework.ws.transport.context.TransportContext;
import org.springframework.ws.transport.context.TransportContextHolder;
import org.springframework.ws.transport.http.HttpServletConnection;

class RequestLoggingInterceptorTest {

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void technicalSoapFaultMetadataRequiresSuccessfulWriting(boolean writingFails) throws Exception {
    SaajSoapMessageFactory factory = new SaajSoapMessageFactory();
    factory.afterPropertiesSet();
    MessageContext message = new DefaultMessageContext(factory);
    SoapMessage soap = (SoapMessage) message.getResponse();
    var fault = soap.getSoapBody().addServerOrReceiverFault("Public SOAP reason", Locale.ENGLISH);

    JsonNode logEntry = completeSoapResponse(message,
        writingFails ? new IOException("transport failure") : null);

    assertEquals(200, logEntry.path("http_code").intValue());
    if (writingFails) {
      assertFalse(logEntry.has("faultCode"));
      assertFalse(logEntry.has("faultDetail"));
      assertFalse(logEntry.has("ctx_soap_fault_code"));
    } else {
      assertEquals(fault.getFaultCode().toString(), logEntry.path("faultCode").textValue());
      assertFalse(logEntry.has("ctx_soap_fault_code"));
      assertEquals("Public SOAP reason", logEntry.path("faultDetail").textValue());
      assertFalse(logEntry.has("ctx_soap_outcome"));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void applicationSoapFaultDetailPrefersDescriptionAndFallsBackToFaultString(
      boolean hasDescription) throws Exception {
    SaajSoapMessageFactory factory = new SaajSoapMessageFactory();
    factory.afterPropertiesSet();
    MessageContext message = new DefaultMessageContext(factory);
    SoapMessage soap = (SoapMessage) message.getResponse();
    String payload = """
        <p:paGetPaymentRes xmlns:p="http://pagopa-api.pagopa.gov.it/pa/paForNode.xsd">
          <outcome>KO</outcome><fault><faultCode>PAA_SEMANTICA</faultCode>
          <faultString>Public fault string</faultString>%s</fault>
        </p:paGetPaymentRes>
        """.formatted(hasDescription ? "<description>Public description</description>" : "");
    TransformerFactory.newInstance().newTransformer().transform(
        new StreamSource(new StringReader(payload)), soap.getPayloadResult());

    JsonNode logEntry = completeSoapResponse(message, null);

    assertEquals("KO", logEntry.path("ctx_soap_outcome").textValue());
    assertEquals("PAA_SEMANTICA", logEntry.path("faultCode").textValue());
    assertFalse(logEntry.has("ctx_soap_fault_code"));
    assertEquals(hasDescription ? "Public description" : "Public fault string",
        logEntry.path("faultDetail").textValue());
  }

  private JsonNode completeSoapResponse(MessageContext message, Exception writeFailure)
      throws Exception {
    TransportContext previous = TransportContextHolder.getTransportContext();
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    RequestFilter filter = new RequestFilter();
    try (RequestLogCapture logs = new RequestLogCapture()) {
      TransportContextHolder.setTransportContext(
          new DefaultTransportContext(new HttpServletConnection(request, response) {}));
      filter.doFilter(request, response, (req, res) ->
          new RequestLoggingInterceptor().afterCompletion(message, null, writeFailure));
      filter.logCompletedRequest(request);
      return logs.singleSummary();
    } finally {
      TransportContextHolder.setTransportContext(previous);
    }
  }

  @Test
  void soapActionComesFromResolvedEndpointNotTheIncomingHeader() throws Exception {
    TransportContext previous = TransportContextHolder.getTransportContext();
    java.util.Map<String, String> previousMdc = MDC.getCopyOfContextMap();
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    request.addHeader("SOAPAction", "untrusted-value");
    HttpRequestLogContext context = new HttpRequestLogContext("request-1", response);
    context.attach(request, response);
    PartnerEndpoint endpoint =
        new PartnerEndpoint(mock(PartnerService.class), new ObjectFactory());
    MethodEndpoint method =
        new MethodEndpoint(endpoint, PartnerEndpoint.class.getMethod(
            "paVerifyPaymentNotice", JAXBElement.class));
    TransportContextHolder.setTransportContext(
        new DefaultTransportContext(new HttpServletConnection(request, response) {}));
    try {
      assertTrue(new RequestLoggingInterceptor().handleRequest(mock(MessageContext.class), method));
      assertEquals(PA_VERIFY_PAYMENT_NOTICE, MDC.get("method"));
    } finally {
      TransportContextHolder.setTransportContext(previous);
      if (previousMdc == null) {
        MDC.clear();
      } else {
        MDC.setContextMap(previousMdc);
      }
    }
  }

  @Test
  void errorControllerCannotReplaceTheOriginalApiAction() throws Exception {
    java.util.Map<String, String> previousMdc = MDC.getCopyOfContextMap();
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    HttpRequestLogContext context = new HttpRequestLogContext("request-1", response);
    context.attach(request, response);
    try {
      context.setMethod("originalMethod");
      request.setDispatcherType(DispatcherType.ERROR);
      HandlerMethod errorHandler =
          new HandlerMethod(this, getClass().getDeclaredMethod("errorControllerCannotReplaceTheOriginalApiAction"));

      assertTrue(new RequestLoggingInterceptor().preHandle(request, response, errorHandler));
      assertEquals("originalMethod", MDC.get("method"));
    } finally {
      if (previousMdc == null) {
        MDC.clear();
      } else {
        MDC.setContextMap(previousMdc);
      }
    }
  }
}
