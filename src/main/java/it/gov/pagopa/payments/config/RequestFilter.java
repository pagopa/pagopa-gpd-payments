package it.gov.pagopa.payments.config;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.gov.pagopa.payments.logging.HttpRequestLogContext;
import it.gov.pagopa.payments.logging.StructuredLogFields;
import it.gov.pagopa.payments.utils.CommonUtil;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class RequestFilter implements Filter {

  private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

  /**
   * Scopes correlation to each servlet dispatch and restores the exact previous MDC. The final
   * summary is deferred to request destruction, after error handling or asynchronous completion.
   *
   * @param request http request
   * @param response http response
   * @param chain next filter
   * @throws IOException if an I/O error occurs during this filter's processing of the request
   * @throws ServletException if the processing fails for any other reason
   */
  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    if (!(request instanceof HttpServletRequest httpRequest)
        || !(response instanceof HttpServletResponse httpResponse)) {
      chain.doFilter(request, response);
      return;
    }
    Map<String, String> previousMdc = MDC.getCopyOfContextMap();
    HttpRequestLogContext context = HttpRequestLogContext.from(httpRequest);
    try {
      MDC.clear();
      if (context == null) {
        context = new HttpRequestLogContext(correlationId(httpRequest), httpResponse);
      }
      context.attach(httpRequest, httpResponse);
      context.populateMdc();
      httpResponse.setHeader(HttpRequestLogContext.REQUEST_ID_HEADER, context.correlationId());
      chain.doFilter(request, response);
    } catch (IOException | ServletException | RuntimeException | Error failure) {
      if (context != null) {
        context.markFailure();
      }
      throw failure;
    } finally {
      restoreMdc(previousMdc);
    }
  }

  private String correlationId(HttpServletRequest request) {
    String incoming = request.getHeader(HttpRequestLogContext.REQUEST_ID_HEADER);
    if (incoming != null && SAFE_REQUEST_ID.matcher(incoming).matches()) {
      return incoming;
    }
    if (incoming != null) {
      log.warn("Invalid X-Request-Id replaced");
    }
    return UUID.randomUUID().toString();
  }

  /** Called once by the servlet lifecycle listener, not from a potentially provisional dispatch. */
  public void logCompletedRequest(HttpServletRequest request) {
    HttpRequestLogContext context = HttpRequestLogContext.from(request);
    if (context == null || !context.claimCompletion()) {
      return;
    }
    Map<String, String> previousMdc = MDC.getCopyOfContextMap();
    try {
      MDC.clear();
      context.populateMdc();
      context.populateSoapResponseLogMdc();
      String status = context.operationStatus();
      if (status != null) {
        MDC.put(HttpRequestLogContext.STATUS, status);
      } else {
        log.warn("HTTP operation result unavailable at request completion");
      }
      ObjectNode fields = JsonNodeFactory.instance.objectNode();
      fields.put("event.duration", context.durationNanos());
      fields.put("http.response.status_code", context.responseStatus());
      context.addRequestLogFields(fields);
      fields.with("args").put("serviceType", CommonUtil.getServiceType(request));
      StructuredLogFields structured = new StructuredLogFields(fields);
      if (context.hasSoapSystemErrorForLogging()) {
        log.error("HTTP request completed", structured);
      } else if ("healthCheck".equals(MDC.get(HttpRequestLogContext.METHOD))) {
        if (HttpRequestLogContext.STATUS_OK.equals(status)) {
          log.debug("HTTP request completed", structured);
        } else {
          log.warn("HTTP request completed", structured);
        }
      } else {
        log.info("HTTP request completed", structured);
      }
    } finally {
      restoreMdc(previousMdc);
    }
  }

  private void restoreMdc(Map<String, String> previous) {
    if (previous == null) {
      MDC.clear();
    } else {
      MDC.setContextMap(previous);
    }
  }
}
