package it.gov.pagopa.payments.logging;

import com.fasterxml.jackson.databind.node.ObjectNode;
import it.gov.pagopa.payments.model.PaaErrorEnum;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.lang.Nullable;

/**
 * Request-local metadata shared across servlet dispatches. It retains no payload or thread-local
 * snapshot; completion is claimed once after the container has handled any error dispatch.
 */
public final class HttpRequestLogContext {

  public static final String CORRELATION_ID = "correlation_id";
  public static final String METHOD = "method";
  public static final String STATUS = "status";
  public static final String STATUS_OK = "OK";
  public static final String STATUS_KO = "KO";
  public static final String REQUEST_ID_HEADER = "X-Request-Id";
  private static final String ATTRIBUTE = HttpRequestLogContext.class.getName();

  private final String correlationId;
  private final long startedNanos = System.nanoTime();
  private final AtomicBoolean completed = new AtomicBoolean();
  // The servlet lifecycle owns response access; volatile only publishes the latest dispatch wrapper.
  @SuppressWarnings("java:S3077")
  private volatile HttpServletResponse response;
  private volatile String operationMethod;
  private volatile boolean failed;
  private volatile String soapOutcome;
  private volatile String soapFaultCode;
  private volatile String faultCode;
  private volatile String faultDetail;
  private volatile String creditorId;
  private volatile String noticeNumber;
  private volatile String iuv;
  // Published as an owned snapshot and never mutated; readers receive a defensive copy.
  @SuppressWarnings("java:S3077")
  private volatile ObjectNode requestArgs;

  public HttpRequestLogContext(String correlationId, HttpServletResponse response) {
    this.correlationId = correlationId;
    this.response = response;
  }

  public static HttpRequestLogContext from(HttpServletRequest request) {
    return (HttpRequestLogContext) request.getAttribute(ATTRIBUTE);
  }

  public void attach(HttpServletRequest request, HttpServletResponse response) {
    request.setAttribute(ATTRIBUTE, this);
    this.response = response;
  }

  public String correlationId() {
    return correlationId;
  }

  /** Sets the logical API operation, not the HTTP verb. */
  public void setMethod(String method) {
    this.operationMethod = method;
    MDC.put(METHOD, method);
  }

  public void markFailure() {
    failed = true;
    soapOutcome = null;
    soapFaultCode = null;
    faultCode = null;
    faultDetail = null;
  }

  /** Records only response metadata; a technical SOAP Fault has a code but no application outcome. */
  public void recordSoapResponseLog(String outcome, String faultCode) {
    recordSoapResponseLog(outcome, faultCode, null);
  }

  public void recordSoapResponseLog(String outcome, String faultCode, String faultDetail) {
    soapOutcome = outcome;
    soapFaultCode = faultCode;
    this.faultCode = faultCode;
    this.faultDetail = faultDetail;
    if (STATUS_KO.equals(outcome) || faultCode != null) {
      failed = true;
    }
  }

  /** Records only the REST error response's title and detail, never its entire body. */
  public void recordRestFaultLog(String code, String detail) {
    faultCode = code;
    faultDetail = detail;
  }

  public void addResponseFaultLogFields(ObjectNode fields) {
    if (faultCode != null) {
      fields.put("faultCode", faultCode);
    }
    if (faultDetail != null) {
      fields.put("faultDetail", faultDetail);
    }
  }

  /** Adds the application SOAP outcome only to the final request summary. */
  public void populateSoapResponseLogMdc() {
    if (soapOutcome != null) {
      MDC.put("ctx_soap_outcome", soapOutcome);
    }
  }

  public boolean hasSoapSystemErrorForLogging() {
    return PaaErrorEnum.PAA_SYSTEM_ERROR.getFaultCode().equals(soapFaultCode);
  }

  /** Stores only the approved projection, never the original HTTP or SOAP payload. */
  public void recordRequestProjectionLog(RequestLogProjection.Projection projection) {
    creditorId = projection.creditorId();
    noticeNumber = projection.noticeNumber();
    iuv = projection.iuv();
    requestArgs = projection.args();
    populatePaymentLogMdc();
  }

  public void recordResponseIuvLog(String value) {
    if (value != null) {
      iuv = value;
      MDC.put("ctx_iuv", value);
    }
  }

  /** Arguments are event-local and emitted once; dependency events must not inherit them via MDC. */
  public void addRequestLogFields(ObjectNode fields) {
    ObjectNode args = requestArgs;
    if (args != null && !args.isEmpty()) {
      fields.set("args", args.deepCopy());
    }
  }

  private void populatePaymentLogMdc() {
    if (creditorId != null) {
      MDC.put("ctx_creditor_id", creditorId);
    }
    if (noticeNumber != null) {
      MDC.put("ctx_notice_number", noticeNumber);
    }
    if (iuv != null) {
      MDC.put("ctx_iuv", iuv);
    }
  }

  /** Rebuilds only known request metadata, not MDC left on a reused container thread. */
  public void populateMdc() {
    MDC.put(CORRELATION_ID, correlationId);
    String method = operationMethod;
    if (method != null) {
      MDC.put(METHOD, method);
    }
    populatePaymentLogMdc();
  }

  public boolean claimCompletion() {
    return completed.compareAndSet(false, true);
  }

  public long responseTimeMillis() {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
  }

  public int responseStatus() {
    return response.getStatus();
  }

  /** Keeps the operation result distinct from the HTTP code and any SOAP application outcome. */
  @Nullable
  public String operationStatus() {
    int status = responseStatus();
    if (failed || status >= 400) {
      return STATUS_KO;
    }
    return status >= 200 && status < 400 ? STATUS_OK : null;
  }
}
