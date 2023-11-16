package it.gov.pagopa.payments.endpoints;

import it.gov.pagopa.payments.endpoints.validation.exceptions.PartnerValidationException;
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
import java.io.IOException;
import javax.xml.bind.JAXBElement;
import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLStreamException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;
import org.springframework.ws.soap.server.endpoint.annotation.SoapAction;
import org.xml.sax.SAXException;

@Endpoint
@Slf4j
public class PartnerEndpoint {

  @Autowired private PartnerService partnerService;

  @Autowired private ObjectFactory factory;

  @SoapAction("paVerifyPaymentNotice")
  @PayloadRoot(localPart = "paVerifyPaymentNoticeReq")
  @ResponsePayload
  public JAXBElement<PaVerifyPaymentNoticeRes> paVerifyPaymentNotice(
      @RequestPayload JAXBElement<PaVerifyPaymentNoticeReq> request)
      throws DatatypeConfigurationException, PartnerValidationException {

    log.debug(" paVerifyPaymentNotice START ");
    return factory.createPaVerifyPaymentNoticeRes(
        partnerService.paVerifyPaymentNotice(request.getValue()));
  }

  @SoapAction("paGetPayment")
  @PayloadRoot(localPart = "paGetPaymentReq")
  @ResponsePayload
  public JAXBElement<PaGetPaymentRes> paGetPayment(
      @RequestPayload JAXBElement<PaGetPaymentReq> request)
      throws PartnerValidationException, DatatypeConfigurationException {

    log.debug(" paGetPayment START ");
    return factory.createPaGetPaymentRes(partnerService.paGetPayment(request.getValue()));
  }

  @SoapAction("paGetPaymentV2")
  @PayloadRoot(localPart = "paGetPaymentV2Request")
  @ResponsePayload
  public JAXBElement<PaGetPaymentV2Response> paGetPaymentV2(
      @RequestPayload JAXBElement<PaGetPaymentV2Request> request)
      throws PartnerValidationException, DatatypeConfigurationException {

    log.debug(" paGetPaymentV2 START ");
    return factory.createPaGetPaymentV2Response(partnerService.paGetPaymentV2(request.getValue()));
  }

  @SoapAction("paSendRT")
  @PayloadRoot(localPart = "paSendRTReq")
  @ResponsePayload
  public JAXBElement<PaSendRTRes> paSendRT(@RequestPayload JAXBElement<PaSendRTReq> request) {

    log.debug("paSendRT START [noticeNumber={}]", request.getValue().getReceipt().getNoticeNumber());
    return factory.createPaSendRTRes(partnerService.paSendRT(request.getValue()));
  }

  @SoapAction("paSendRTV2")
  @PayloadRoot(localPart = "PaSendRTV2Request")
  @ResponsePayload
  public JAXBElement<PaSendRTV2Response> paSendRTV2(
      @RequestPayload JAXBElement<PaSendRTV2Request> request) {

    log.debug(" paSendRTV2 START ");
    return factory.createPaSendRTV2Response(partnerService.paSendRTV2(request.getValue()));
  }

  @SoapAction("paDemandPaymentNotice")
  @PayloadRoot(localPart = "paDemandPaymentNotice")
  @ResponsePayload
  public JAXBElement<PaDemandPaymentNoticeResponse> paDemandPaymentNotice(
      @RequestPayload JAXBElement<PaDemandPaymentNoticeRequest> request)
      throws DatatypeConfigurationException, ParserConfigurationException, IOException,
          SAXException, XMLStreamException {

    log.debug(" paDemandPaymentNotice START ");
    return factory.createPaDemandPaymentNoticeResponse(
        partnerService.paDemandPaymentNotice(request.getValue()));
  }
}
