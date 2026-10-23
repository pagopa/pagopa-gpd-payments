package it.gov.pagopa.payments.endpoints.validation;

import static it.gov.pagopa.payments.utils.SoapActions.PA_DEMAND_PAYMENT_NOTICE;
import static it.gov.pagopa.payments.utils.SoapActions.PA_GET_PAYMENT;
import static it.gov.pagopa.payments.utils.SoapActions.PA_GET_PAYMENT_V2;
import static it.gov.pagopa.payments.utils.SoapActions.PA_SEND_RT;
import static it.gov.pagopa.payments.utils.SoapActions.PA_SEND_RT_V2;
import static it.gov.pagopa.payments.utils.SoapActions.PA_VERIFY_PAYMENT_NOTICE;

import it.gov.pagopa.payments.config.LogContext;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.gov.pagopa.payments.endpoints.validation.exceptions.PartnerValidationException;
import it.gov.pagopa.payments.model.PaaErrorEnum;
import it.gov.pagopa.payments.model.partner.CtFaultBean;
import it.gov.pagopa.payments.model.partner.CtResponse;
import it.gov.pagopa.payments.model.partner.ObjectFactory;
import it.gov.pagopa.payments.model.partner.StOutcome;
import java.io.IOException;
import java.io.Serial;
import java.util.Set;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.soap.MessageFactory;
import javax.xml.soap.SOAPBody;
import javax.xml.soap.SOAPException;
import javax.xml.soap.SOAPMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.ws.transport.TransportConstants;
import org.springframework.ws.transport.http.MessageDispatcherServlet;
import org.w3c.dom.Document;

@Component
@Slf4j
public class SoapMessageDispatcher extends MessageDispatcherServlet {

  @Serial private static final long serialVersionUID = 2735436671084580797L;

  private static final String SOAP_PREFIX = "soapenv";
  private static final String ORIGINAL_SOAP_PREFIX = "SOAP-ENV";
  private static final String SOAP_PROCESSING_FAILED = "Processing resulted in generic exception";

  private final transient ObjectFactory factory;
  private final String intermediario;

  public SoapMessageDispatcher(
      ObjectFactory factory, @Value("${pt.id_intermediario}") String intermediario) {
    this.factory = factory;
    this.intermediario = intermediario;
  }

  /** Holds the fault details extracted from a {@link PartnerValidationException}. */
  private record FaultInfo(PaaErrorEnum errorEnum) {}

  @Override
  protected void doService(HttpServletRequest request, HttpServletResponse response) {
    String soapAction = getSOAPActionFromHeaders(request);
    ObjectNode fields = LogContext.fields(request);
    if (fields != null && soapAction != null
        && Set.of(PA_VERIFY_PAYMENT_NOTICE, PA_GET_PAYMENT, PA_GET_PAYMENT_V2,
            PA_DEMAND_PAYMENT_NOTICE, PA_SEND_RT, PA_SEND_RT_V2).contains(soapAction)) {
      fields.put(LogContext.METHOD, soapAction);
      org.slf4j.MDC.put(LogContext.METHOD, soapAction);
    }
    FaultInfo fault = dispatch(request, response);
    if (fault != null && soapAction != null) {
      writeFaultResponse(request, response, soapAction, fault);
    }
  }

  /**
   * Delegates to the standard Spring-WS dispatch and translates a validation failure into a {@link
   * FaultInfo}. Returns {@code null} when the request is processed without a business fault.
   */
  private FaultInfo dispatch(HttpServletRequest request, HttpServletResponse response) {
    try {
      callService(request, response);
      return null;
    } catch (PartnerValidationException e) {
      markFailed(request);
      response.setStatus(HttpServletResponse.SC_OK);
      return new FaultInfo(e.getError());
    } catch (Exception e) {
      markFailed(request);
      log.error(SOAP_PROCESSING_FAILED, e);
      response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
      return null;
    }
  }

  /**
   * Seam that delegates to the standard Spring-WS dispatch. Extracted to allow the request
   * processing to be stubbed in unit tests.
   */
  protected void callService(HttpServletRequest request, HttpServletResponse response)
      throws Exception {
    super.doService(request, response);
  }

  /** Builds the fault SOAP envelope for the given action and writes it to the response. */
  private void writeFaultResponse(
      HttpServletRequest request, HttpServletResponse response, String soapAction, FaultInfo fault) {
    try {
      JAXBElement<? extends CtResponse> faultElement =
          buildFaultElement(soapAction, toFaultBean(fault));
      writeSoapMessage(response, marshalToDocument(faultElement));
      ObjectNode fields = LogContext.fields(request);
      if (fields != null) {
        fields.put(LogContext.STATUS, LogContext.STATUS_KO)
            .put(LogContext.CTX_SOAP_OUTCOME, LogContext.STATUS_KO)
            .put(LogContext.FAULT_CODE, fault.errorEnum().getFaultCode())
            .put(LogContext.FAULT_DETAIL, fault.errorEnum().getDescription() == null
                ? fault.errorEnum().getFaultString() : fault.errorEnum().getDescription());
      }
    } catch (ParserConfigurationException | SOAPException | JAXBException | IOException e) {
      markFailed(request);
      log.error(SOAP_PROCESSING_FAILED, e);
      response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    }
  }

  private CtFaultBean toFaultBean(FaultInfo fault) {
    CtFaultBean faultBean = factory.createCtFaultBean();
    faultBean.setDescription(fault.errorEnum.getDescription());
    faultBean.setFaultCode(fault.errorEnum.getFaultCode());
    faultBean.setFaultString(fault.errorEnum.getFaultString());
    faultBean.setId(intermediario);
    return faultBean;
  }

  /** Selects the response type matching the SOAP action and wraps it as a JAXB root element. */
  private JAXBElement<? extends CtResponse> buildFaultElement(
      String soapAction, CtFaultBean faultBean) throws SOAPException {
    return switch (soapAction) {
      case PA_VERIFY_PAYMENT_NOTICE ->
          factory.createPaVerifyPaymentNoticeRes(
              withFault(factory.createPaVerifyPaymentNoticeRes(), faultBean));
      case PA_GET_PAYMENT ->
          factory.createPaGetPaymentRes(withFault(factory.createPaGetPaymentRes(), faultBean));
      case PA_GET_PAYMENT_V2 ->
          factory.createPaGetPaymentV2Response(
              withFault(factory.createPaGetPaymentV2Response(), faultBean));
      case PA_DEMAND_PAYMENT_NOTICE ->
          factory.createPaDemandPaymentNoticeResponse(
              withFault(factory.createPaDemandPaymentNoticeResponse(), faultBean));
      case PA_SEND_RT ->
          factory.createPaSendRTRes(withFault(factory.createPaSendRTRes(), faultBean));
      case PA_SEND_RT_V2 ->
          factory.createPaSendRTV2Response(
              withFault(factory.createPaSendRTV2Response(), faultBean));
      default ->
          throw new SOAPException("Unsupported SOAP action for fault response: " + soapAction);
    };
  }

  private <T extends CtResponse> T withFault(T response, CtFaultBean faultBean) {
    response.setOutcome(StOutcome.KO);
    response.setFault(faultBean);
    return response;
  }

  private Document marshalToDocument(JAXBElement<?> element)
      throws ParserConfigurationException, JAXBException {
    DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
    dbf.setNamespaceAware(true);
    Document doc = dbf.newDocumentBuilder().newDocument();
    JAXBContext.newInstance(ObjectFactory.class).createMarshaller().marshal(element, doc);
    return doc;
  }

  private void writeSoapMessage(HttpServletResponse response, Document doc)
      throws SOAPException, IOException {
    SOAPMessage soapMessage = MessageFactory.newInstance().createMessage();

    soapMessage.getSOAPPart().getEnvelope().removeNamespaceDeclaration(ORIGINAL_SOAP_PREFIX);
    soapMessage.getSOAPPart().getEnvelope().setPrefix(SOAP_PREFIX);
    soapMessage.getSOAPHeader().setPrefix(SOAP_PREFIX);
    soapMessage.getSOAPBody().setPrefix(SOAP_PREFIX);

    SOAPBody soapBody = soapMessage.getSOAPBody();
    soapBody.addDocument(doc);
    soapMessage.saveChanges();

    response.setContentType(MediaType.TEXT_XML_VALUE);
    ServletOutputStream outputStream = response.getOutputStream();
    soapMessage.writeTo(outputStream);
    outputStream.flush();
  }

  private static void markFailed(HttpServletRequest request) {
    ObjectNode fields = LogContext.fields(request);
    if (fields != null) {
      fields.put(LogContext.STATUS, LogContext.STATUS_KO);
      fields.remove(LogContext.FAULT_CODE);
      fields.remove(LogContext.FAULT_DETAIL);
      fields.remove(LogContext.CTX_SOAP_OUTCOME);
    }
  }

  private String getSOAPActionFromHeaders(HttpServletRequest request) {
    String soapAction = request.getHeader(TransportConstants.HEADER_SOAP_ACTION);
    return soapAction != null ? soapAction.replace("\"", "") : null;
  }
}
