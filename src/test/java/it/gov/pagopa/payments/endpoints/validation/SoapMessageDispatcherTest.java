package it.gov.pagopa.payments.endpoints.validation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    when(request.getHeader("SOAPAction")).thenReturn("paVerifyPaymentNotice");
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

    when(request.getHeader("SOAPAction")).thenReturn("paDemandPaymentNotice");
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

    when(request.getHeader("SOAPAction")).thenReturn("paGetPayment");
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

    when(request.getHeader("SOAPAction")).thenReturn("paGetPaymentV2");
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

    when(request.getHeader("SOAPAction")).thenReturn("paSendRT");
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

    when(request.getHeader("SOAPAction")).thenReturn("paSendRTV2");
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

    when(request.getHeader("SOAPAction")).thenReturn("unknown");

    doThrow(new PartnerValidationException(PaaErrorEnum.PAA_SEMANTICA))
        .when(soapMessageDispatcher)
        .callService(any(), any());

    soapMessageDispatcher.doService(request, response);

    verify(response, never()).getOutputStream();
    verify(response).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
  }
}
