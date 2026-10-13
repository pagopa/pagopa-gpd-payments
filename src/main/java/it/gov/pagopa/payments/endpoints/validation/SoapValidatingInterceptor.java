package it.gov.pagopa.payments.endpoints.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.gov.pagopa.payments.config.LoggingAspect;
import it.gov.pagopa.payments.endpoints.validation.exceptions.PartnerValidationException;
import it.gov.pagopa.payments.model.PaaErrorEnum;
import java.io.IOException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.xml.soap.SOAPException;
import javax.xml.soap.SOAPMessage;
import javax.xml.transform.dom.DOMSource;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.soap.saaj.SaajSoapMessage;
import org.springframework.ws.soap.server.endpoint.interceptor.PayloadValidatingInterceptor;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

@Slf4j
public class SoapValidatingInterceptor extends PayloadValidatingInterceptor {

  private static final String SOAP_PREFIX = "soapenv";
  private static final String EXCLUDED_PRIMITIVES = "paDemandPaymentNoticeResponse";
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final Pattern XSD_ERROR_KEY =
      Pattern.compile("^((?:cvc|s4s|src|cos|sch-props|schema_reference|wf)-[A-Za-z0-9_.-]+):");
  private final List<String> amountNodeElements =
      List.of("paymentAmount", "amount", "transferAmount");

  @Override
  protected boolean handleRequestValidationErrors(
      MessageContext messageContext, SAXParseException[] errors) {

    if (errors.length > 0) {
      var details = OBJECT_MAPPER.createObjectNode();
      var validationErrors = details.putArray("xsd_errors");
      for (var error : errors) {
        var matcher =
            XSD_ERROR_KEY.matcher(error.getMessage() == null ? "" : error.getMessage());
        validationErrors
            .addObject()
            .put("line", error.getLineNumber())
            .put("column", error.getColumnNumber())
            .put("key", matcher.find() ? matcher.group(1) : "invalid-xml");
      }
      try {
        MDC.put("ctx_details", details.toString());
        log.warn("SOAP request XSD validation failed");
      } finally {
        MDC.remove("ctx_details");
      }
      throw new PartnerValidationException(PaaErrorEnum.PAA_SINTASSI_XSD);
    }
    return true;
  }

  @Override
  public boolean handleResponse(MessageContext messageContext, Object endpoint)
      throws IOException, SAXException {

    SaajSoapMessage soapResponse = (SaajSoapMessage) messageContext.getResponse();
    alterSoapEnvelope(soapResponse);
    return super.handleResponse(messageContext, endpoint);
  }

  @Override
  public void afterCompletion(MessageContext messageContext, Object endpoint, Exception exception) {
    clearResponseMetadata();
    // Read the final response, not an intermediate payload that validation may replace.
    if (exception == null
        && messageContext.hasResponse()
        && messageContext.getResponse() instanceof SaajSoapMessage soapResponse) {
      DOMSource body = (DOMSource) soapResponse.getSoapBody().getSource();
      recordResponseOutcome(body.getNode());
    }
  }

  static void recordResponseOutcome(Node body) {
    clearResponseMetadata();
    if (body == null) {
      return;
    }
    if (body.getNodeType() == Node.DOCUMENT_NODE) {
      body = firstElement(body);
    }
    if (body == null) {
      return;
    }
    Node result = "Body".equals(localName(body)) ? firstElement(body) : body;
    if (result == null) {
      return;
    }
    for (Node child = result.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (child.getNodeType() != Node.ELEMENT_NODE) {
        continue;
      }
      if ("outcome".equals(localName(child)) && firstElement(child) == null) {
        String outcome = child.getTextContent().strip();
        if (LoggingAspect.STATUS_OK.equals(outcome) || LoggingAspect.STATUS_KO.equals(outcome)) {
          MDC.put(LoggingAspect.SOAP_OUTCOME, outcome);
        }
      } else if ("fault".equals(localName(child))) {
        for (Node code = child.getFirstChild(); code != null; code = code.getNextSibling()) {
          if (code.getNodeType() == Node.ELEMENT_NODE
              && "faultCode".equals(localName(code))
              && firstElement(code) == null) {
            String faultCode = code.getTextContent().strip();
            if (!faultCode.isEmpty()) {
              MDC.put(LoggingAspect.FAULT_CODE, faultCode);
              MDC.put(LoggingAspect.ERROR_CODE, faultCode);
            }
            break;
          }
        }
      }
    }
  }

  static void clearResponseMetadata() {
    MDC.remove(LoggingAspect.SOAP_OUTCOME);
    MDC.remove(LoggingAspect.FAULT_CODE);
    MDC.remove(LoggingAspect.ERROR_CODE);
    MDC.remove(LoggingAspect.FAULT_DETAIL);
  }

  private static Node firstElement(Node parent) {
    for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (child.getNodeType() == Node.ELEMENT_NODE) {
        return child;
      }
    }
    return null;
  }

  private static String localName(Node node) {
    if (node.getLocalName() != null) {
      return node.getLocalName();
    }
    String name = node.getNodeName();
    return name.substring(name.indexOf(':') + 1);
  }

  private void alterSoapEnvelope(SaajSoapMessage soapResponse) {

    try {
      SOAPMessage soapMessage = soapResponse.getSaajMessage();
      soapMessage.getSOAPPart().getEnvelope().removeNamespaceDeclaration("SOAP-ENV");
      soapMessage.getSOAPPart().getEnvelope().setPrefix(SOAP_PREFIX);
      soapMessage.getSOAPHeader().setPrefix(SOAP_PREFIX);
      soapMessage.getSOAPBody().setPrefix(SOAP_PREFIX);

      fixNumberAmountFormat(soapMessage);

      soapMessage.saveChanges();
    } catch (SOAPException e) {

      log.error("Processing SOAP envelope failed", e);
    }
  }

  private void fixNumberAmountFormat(SOAPMessage soapMessage) throws SOAPException {
    var bodyElement = soapMessage.getSOAPBody().getFirstChild();
    if (bodyElement != null && EXCLUDED_PRIMITIVES.contains(bodyElement.getLocalName())) {
      // The vertical service already returns amounts in euros (e.g. "16.00"),
      // applying the cents-to-euros conversion here would corrupt the value (e.g. "0.16").
      return;
    }

    for (String nodeElement : amountNodeElements) {
      var nodeList = soapMessage.getSOAPBody().getElementsByTagName(nodeElement);
      for (int i = 0; i < nodeList.getLength(); i++) {
        var node = nodeList.item(i);
        if (node != null) {
          var amount = node.getFirstChild().getNodeValue();
          DecimalFormat dec =
              new DecimalFormat(
                  "#0.00", DecimalFormatSymbols.getInstance(Locale.US)); // format example: 10.99
          double number = Double.parseDouble(amount) / 100.0; // amount is in cents (1099)
          node.getFirstChild().setNodeValue(dec.format(number));
        }
      }
    }
  }
}
