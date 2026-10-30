package it.gov.pagopa.payments.endpoints.validation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static it.gov.pagopa.payments.utils.SoapActions.PA_DEMAND_PAYMENT_NOTICE;
import static it.gov.pagopa.payments.utils.SoapActions.PA_GET_PAYMENT;
import static it.gov.pagopa.payments.utils.SoapActions.PA_GET_PAYMENT_V2;
import static it.gov.pagopa.payments.utils.SoapActions.PA_SEND_RT;
import static it.gov.pagopa.payments.utils.SoapActions.PA_SEND_RT_V2;
import static it.gov.pagopa.payments.utils.SoapActions.PA_VERIFY_PAYMENT_NOTICE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import it.gov.pagopa.payments.config.LogContext;
import it.gov.pagopa.payments.endpoints.validation.exceptions.PartnerValidationException;
import it.gov.pagopa.payments.model.PaaErrorEnum;
import it.gov.pagopa.payments.model.partner.CtResponse;
import it.gov.pagopa.payments.model.partner.ObjectFactory;
import it.gov.pagopa.payments.model.partner.StOutcome;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Stream;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.soap.MessageFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.mock.web.DelegatingServletOutputStream;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletConfig;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.ws.transport.TransportConstants;
import org.w3c.dom.Element;

@ExtendWith(MockitoExtension.class)
class SoapMessageDispatcherTest {

  @InjectMocks @Spy SoapMessageDispatcher soapMessageDispatcher;

  @Mock HttpServletRequest request;

  @Mock HttpServletResponse response;

  @Mock private ObjectFactory factory;

  @Mock private ServletOutputStream outputStreamMock;

  private final ObjectFactory factoryUtil = new ObjectFactory();

  @Test
  void paVerifyPaymentNotice() throws Exception {
    when(factory.createCtFaultBean()).thenReturn(factoryUtil.createCtFaultBean());
    when(factory.createPaVerifyPaymentNoticeRes())
        .thenReturn(factoryUtil.createPaVerifyPaymentNoticeRes());
    when(factory.createPaVerifyPaymentNoticeRes(any()))
        .thenReturn(
            factoryUtil.createPaVerifyPaymentNoticeRes(
                factoryUtil.createPaVerifyPaymentNoticeRes()));

    when(request.getHeader(TransportConstants.HEADER_SOAP_ACTION))
        .thenReturn(PA_VERIFY_PAYMENT_NOTICE);
    when(response.getOutputStream()).thenReturn(outputStreamMock);

    doThrow(new PartnerValidationException(PaaErrorEnum.PAA_SEMANTICA))
        .when(soapMessageDispatcher)
        .callService(any(), any());

    soapMessageDispatcher.doService(request, response);

    verify(response, times(1)).getOutputStream();
  }

  @Test
  void paDemandPaymentNotice() throws Exception {
    when(factory.createCtFaultBean()).thenReturn(factoryUtil.createCtFaultBean());
    when(factory.createPaDemandPaymentNoticeResponse())
        .thenReturn(factoryUtil.createPaDemandPaymentNoticeResponse());
    when(factory.createPaDemandPaymentNoticeResponse(any()))
        .thenReturn(
            factoryUtil.createPaDemandPaymentNoticeResponse(
                factoryUtil.createPaDemandPaymentNoticeResponse()));

    when(request.getHeader(TransportConstants.HEADER_SOAP_ACTION))
        .thenReturn(PA_DEMAND_PAYMENT_NOTICE);
    when(response.getOutputStream()).thenReturn(outputStreamMock);

    doThrow(new PartnerValidationException(PaaErrorEnum.PAA_SEMANTICA))
        .when(soapMessageDispatcher)
        .callService(any(), any());

    soapMessageDispatcher.doService(request, response);

    verify(response, times(1)).getOutputStream();
  }

  @Test
  void paGetPayment() throws Exception {
    when(factory.createCtFaultBean()).thenReturn(factoryUtil.createCtFaultBean());
    when(factory.createPaGetPaymentRes()).thenReturn(factoryUtil.createPaGetPaymentRes());
    when(factory.createPaGetPaymentRes(any()))
        .thenReturn(factoryUtil.createPaGetPaymentRes(factoryUtil.createPaGetPaymentRes()));

    when(request.getHeader(TransportConstants.HEADER_SOAP_ACTION)).thenReturn(PA_GET_PAYMENT);
    when(response.getOutputStream()).thenReturn(outputStreamMock);

    doThrow(new PartnerValidationException(PaaErrorEnum.PAA_SEMANTICA))
        .when(soapMessageDispatcher)
        .callService(any(), any());

    soapMessageDispatcher.doService(request, response);

    verify(response, times(1)).getOutputStream();
  }

  @Test
  void paGetPaymentV2() throws Exception {
    when(factory.createCtFaultBean()).thenReturn(factoryUtil.createCtFaultBean());
    when(factory.createPaGetPaymentV2Response())
        .thenReturn(factoryUtil.createPaGetPaymentV2Response());
    when(factory.createPaGetPaymentV2Response(any()))
        .thenReturn(
            factoryUtil.createPaGetPaymentV2Response(factoryUtil.createPaGetPaymentV2Response()));

    when(request.getHeader(TransportConstants.HEADER_SOAP_ACTION)).thenReturn(PA_GET_PAYMENT_V2);
    when(response.getOutputStream()).thenReturn(outputStreamMock);

    doThrow(new PartnerValidationException(PaaErrorEnum.PAA_SEMANTICA))
        .when(soapMessageDispatcher)
        .callService(any(), any());

    soapMessageDispatcher.doService(request, response);

    verify(response, times(1)).getOutputStream();
  }

  @Test
  void paSendRT() throws Exception {
    when(factory.createCtFaultBean()).thenReturn(factoryUtil.createCtFaultBean());
    when(factory.createPaSendRTRes()).thenReturn(factoryUtil.createPaSendRTRes());
    when(factory.createPaSendRTRes(any()))
        .thenReturn(factoryUtil.createPaSendRTRes(factoryUtil.createPaSendRTRes()));

    when(request.getHeader(TransportConstants.HEADER_SOAP_ACTION)).thenReturn(PA_SEND_RT);
    when(response.getOutputStream()).thenReturn(outputStreamMock);

    doThrow(new PartnerValidationException(PaaErrorEnum.PAA_SEMANTICA))
        .when(soapMessageDispatcher)
        .callService(any(), any());

    soapMessageDispatcher.doService(request, response);

    verify(response, times(1)).getOutputStream();
  }

  @Test
  void paSendRTV2() throws Exception {
    when(factory.createCtFaultBean()).thenReturn(factoryUtil.createCtFaultBean());
    when(factory.createPaSendRTV2Response()).thenReturn(factoryUtil.createPaSendRTV2Response());
    when(factory.createPaSendRTV2Response(any()))
        .thenReturn(factoryUtil.createPaSendRTV2Response(factoryUtil.createPaSendRTV2Response()));

    when(request.getHeader(TransportConstants.HEADER_SOAP_ACTION)).thenReturn(PA_SEND_RT_V2);
    when(response.getOutputStream()).thenReturn(outputStreamMock);

    doThrow(new PartnerValidationException(PaaErrorEnum.PAA_SEMANTICA))
        .when(soapMessageDispatcher)
        .callService(any(), any());

    soapMessageDispatcher.doService(request, response);

    verify(response, times(1)).getOutputStream();
  }

  @Test
  void doServiceDefault() throws Exception {
    when(factory.createCtFaultBean()).thenReturn(factoryUtil.createCtFaultBean());

    when(request.getHeader(TransportConstants.HEADER_SOAP_ACTION)).thenReturn("unknown");

    doThrow(new PartnerValidationException(PaaErrorEnum.PAA_SEMANTICA))
        .when(soapMessageDispatcher)
        .callService(any(), any());

    soapMessageDispatcher.doService(request, response);

    verify(response, never()).getOutputStream();
    verify(response).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
  }

  private Map<String, String> previousMdc;

  @BeforeEach
  void saveMdc() {
    previousMdc = MDC.getCopyOfContextMap();
    MDC.clear();
  }

  @AfterEach
  void restoreMdc() {
    LogContext.restore(previousMdc);
  }

  static Stream<Arguments> faultActions() {
    return Stream.of(
        Arguments.of(PA_VERIFY_PAYMENT_NOTICE, "paVerifyPaymentNoticeRes"),
        Arguments.of(PA_GET_PAYMENT, "paGetPaymentRes"),
        Arguments.of(PA_GET_PAYMENT_V2, "paGetPaymentV2Response"),
        Arguments.of(PA_DEMAND_PAYMENT_NOTICE, "paDemandPaymentNoticeResponse"),
        Arguments.of(PA_SEND_RT, "paSendRTRes"),
        Arguments.of(PA_SEND_RT_V2, "paSendRTV2Response"));
  }

  @ParameterizedTest
  @MethodSource("faultActions")
  void businessFaultContainsRealPayloadAndLoggingDetails(String action, String responseName)
      throws Exception {
    var servletRequest = new MockHttpServletRequest();
    var servletResponse = new MockHttpServletResponse();
    ObjectNode fields = attachFields(servletRequest);
    servletRequest.addHeader(TransportConstants.HEADER_SOAP_ACTION, "\"" + action + "\"");
    var dispatcher = spy(new SoapMessageDispatcher(factoryUtil, "broker-id"));
    var error = PaaErrorEnum.PAA_SINTASSI_XSD;
    doThrow(new PartnerValidationException(error))
        .when(dispatcher).callService(servletRequest, servletResponse);

    dispatcher.doService(servletRequest, servletResponse);

    assertEquals(200, servletResponse.getStatus());
    assertEquals("text/xml", servletResponse.getContentType());
    var message = MessageFactory.newInstance().createMessage(
        null, new ByteArrayInputStream(servletResponse.getContentAsByteArray()));
    assertFalse(message.getSOAPBody().hasFault());
    assertEquals("soapenv", message.getSOAPPart().getEnvelope().getPrefix());
    Element payload = (Element) message.getSOAPBody().getFirstChild();
    assertEquals(responseName, payload.getLocalName());
    assertEquals("http://pagopa-api.pagopa.gov.it/pa/paForNode.xsd", payload.getNamespaceURI());
    var element = (JAXBElement<?>) JAXBContext.newInstance(ObjectFactory.class)
        .createUnmarshaller().unmarshal(payload);
    CtResponse result = (CtResponse) element.getValue();
    assertEquals(StOutcome.KO, result.getOutcome());
    assertNotNull(result.getFault());
    assertEquals("broker-id", result.getFault().getId());
    assertEquals(error.getFaultCode(), result.getFault().getFaultCode());
    assertEquals(error.getFaultString(), result.getFault().getFaultString());
    assertEquals(error.getDescription(), result.getFault().getDescription());
    assertEquals(action, fields.path(LogContext.METHOD).asText());
    assertEquals(action, MDC.get(LogContext.METHOD));
    assertEquals(LogContext.STATUS_KO, fields.path(LogContext.STATUS).asText());
    assertEquals(LogContext.STATUS_KO, fields.path(LogContext.CTX_SOAP_OUTCOME).asText());
    assertEquals(error.getFaultCode(), fields.path(LogContext.FAULT_CODE).asText());
    assertEquals(error.getDescription(), fields.path(LogContext.FAULT_DETAIL).asText());
  }

  static Stream<Arguments> successfulActions() {
    return Stream.of(PA_GET_PAYMENT, "unknown", null)
        .flatMap(action -> Stream.of(true, false)
            .map(context -> Arguments.of(action, context)));
  }

  @ParameterizedTest
  @MethodSource("successfulActions")
  void successfulDispatchPreservesResponseAndOnlyEnrichesKnownAction(
      String action, boolean withContext) throws Exception {
    var servletRequest = new MockHttpServletRequest();
    var servletResponse = new MockHttpServletResponse();
    ObjectNode fields = withContext ? attachFields(servletRequest) : null;
    ObjectNode expectedFields = fields == null ? null : fields.deepCopy();
    if (action != null) {
      servletRequest.addHeader(TransportConstants.HEADER_SOAP_ACTION, action);
    }
    var dispatcher = spy(new SoapMessageDispatcher(factoryUtil, "broker-id"));
    doAnswer(invocation -> {
      servletResponse.setStatus(202);
      servletResponse.getWriter().write("<success/>");
      return null;
    }).when(dispatcher).callService(servletRequest, servletResponse);

    dispatcher.doService(servletRequest, servletResponse);

    verify(dispatcher).callService(servletRequest, servletResponse);
    assertEquals(202, servletResponse.getStatus());
    assertEquals("<success/>", servletResponse.getContentAsString());
    String expectedMethod = withContext && PA_GET_PAYMENT.equals(action) ? action : null;
    assertEquals(expectedMethod, MDC.get(LogContext.METHOD));
    if (expectedMethod != null) {
      expectedFields.put(LogContext.METHOD, action);
    }
    assertEquals(expectedFields, LogContext.fields(servletRequest));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void dispatchFailuresClearStaleFaultDetailsWithOrWithoutContext(boolean withContext)
      throws Exception {
    for (String failure : new String[] {"missing-action", "unsupported", "unexpected", "write"}) {
      var servletRequest = new MockHttpServletRequest();
      var servletResponse = new MockHttpServletResponse();
      ObjectNode fields = withContext ? attachFields(servletRequest) : null;
      if (!"missing-action".equals(failure)) {
        servletRequest.addHeader(TransportConstants.HEADER_SOAP_ACTION,
            "unsupported".equals(failure) ? "unknown" : PA_GET_PAYMENT);
      }
      var dispatcher = spy(new SoapMessageDispatcher(factoryUtil, "broker-id"));
      Exception exception = "unexpected".equals(failure)
          ? new IllegalStateException("dispatch failed")
          : new PartnerValidationException(PaaErrorEnum.PAA_SINTASSI_XSD);
      HttpServletResponse target = servletResponse;
      if ("write".equals(failure)) {
        target = new MockHttpServletResponse() {
          @Override
          public ServletOutputStream getOutputStream() {
            return new DelegatingServletOutputStream(new OutputStream() {
              @Override
              public void write(int value) throws IOException {
                throw new IOException("client disconnected");
              }
            });
          }
        };
      }
      doThrow(exception).when(dispatcher).callService(servletRequest, target);

      dispatcher.doService(servletRequest, target);

      assertEquals("missing-action".equals(failure) ? 200 : 500, target.getStatus(), failure);
      assertEquals(0, servletResponse.getContentAsByteArray().length, failure);
      if (withContext) {
        assertEquals(LogContext.STATUS_KO, fields.path(LogContext.STATUS).asText(), failure);
        assertFalse(fields.has(LogContext.CTX_SOAP_OUTCOME), failure);
        assertFalse(fields.has(LogContext.FAULT_CODE), failure);
        assertFalse(fields.has(LogContext.FAULT_DETAIL), failure);
        assertEquals("request-id", fields.path(LogContext.REQUEST_ID).asText(), failure);
      } else {
        assertNull(LogContext.fields(servletRequest), failure);
      }
    }
  }

  @Test
  void realSpringDispatchWithoutMatchingEndpointReturnsNotFound() throws Exception {
    try (var context = new StaticWebApplicationContext()) {
      context.refresh();
      var dispatcher = new SoapMessageDispatcher(factoryUtil, "broker-id");
      dispatcher.setApplicationContext(context);
      dispatcher.init(new MockServletConfig());
      var servletRequest = new MockHttpServletRequest();
      var servletResponse = new MockHttpServletResponse();
      servletRequest.setMethod("POST");
      servletRequest.setContentType("text/xml");
      servletRequest.setContent(("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">"
          + "<s:Body><missing xmlns=\"urn:test\"/></s:Body></s:Envelope>")
          .getBytes(StandardCharsets.UTF_8));
      ObjectNode fields = attachFields(servletRequest);
      ObjectNode expected = fields.deepCopy();
      try {
        dispatcher.doService(servletRequest, servletResponse);
      } finally {
        dispatcher.destroy();
      }
      assertEquals(404, servletResponse.getStatus());
      assertEquals(0, servletResponse.getContentAsByteArray().length);
      assertEquals(expected, fields);
    }
  }

  private ObjectNode attachFields(MockHttpServletRequest servletRequest) {
    ObjectNode fields = LogContext.event().put(LogContext.STATUS, LogContext.STATUS_OK)
        .put(LogContext.CTX_SOAP_OUTCOME, LogContext.STATUS_OK)
        .put(LogContext.FAULT_CODE, "stale-code").put(LogContext.FAULT_DETAIL, "stale-detail")
        .put(LogContext.REQUEST_ID, "request-id");
    servletRequest.setAttribute(LogContext.class.getName(), fields);
    return fields;
  }
}
