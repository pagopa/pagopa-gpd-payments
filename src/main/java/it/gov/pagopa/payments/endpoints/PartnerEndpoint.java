package it.gov.pagopa.payments.endpoints;

import static it.gov.pagopa.payments.utils.SoapActions.PA_DEMAND_PAYMENT_NOTICE;
import static it.gov.pagopa.payments.utils.SoapActions.PA_GET_PAYMENT;
import static it.gov.pagopa.payments.utils.SoapActions.PA_GET_PAYMENT_V2;
import static it.gov.pagopa.payments.utils.SoapActions.PA_SEND_RT;
import static it.gov.pagopa.payments.utils.SoapActions.PA_SEND_RT_V2;
import static it.gov.pagopa.payments.utils.SoapActions.PA_VERIFY_PAYMENT_NOTICE;

import it.gov.pagopa.payments.endpoints.validation.exceptions.PartnerValidationException;
import it.gov.pagopa.payments.logging.OperationLogScope;
import it.gov.pagopa.payments.logging.RequestArgs;
import it.gov.pagopa.payments.model.partner.ObjectFactory;
import it.gov.pagopa.payments.model.partner.PaDemandPaymentNoticeRequest;
import it.gov.pagopa.payments.model.partner.PaDemandPaymentNoticeResponse;
import it.gov.pagopa.payments.model.partner.PaGetPaymentReq;
import it.gov.pagopa.payments.model.partner.PaGetPaymentRes;
import it.gov.pagopa.payments.model.partner.PaGetPaymentV2Request;
import it.gov.pagopa.payments.model.partner.PaGetPaymentV2Response;
import it.gov.pagopa.payments.model.partner.PaSendRTReq;
import it.gov.pagopa.payments.model.partner.PaSendRTRes;
import it.gov.pagopa.payments.model.partner.PaSendRTV2Request;
import it.gov.pagopa.payments.model.partner.PaSendRTV2Response;
import it.gov.pagopa.payments.model.partner.PaVerifyPaymentNoticeReq;
import it.gov.pagopa.payments.model.partner.PaVerifyPaymentNoticeRes;
import it.gov.pagopa.payments.service.PartnerService;
import it.gov.pagopa.payments.utils.CommonUtil;
import javax.xml.bind.JAXBElement;
import javax.xml.datatype.DatatypeConfigurationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;
import org.springframework.ws.soap.server.endpoint.annotation.SoapAction;

@Endpoint
@Slf4j
public class PartnerEndpoint {

  private final PartnerService partnerService;
  private final ObjectFactory factory;

  public PartnerEndpoint(PartnerService partnerService, ObjectFactory factory) {
    this.partnerService = partnerService;
    this.factory = factory;
  }

  @SoapAction(PA_VERIFY_PAYMENT_NOTICE)
  @PayloadRoot(localPart = "paVerifyPaymentNoticeReq")
  @ResponsePayload
  public JAXBElement<PaVerifyPaymentNoticeRes> paVerifyPaymentNotice(
      @RequestPayload JAXBElement<PaVerifyPaymentNoticeReq> request)
      throws DatatypeConfigurationException, PartnerValidationException {
    var value = request.getValue();
    summaryArgs(RequestArgs.paVerifyPaymentNotice(value));
    payment(PA_VERIFY_PAYMENT_NOTICE,
        value.getQrCode() == null ? null : value.getQrCode().getNoticeNumber(), value.getIdPA());
    return factory.createPaVerifyPaymentNoticeRes(
        partnerService.paVerifyPaymentNotice(request.getValue(), CommonUtil.getServiceType()));
  }

  @SoapAction(PA_GET_PAYMENT)
  @PayloadRoot(localPart = "paGetPaymentReq")
  @ResponsePayload
  public JAXBElement<PaGetPaymentRes> paGetPayment(
      @RequestPayload JAXBElement<PaGetPaymentReq> request)
      throws PartnerValidationException, DatatypeConfigurationException {
    var value = request.getValue();
    summaryArgs(RequestArgs.paGetPayment(value));
    payment(PA_GET_PAYMENT,
        value.getQrCode() == null ? null : value.getQrCode().getNoticeNumber(), value.getIdPA());
    return factory.createPaGetPaymentRes(
        partnerService.paGetPayment(request.getValue(), CommonUtil.getServiceType()));
  }

  @SoapAction(PA_GET_PAYMENT_V2)
  @PayloadRoot(localPart = "paGetPaymentV2Request")
  @ResponsePayload
  public JAXBElement<PaGetPaymentV2Response> paGetPaymentV2(
      @RequestPayload JAXBElement<PaGetPaymentV2Request> request)
      throws PartnerValidationException, DatatypeConfigurationException {
    var value = request.getValue();
    summaryArgs(RequestArgs.paGetPaymentV2(value));
    payment(PA_GET_PAYMENT_V2,
        value.getQrCode() == null ? null : value.getQrCode().getNoticeNumber(), value.getIdPA());
    return factory.createPaGetPaymentV2Response(
        partnerService.paGetPaymentV2(request.getValue(), CommonUtil.getServiceType()));
  }

  @SoapAction(PA_SEND_RT)
  @PayloadRoot(localPart = "paSendRTReq")
  @ResponsePayload
  public JAXBElement<PaSendRTRes> paSendRT(@RequestPayload JAXBElement<PaSendRTReq> request) {
    var receipt = request.getValue().getReceipt();
    payment(PA_SEND_RT, receipt == null ? null : receipt.getNoticeNumber(),
        receipt == null ? null : receipt.getFiscalCode());
    summaryArgs(RequestArgs.paSendRT(request.getValue()));
    return factory.createPaSendRTRes(partnerService.paSendRT(request.getValue()));
  }

  @SoapAction(PA_SEND_RT_V2)
  @PayloadRoot(localPart = "PaSendRTV2Request")
  @ResponsePayload
  public JAXBElement<PaSendRTV2Response> paSendRTV2(
      @RequestPayload JAXBElement<PaSendRTV2Request> request) {
    var receipt = request.getValue().getReceipt();
    payment(PA_SEND_RT_V2, receipt == null ? null : receipt.getNoticeNumber(),
        receipt == null ? null : receipt.getFiscalCode());
    summaryArgs(RequestArgs.paSendRTV2(request.getValue()));
    return factory.createPaSendRTV2Response(partnerService.paSendRTV2(request.getValue()));
  }

  @SoapAction(PA_DEMAND_PAYMENT_NOTICE)
  @PayloadRoot(localPart = "paDemandPaymentNotice")
  @ResponsePayload
  public JAXBElement<PaDemandPaymentNoticeResponse> paDemandPaymentNotice(
      @RequestPayload JAXBElement<PaDemandPaymentNoticeRequest> request) {
    summaryArgs(RequestArgs.paDemandPaymentNotice(request.getValue()));
    payment(PA_DEMAND_PAYMENT_NOTICE, null, request.getValue().getIdPA());
    return factory.createPaDemandPaymentNoticeResponse(
        partnerService.paDemandPaymentNotice(request.getValue()));
  }

  private static void summaryArgs(String args) {
    if (OperationLogScope.currentOrNull() != null) {
      OperationLogScope.current().summaryArgs(args);
    }
  }

  private static void payment(String method, String noticeNumber, String creditorFiscalCode) {
    if (OperationLogScope.currentOrNull() != null) {
      org.slf4j.MDC.put("method", method);
      OperationLogScope.current().setPaymentLogContext(noticeNumber, creditorFiscalCode);
    }
  }
}
