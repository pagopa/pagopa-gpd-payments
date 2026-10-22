package it.gov.pagopa.payments.config;

import static it.gov.pagopa.payments.utils.SoapActions.PA_VERIFY_PAYMENT_NOTICE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import it.gov.pagopa.payments.endpoints.PartnerEndpoint;
import it.gov.pagopa.payments.logging.HttpRequestLogContext;
import it.gov.pagopa.payments.model.partner.ObjectFactory;
import it.gov.pagopa.payments.service.PartnerService;
import javax.servlet.DispatcherType;
import javax.xml.bind.JAXBElement;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.ws.server.endpoint.MethodEndpoint;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.transport.context.DefaultTransportContext;
import org.springframework.ws.transport.context.TransportContext;
import org.springframework.ws.transport.context.TransportContextHolder;
import org.springframework.ws.transport.http.HttpServletConnection;

class RequestLoggingInterceptorTest {

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
