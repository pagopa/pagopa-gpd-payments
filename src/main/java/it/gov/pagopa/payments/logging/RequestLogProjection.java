package it.gov.pagopa.payments.logging;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.gov.pagopa.payments.controller.BaseController;
import it.gov.pagopa.payments.controller.DeadLetterController;
import it.gov.pagopa.payments.controller.receipt.impl.PaymentsController;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Explicit, payload-free logging allowlists for the application's boundary operations. */
public final class RequestLogProjection {

  private static final Logger LOG = LoggerFactory.getLogger(RequestLogProjection.class);

  private RequestLogProjection() {}

  public record Projection(String creditorId, String noticeNumber, String iuv, ObjectNode args) {
    public Projection {
      args = args == null || args.isEmpty() ? null : args.deepCopy();
    }

    @Override
    public ObjectNode args() {
      return args == null ? null : args.deepCopy();
    }
  }

  public static Projection soapRequest(String operation, Element payload) {
    if (operation == null || payload == null) {
      return emptyProjection();
    }
    ObjectNode args = JsonNodeFactory.instance.objectNode();
    switch (operation) {
      case "paVerifyPaymentNotice", "paGetPayment", "paGetPaymentV2" -> {
        soapCommonArgs(args, payload);
        if (!"paVerifyPaymentNotice".equals(operation)) {
          put(args, "transferType", text(payload, "transferType"));
          put(args, "dueDate", text(payload, "dueDate"));
        }
        return new Projection(
            text(payload, "idPA"), text(child(payload, "qrCode"), "noticeNumber"), null, args);
      }
      case "paDemandPaymentNotice" -> {
        soapCommonArgs(args, payload);
        put(args, "idServizio", text(payload, "idServizio"));
        return new Projection(text(payload, "idPA"), null, null, args);
      }
      case "paSendRT", "paSendRTV2" -> {
        soapCommonArgs(args, payload);
        Element receipt = child(payload, "receipt");
        put(args, "receiptId", text(receipt, "receiptId"));
        put(args, "idPSP", text(receipt, "idPSP"));
        put(args, "paymentDateTime", text(receipt, "paymentDateTime"));
        putSoapBoolean(args, "standIn", text(receipt, "standIn"));
        if ("paSendRTV2".equals(operation)) {
          put(args, "idBundle", text(receipt, "idBundle"));
        }
        String creditor = text(payload, "idPA");
        return new Projection(
            creditor == null ? text(receipt, "fiscalCode") : creditor,
            text(receipt, "noticeNumber"),
            text(receipt, "creditorReferenceId"),
            args);
      }
      default -> {
        return emptyProjection();
      }
    }
  }

  public static Projection restRequest(HandlerMethod handler, HttpServletRequest request) {
    if (handler == null || request == null) {
      return emptyProjection();
    }
    Class<?> controller = handler.getBeanType();
    String method = handler.getMethod().getName();
    ObjectNode args = JsonNodeFactory.instance.objectNode();
    if (PaymentsController.class.isAssignableFrom(controller)) {
      switch (method) {
        case "getReceiptByIUV" -> {
          put(args, "segregationCodes", request.getParameter("segregationCodes"));
          return new Projection(
              pathVariable(request, "organizationfiscalcode"), null,
              pathVariable(request, "iuv"), args);
        }
        case "getOrganizationReceipts" -> {
          putRestInteger(args, request, "pageNum", 0);
          putRestInteger(args, request, "pageSize", 20);
          put(args, "service", request.getParameter("service"));
          put(args, "from", request.getParameter("from"));
          put(args, "to", request.getParameter("to"));
          put(args, "segregationCodes", request.getParameter("segregationCodes"));
          return new Projection(pathVariable(request, "organizationfiscalcode"), null, null, args);
        }
        default -> {
          return emptyProjection();
        }
      }
    }
    if (DeadLetterController.class.isAssignableFrom(controller)) {
      switch (method) {
        case "getDeadLetters" -> {
          putRestInteger(args, request, "maxMessages", 50);
          return new Projection(null, null, null, args);
        }
        case "getDeadLetter" -> {
          // Blob filenames are deliberately outside this conservative allowlist.
          return emptyProjection();
        }
        default -> {
          return emptyProjection();
        }
      }
    }
    if (BaseController.class.isAssignableFrom(controller)) {
      switch (method) {
        case "home", "healthCheck" -> {
          return emptyProjection();
        }
        default -> {
          return emptyProjection();
        }
      }
    }
    return emptyProjection();
  }

  public static String soapResponseIuv(Element payload) {
    if (payload == null) {
      return null;
    }
    return switch (localName(payload)) {
      case "paGetPaymentRes", "paGetPaymentV2Response" ->
          text(child(payload, "data"), "creditorReferenceId");
      // The current verify response XSD contains no creditorReferenceId.
      case "paVerifyPaymentNoticeRes" -> null;
      default -> null;
    };
  }

  private static Projection emptyProjection() {
    return new Projection(null, null, null, null);
  }

  private static void soapCommonArgs(ObjectNode args, Element payload) {
    put(args, "idBrokerPA", text(payload, "idBrokerPA"));
    put(args, "idStation", text(payload, "idStation"));
  }

  private static void put(ObjectNode args, String field, String value) {
    if (value != null) {
      args.put(field, value);
    }
  }

  private static void putSoapBoolean(ObjectNode args, String field, String value) {
    if (value == null) {
      return;
    }
    switch (value.trim()) {
      case "true", "1" -> args.put(field, true);
      case "false", "0" -> args.put(field, false);
      default -> LOG.debug("Request logging projection omitted invalid boolean field {}", field);
    }
  }

  private static void putRestInteger(
      ObjectNode args, HttpServletRequest request, String field, int defaultValue) {
    String value = request.getParameter(field);
    if (value == null || value.isEmpty()) {
      args.put(field, defaultValue);
      return;
    }
    try {
      args.put(field, Integer.parseInt(value.trim()));
    } catch (NumberFormatException exception) {
      LOG.debug("Request logging projection omitted invalid integer field {}", field);
    }
  }

  private static String pathVariable(HttpServletRequest request, String name) {
    Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
    if (variables instanceof Map<?, ?> map && map.get(name) instanceof String value) {
      return value;
    }
    return null;
  }

  private static Element child(Element parent, String name) {
    if (parent != null) {
      for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
        if (node instanceof Element element && name.equals(localName(element))) {
          return element;
        }
      }
    }
    return null;
  }

  private static String text(Element parent, String name) {
    Element element = child(parent, name);
    if (element == null) {
      return null;
    }
    StringBuilder value = new StringBuilder();
    for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (node instanceof Element) {
        return null;
      }
      if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
        value.append(node.getNodeValue());
      }
    }
    return value.toString();
  }

  private static String localName(Element element) {
    return element.getLocalName() == null ? element.getNodeName() : element.getLocalName();
  }
}
