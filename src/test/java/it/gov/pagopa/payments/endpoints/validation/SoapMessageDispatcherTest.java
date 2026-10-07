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

import it.gov.pagopa.payments.endpoints.validation.exceptions.PartnerValidationException;
import it.gov.pagopa.payments.model.PaaErrorEnum;
import it.gov.pagopa.payments.model.partner.ObjectFactory;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.ws.transport.TransportConstants;

@SpringBootTest
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
}
