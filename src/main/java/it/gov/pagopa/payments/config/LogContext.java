package it.gov.pagopa.payments.config;

import it.gov.pagopa.payments.model.PaymentOptionModelResponse;
import it.gov.pagopa.payments.model.PaymentsModelResponse;
import it.gov.pagopa.payments.model.partner.CtQrCode;
import it.gov.pagopa.payments.model.partner.CtReceipt;
import it.gov.pagopa.payments.model.partner.CtReceiptV2;
import it.gov.pagopa.payments.model.partner.PaDemandPaymentNoticeRequest;
import it.gov.pagopa.payments.model.partner.PaGetPaymentReq;
import it.gov.pagopa.payments.model.partner.PaGetPaymentV2Request;
import it.gov.pagopa.payments.model.partner.PaSendRTReq;
import it.gov.pagopa.payments.model.partner.PaSendRTV2Request;
import it.gov.pagopa.payments.model.partner.PaVerifyPaymentNoticeReq;
import it.gov.pagopa.payments.utils.LogMasker;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.xml.bind.JAXBElement;
import lombok.experimental.UtilityClass;
import org.slf4j.MDC;

/**
 * Business context of a log event.
 *
 * <p>Business identifiers used to search and correlate the logs are promoted to isolated top level
 * fields ({@code ctx.*}), while volatile technical information is nested under {@code
 * ctx.details.*}. Payloads are never serialized: only the identifiers listed here are extracted,
 * so that PII and financial data cannot leak into the logs.
 */
@UtilityClass
public class LogContext {

  /** Notice number (Numero Avviso) of the debt position. */
  public static final String CTX_NAV = "ctx.nav";
  /** Payment identifier (Identificativo Univoco Versamento). */
  public static final String CTX_IUV = "ctx.iuv";
  /** Fiscal code of the creditor institution (organization, not a natural person). */
  public static final String CTX_ORGANIZATION_FISCAL_CODE = "ctx.organization.fiscal.code";
  /** Identifier of the payment transaction (receipt id). */
  public static final String CTX_TRANSACTION_ID = "ctx.transaction.id";

  public static final String CTX_DETAILS_STATION = "ctx.details.station";
  public static final String CTX_DETAILS_SERVICE = "ctx.details.service";
  public static final String CTX_DETAILS_STATUS = "ctx.details.status";
  public static final String CTX_DETAILS_FAULT_CODE = "ctx.details.faultCode";
  public static final String CTX_DETAILS_CACHE_VERSION = "ctx.details.cacheVersion";
  public static final String CTX_DETAILS_MESSAGE_ID = "ctx.details.messageId";
  public static final String CTX_DETAILS_VALIDATION_ERRORS = "ctx.details.validationErrors";

  private static final String DETAILS_PREFIX = "ctx.details.";

  private static final Set<String> BUSINESS_KEYS =
      Set.of(CTX_NAV, CTX_IUV, CTX_ORGANIZATION_FISCAL_CODE, CTX_TRANSACTION_ID);

  private static final Set<String> CONTEXT_KEYS = buildContextKeys();

  private static Set<String> buildContextKeys() {
    Set<String> keys = new LinkedHashSet<>(BUSINESS_KEYS);
    keys.add(CTX_DETAILS_STATION);
    keys.add(CTX_DETAILS_SERVICE);
    keys.add(CTX_DETAILS_STATUS);
    keys.add(CTX_DETAILS_FAULT_CODE);
    keys.add(CTX_DETAILS_CACHE_VERSION);
    keys.add(CTX_DETAILS_MESSAGE_ID);
    keys.add(CTX_DETAILS_VALIDATION_ERRORS);
    return keys;
  }

  /**
   * Publishes a business identifier as an isolated top level field. It is meant for the flows that
   * are not intercepted by the logging aspect, e.g. the messages consumed from a queue.
   *
   * @param key one of the {@code CTX_*} business keys of this class
   * @param value the value to publish, ignored when {@code null} or blank
   */
  public static void putIdentifier(String key, String value) {
    if (!BUSINESS_KEYS.contains(key)) {
      throw new IllegalArgumentException("Not a business identifier: " + key);
    }
    put(key, value);
  }

  /**
   * Publishes a volatile technical information in the nested {@code ctx.details} object. It is
   * removed when the enclosing operation ends.
   *
   * @param key one of the {@code CTX_DETAILS_*} keys of this class
   * @param value the value to publish, ignored when {@code null}
   */
  public static void putDetail(String key, Object value) {
    if (!key.startsWith(DETAILS_PREFIX)) {
      throw new IllegalArgumentException("Only ctx.details fields can host volatile data: " + key);
    }
    if (value != null) {
      MDC.put(key, String.valueOf(value));
    }
  }

  /**
   * Extracts the business identifiers from the arguments of an intercepted method and publishes
   * them in the MDC as top level {@code ctx.*} fields.
   *
   * @param args the arguments of the intercepted method
   * @param parameterNames the names of the parameters, aligned with {@code args}
   * @return the previous values of the touched MDC keys, to be restored with {@link
   *     #restore(Map)}
   */
  public static Map<String, String> enrich(Object[] args, String[] parameterNames) {
    Map<String, String> previousValues = snapshot();
    if (args == null) {
      return previousValues;
    }
    for (int i = 0; i < args.length; i++) {
      String parameterName = parameterNames != null && i < parameterNames.length ? parameterNames[i] : null;
      enrichFrom(args[i], parameterName);
    }
    return previousValues;
  }

  /**
   * Extracts the business identifiers from the result of an intercepted method, filling only the
   * fields that are still unknown.
   *
   * @param result the returned value of the intercepted method
   */
  public static void enrichFromResult(Object result) {
    enrichFrom(result, null);
  }

  /**
   * Restores the MDC context fields to the given values, so that nested invocations do not leak
   * their identifiers to the caller.
   *
   * @param previousValues the values returned by {@link #enrich(Object[], String[])}
   */
  public static void restore(Map<String, String> previousValues) {
    for (String key : CONTEXT_KEYS) {
      String previousValue = previousValues.get(key);
      if (previousValue == null) {
        MDC.remove(key);
      } else {
        MDC.put(key, previousValue);
      }
    }
  }

  /** Removes every context field from the MDC. */
  public static void clear() {
    CONTEXT_KEYS.forEach(MDC::remove);
  }

  private static Map<String, String> snapshot() {
    Map<String, String> values = new HashMap<>();
    for (String key : CONTEXT_KEYS) {
      String value = MDC.get(key);
      if (value != null) {
        values.put(key, value);
      }
    }
    return values;
  }

  private static void enrichFrom(Object value, String parameterName) {
    if (value instanceof JAXBElement<?> jaxbElement) {
      enrichFrom(jaxbElement.getValue(), parameterName);
      return;
    }
    if (value instanceof PaVerifyPaymentNoticeReq request) {
      put(CTX_ORGANIZATION_FISCAL_CODE, request.getIdPA());
      put(CTX_DETAILS_STATION, request.getIdStation());
      enrichFromQrCode(request.getQrCode());
    } else if (value instanceof PaGetPaymentReq request) {
      put(CTX_ORGANIZATION_FISCAL_CODE, request.getIdPA());
      put(CTX_DETAILS_STATION, request.getIdStation());
      enrichFromQrCode(request.getQrCode());
    } else if (value instanceof PaGetPaymentV2Request request) {
      put(CTX_ORGANIZATION_FISCAL_CODE, request.getIdPA());
      put(CTX_DETAILS_STATION, request.getIdStation());
      enrichFromQrCode(request.getQrCode());
    } else if (value instanceof PaSendRTReq request) {
      put(CTX_ORGANIZATION_FISCAL_CODE, request.getIdPA());
      put(CTX_DETAILS_STATION, request.getIdStation());
      enrichFromReceipt(request.getReceipt());
    } else if (value instanceof PaSendRTV2Request request) {
      put(CTX_ORGANIZATION_FISCAL_CODE, request.getIdPA());
      put(CTX_DETAILS_STATION, request.getIdStation());
      enrichFromReceipt(request.getReceipt());
    } else if (value instanceof PaDemandPaymentNoticeRequest request) {
      put(CTX_ORGANIZATION_FISCAL_CODE, request.getIdPA());
      put(CTX_DETAILS_STATION, request.getIdStation());
      put(CTX_DETAILS_SERVICE, request.getIdServizio());
    } else if (value instanceof CtQrCode qrCode) {
      enrichFromQrCode(qrCode);
    } else if (value instanceof PaymentsModelResponse paymentOption) {
      put(CTX_NAV, paymentOption.getNav());
      put(CTX_IUV, paymentOption.getIuv());
      put(CTX_ORGANIZATION_FISCAL_CODE, paymentOption.getOrganizationFiscalCode());
    } else if (value instanceof PaymentOptionModelResponse paymentOption) {
      put(CTX_NAV, paymentOption.getNav());
      put(CTX_IUV, paymentOption.getIuv());
      put(CTX_ORGANIZATION_FISCAL_CODE, paymentOption.getOrganizationFiscalCode());
      put(CTX_TRANSACTION_ID, paymentOption.getIdReceipt());
    } else if (value instanceof String stringValue) {
      enrichFromNamedParameter(parameterName, stringValue);
    }
  }

  private static void enrichFromQrCode(CtQrCode qrCode) {
    if (qrCode == null) {
      return;
    }
    put(CTX_NAV, qrCode.getNoticeNumber());
    put(CTX_ORGANIZATION_FISCAL_CODE, qrCode.getFiscalCode());
  }

  private static void enrichFromReceipt(CtReceipt receipt) {
    if (receipt == null) {
      return;
    }
    put(CTX_NAV, receipt.getNoticeNumber());
    put(CTX_IUV, receipt.getCreditorReferenceId());
    put(CTX_ORGANIZATION_FISCAL_CODE, receipt.getFiscalCode());
    put(CTX_TRANSACTION_ID, receipt.getReceiptId());
  }

  private static void enrichFromReceipt(CtReceiptV2 receipt) {
    if (receipt == null) {
      return;
    }
    put(CTX_NAV, receipt.getNoticeNumber());
    put(CTX_IUV, receipt.getCreditorReferenceId());
    put(CTX_ORGANIZATION_FISCAL_CODE, receipt.getFiscalCode());
    put(CTX_TRANSACTION_ID, receipt.getReceiptId());
  }

  /**
   * Only the parameters that carry a business identifier are logged: any other value (debtor,
   * e-mail, free text filters) is PII and is deliberately dropped.
   */
  private static void enrichFromNamedParameter(String parameterName, String value) {
    if (parameterName == null) {
      return;
    }
    switch (parameterName) {
      case "organizationFiscalCode", "idPa", "idPA", "receiptFiscalCode" ->
          put(CTX_ORGANIZATION_FISCAL_CODE, value);
      case "iuv", "creditorReferenceId" -> put(CTX_IUV, value);
      case "nav", "noticeNumber" -> put(CTX_NAV, value);
      case "transactionId", "receiptId", "idReceipt" -> put(CTX_TRANSACTION_ID, value);
      case "station", "stationId", "idStation" -> put(CTX_DETAILS_STATION, value);
      case "serviceType" -> put(CTX_DETAILS_SERVICE, value);
      default -> {
        // any other parameter may contain PII and must not be logged
      }
    }
  }

  private static void put(String key, String value) {
    if (value == null || value.isBlank()) {
      return;
    }
    String safeValue =
        CTX_ORGANIZATION_FISCAL_CODE.equals(key) ? LogMasker.maskIfPersonal(value) : value;
    if (safeValue != null) {
      MDC.put(key, safeValue);
    }
  }
}
