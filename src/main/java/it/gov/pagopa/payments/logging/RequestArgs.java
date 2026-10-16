package it.gov.pagopa.payments.logging;

import static it.gov.pagopa.payments.logging.helper.RequestProjectionJson.booleanField;
import static it.gov.pagopa.payments.logging.helper.RequestProjectionJson.dateTimeField;
import static it.gov.pagopa.payments.logging.helper.RequestProjectionJson.integerField;
import static it.gov.pagopa.payments.logging.helper.RequestProjectionJson.stringField;

import it.gov.pagopa.payments.controller.DeadLetterController;
import it.gov.pagopa.payments.controller.receipt.impl.PaymentsController;
import it.gov.pagopa.payments.logging.helper.RequestProjectionJson;
import it.gov.pagopa.payments.model.partner.PaDemandPaymentNoticeRequest;
import it.gov.pagopa.payments.model.partner.PaGetPaymentReq;
import it.gov.pagopa.payments.model.partner.PaGetPaymentV2Request;
import it.gov.pagopa.payments.model.partner.PaSendRTReq;
import it.gov.pagopa.payments.model.partner.PaSendRTV2Request;
import it.gov.pagopa.payments.model.partner.PaVerifyPaymentNoticeReq;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.springframework.core.convert.ConversionException;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Centralized, operation-specific request allowlists for final-summary args. Only explicitly named
 * fields are read; headers, personal data and future schema additions stay excluded.
 * Returned JSON is operation-local data, not a logging or MDC side effect.
 */
public final class RequestArgs {
  private static final List<RequestProjectionJson.Field<PaVerifyPaymentNoticeReq>> PA_VERIFY =
      List.of(
          stringField("idBrokerPA", PaVerifyPaymentNoticeReq::getIdBrokerPA),
          stringField("idStation", PaVerifyPaymentNoticeReq::getIdStation));

  private static final List<RequestProjectionJson.Field<PaGetPaymentReq>> PA_GET = List.of(
      stringField("idBrokerPA", PaGetPaymentReq::getIdBrokerPA),
      stringField("idStation", PaGetPaymentReq::getIdStation),
      stringField("transferType", r -> r.getTransferType() == null
          ? null : r.getTransferType().value()),
      dateTimeField("dueDate", PaGetPaymentReq::getDueDate));

  private static final List<RequestProjectionJson.Field<PaGetPaymentV2Request>> PA_GET_V2 = List.of(
      stringField("idBrokerPA", PaGetPaymentV2Request::getIdBrokerPA),
      stringField("idStation", PaGetPaymentV2Request::getIdStation),
      stringField("transferType", r -> r.getTransferType() == null
          ? null : r.getTransferType().value()),
      dateTimeField("dueDate", PaGetPaymentV2Request::getDueDate));

  private static final List<RequestProjectionJson.Field<PaDemandPaymentNoticeRequest>> PA_DEMAND =
      List.of(
          stringField("idBrokerPA", PaDemandPaymentNoticeRequest::getIdBrokerPA),
          stringField("idStation", PaDemandPaymentNoticeRequest::getIdStation),
          stringField("idServizio", PaDemandPaymentNoticeRequest::getIdServizio));

  private static final List<RequestProjectionJson.Field<PaSendRTReq>> PA_SEND_RT = List.of(
      stringField("idBrokerPA", PaSendRTReq::getIdBrokerPA),
      stringField("idStation", PaSendRTReq::getIdStation),
      stringField("receiptId", r -> r.getReceipt() == null ? null : r.getReceipt().getReceiptId()),
      stringField("idPSP", r -> r.getReceipt() == null ? null : r.getReceipt().getIdPSP()),
      stringField("creditorReferenceId",
          r -> r.getReceipt() == null ? null : r.getReceipt().getCreditorReferenceId()),
      dateTimeField("paymentDateTime",
          r -> r.getReceipt() == null ? null : r.getReceipt().getPaymentDateTime()),
      booleanField("standIn", r -> r.getReceipt() == null ? null : r.getReceipt().isStandIn()));

  private static final List<RequestProjectionJson.Field<PaSendRTV2Request>> PA_SEND_RT_V2 = List.of(
      stringField("idBrokerPA", PaSendRTV2Request::getIdBrokerPA),
      stringField("idStation", PaSendRTV2Request::getIdStation),
      stringField("receiptId", r -> r.getReceipt() == null ? null : r.getReceipt().getReceiptId()),
      stringField("idPSP", r -> r.getReceipt() == null ? null : r.getReceipt().getIdPSP()),
      stringField("creditorReferenceId",
          r -> r.getReceipt() == null ? null : r.getReceipt().getCreditorReferenceId()),
      dateTimeField("paymentDateTime",
          r -> r.getReceipt() == null ? null : r.getReceipt().getPaymentDateTime()),
      booleanField("standIn", r -> r.getReceipt() == null ? null : r.getReceipt().isStandIn()),
      stringField("idBundle", r -> r.getReceipt() == null ? null : r.getReceipt().getIdBundle()));

  private record ReceiptLookup(String iuv, String segregationCodes) {}
  private record ReceiptSearch(
      Integer pageNum, Integer pageSize, String service, String from, String to,
      String segregationCodes) {}
  private record DeadLetterList(Integer maxMessages) {}
  private record DeadLetterDetail(String filename) {}

  private static final List<RequestProjectionJson.Field<ReceiptLookup>> RECEIPT_LOOKUP = List.of(
      stringField("iuv", ReceiptLookup::iuv),
      stringField("segregationCodes", ReceiptLookup::segregationCodes));
  private static final List<RequestProjectionJson.Field<ReceiptSearch>> RECEIPT_SEARCH = List.of(
      integerField("pageNum", ReceiptSearch::pageNum),
      integerField("pageSize", ReceiptSearch::pageSize),
      stringField("service", ReceiptSearch::service),
      stringField("from", ReceiptSearch::from),
      stringField("to", ReceiptSearch::to),
      stringField("segregationCodes", ReceiptSearch::segregationCodes));
  private static final List<RequestProjectionJson.Field<DeadLetterList>> DEAD_LETTER_LIST =
      List.of(integerField("maxMessages", DeadLetterList::maxMessages));
  private static final List<RequestProjectionJson.Field<DeadLetterDetail>> DEAD_LETTER_DETAIL =
      List.of(stringField("filename", DeadLetterDetail::filename));

  private RequestArgs() {}

  public static String paVerifyPaymentNotice(PaVerifyPaymentNoticeReq request) {
    return RequestProjectionJson.serialize(request, PA_VERIFY);
  }

  public static String paGetPayment(PaGetPaymentReq request) {
    return RequestProjectionJson.serialize(request, PA_GET);
  }

  public static String paGetPaymentV2(PaGetPaymentV2Request request) {
    return RequestProjectionJson.serialize(request, PA_GET_V2);
  }

  public static String paDemandPaymentNotice(PaDemandPaymentNoticeRequest request) {
    return RequestProjectionJson.serialize(request, PA_DEMAND);
  }

  public static String paSendRT(PaSendRTReq request) {
    return RequestProjectionJson.serialize(request, PA_SEND_RT);
  }

  /** Returns the V2 args JSON string, including idBundle only when present; null means omit args. */
  public static String paSendRTV2(PaSendRTV2Request request) {
    return RequestProjectionJson.serialize(request, PA_SEND_RT_V2);
  }

  /**
   * Projects only approved HTTP inputs for concrete application handlers before MVC binding.
   * Invalid numeric input is unavailable, not replaced with a default; MVC still owns validation.
   */
  public static String forRest(HttpServletRequest request, HandlerMethod handler) {
    String method = handler.getMethod().getName();
    if (PaymentsController.class.isAssignableFrom(handler.getBeanType())) {
      return switch (method) {
        case "getReceiptByIUV" -> RequestProjectionJson.serialize(
            new ReceiptLookup(pathVariable(request, "iuv"), request.getParameter("segregationCodes")),
            RECEIPT_LOOKUP);
        case "getOrganizationReceipts" -> RequestProjectionJson.serialize(
            new ReceiptSearch(integerParameter(request, "pageNum", 0),
                integerParameter(request, "pageSize", 20), request.getParameter("service"),
                request.getParameter("from"), request.getParameter("to"),
                request.getParameter("segregationCodes")), RECEIPT_SEARCH);
        default -> null;
      };
    }
    if (DeadLetterController.class.isAssignableFrom(handler.getBeanType())) {
      return switch (method) {
        case "getDeadLetters" -> RequestProjectionJson.serialize(
            new DeadLetterList(integerParameter(request, "maxMessages", 50)), DEAD_LETTER_LIST);
        case "getDeadLetter" -> RequestProjectionJson.serialize(
            new DeadLetterDetail(request.getParameter("filename")), DEAD_LETTER_DETAIL);
        default -> null;
      };
    }
    // home/info have no inputs; unregistered handlers never inherit another method's allowlist.
    return null;
  }

  private static String pathVariable(HttpServletRequest request, String name) {
    Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
    return variables instanceof Map<?, ?> path && path.get(name) instanceof String value
        ? value : null;
  }

  private static Integer integerParameter(HttpServletRequest request, String name, int defaultValue) {
    String value = request.getParameter(name);
    if (value == null || value.isEmpty()) {
      return defaultValue;
    }
    try {
      return DefaultConversionService.getSharedInstance().convert(value, Integer.class);
    } catch (ConversionException invalid) {
      return null;
    }
  }
}
