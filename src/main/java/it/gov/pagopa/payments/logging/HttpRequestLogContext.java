package it.gov.pagopa.payments.logging;

import com.fasterxml.jackson.databind.node.ObjectNode;
import it.gov.pagopa.payments.model.PaaErrorEnum;
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
  private volatile HttpServletResponse response;
  private volatile String method;
  private volatile boolean failed;
  private volatile String soapOutcome;
  private volatile String soapFaultCode;
  private volatile String creditorId;
  private volatile String noticeNumber;
  private volatile String iuv;
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
    this.method = method;
    MDC.put(METHOD, method);
  }

  public void markFailure() {
    failed = true;
    soapOutcome = null;
    soapFaultCode = null;
  }

  /** Records only response metadata; a technical SOAP Fault has a code but no application outcome. */
  public void recordSoapResponseLog(String outcome, String faultCode) {
    soapOutcome = outcome;
    soapFaultCode = faultCode;
    if (STATUS_KO.equals(outcome) || faultCode != null) {
      failed = true;
    }
  }

  /** Adds SOAP response fields only to the final request summary. */
  public void populateSoapResponseLogMdc() {
    if (soapOutcome != null) {
      MDC.put("ctx_soap_outcome", soapOutcome);
    }
    if (soapFaultCode != null) {
      MDC.put("ctx_soap_fault_code", soapFaultCode);
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
    if (requestArgs != null && !requestArgs.isEmpty()) {
      fields.set("args", requestArgs.deepCopy());
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
    if (method != null) {
      MDC.put(METHOD, method);
    }
    populatePaymentLogMdc();
  }

  public boolean claimCompletion() {
    return completed.compareAndSet(false, true);
  }

  public long durationNanos() {
    return System.nanoTime() - startedNanos;
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
