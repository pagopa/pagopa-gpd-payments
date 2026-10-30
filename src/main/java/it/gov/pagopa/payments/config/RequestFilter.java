package it.gov.pagopa.payments.config;

import static it.gov.pagopa.payments.config.LogContext.ARGS;
import static it.gov.pagopa.payments.config.LogContext.CTX_CREDITOR_ID;
import static it.gov.pagopa.payments.config.LogContext.CTX_IUV;
import static it.gov.pagopa.payments.config.LogContext.CTX_NOTICE_NUMBER;
import static it.gov.pagopa.payments.config.LogContext.CTX_SOAP_OUTCOME;
import static it.gov.pagopa.payments.config.LogContext.FAULT_CODE;
import static it.gov.pagopa.payments.config.LogContext.FAULT_DETAIL;
import static it.gov.pagopa.payments.config.LogContext.HEADER_REQUEST_ID;
import static it.gov.pagopa.payments.config.LogContext.HTTP_CODE;
import static it.gov.pagopa.payments.config.LogContext.METHOD;
import static it.gov.pagopa.payments.config.LogContext.RESPONSE_TIME;
import static it.gov.pagopa.payments.config.LogContext.SERVICE_TYPE;
import static it.gov.pagopa.payments.config.LogContext.STATUS;
import static it.gov.pagopa.payments.config.LogContext.STATUS_KO;
import static it.gov.pagopa.payments.config.LogContext.STATUS_OK;

import com.fasterxml.jackson.databind.node.ObjectNode;
import it.gov.pagopa.payments.model.PaaErrorEnum;
import it.gov.pagopa.payments.utils.CommonUtil;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletRequestEvent;
import javax.servlet.ServletRequestListener;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class RequestFilter implements Filter {
  static final String FAULT_STATUS = RequestFilter.class.getName() + ".faultStatus";
  private static final String START = RequestFilter.class.getName() + ".start";
  private static final String RESPONSE = RequestFilter.class.getName() + ".response";
  private static final String HEALTH_CHECK = "healthCheck";
  private static final String LIVENESS = "liveness";
  private static final String READINESS = "readiness";
  private static final String CONTROLLER_PACKAGE = "it.gov.pagopa.payments.controller";
  private static final Pattern SAFE_ID = Pattern.compile("[a-zA-Z0-9._:-]{1,128}");
  private static final Set<String> HEALTH = Set.of(HEALTH_CHECK, LIVENESS, READINESS);
  private final String livenessPath;
  private final String readinessPath;

  public RequestFilter(
      @Value("${management.endpoints.web.base-path:/actuator}") String managementBasePath) {
    String basePath = "/".equals(managementBasePath) ? "" : managementBasePath;
    livenessPath = String.join("/", basePath, "health", LIVENESS);
    readinessPath = String.join("/", basePath, "health", READINESS);
  }

  @Bean
  public ServletListenerRegistrationBean<ServletRequestListener> requestLoggingListener() {
    // Complete after response serialization/error handling, not at the controller's return.
    return new ServletListenerRegistrationBean<>(new ServletRequestListener() {
      @Override
      public void requestDestroyed(ServletRequestEvent event) {
        logCompletedRequest(event);
      }
    });
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    if (!(request instanceof HttpServletRequest httpRequest)
        || !(response instanceof HttpServletResponse httpResponse)) {
      chain.doFilter(request, response);
      return;
    }
    Map<String, String> previous = MDC.getCopyOfContextMap();
    ObjectNode fields = LogContext.fields(httpRequest);
    if (fields == null) {
      String incoming = httpRequest.getHeader(HEADER_REQUEST_ID);
      fields = LogContext.event().put(LogContext.CORRELATION_ID,
          incoming != null && SAFE_ID.matcher(incoming).matches()
              ? incoming : UUID.randomUUID().toString());
      httpRequest.setAttribute(LogContext.REQUEST_FIELDS, fields);
      httpRequest.setAttribute(START, System.nanoTime());
    }
    httpRequest.setAttribute(RESPONSE, httpResponse);
    try {
      MDC.clear();
      populateMdc(fields);
      String incoming = httpRequest.getHeader(HEADER_REQUEST_ID);
      if (incoming != null && !SAFE_ID.matcher(incoming).matches()) {
        log.warn("Invalid X-Request-Id replaced");
      }
      httpResponse.setHeader(HEADER_REQUEST_ID, fields.path(LogContext.CORRELATION_ID).textValue());
      chain.doFilter(request, response);
    } catch (IOException | ServletException | RuntimeException | Error failure) {
      fields.put(STATUS, STATUS_KO);
      fields.remove(FAULT_CODE);
      fields.remove(FAULT_DETAIL);
      fields.remove(CTX_SOAP_OUTCOME);
      LogContext.diagnostic(log, Level.ERROR, LogMessages.HTTP_REQUEST_PROCESSING_FAILED, failure);
      throw failure;
    } finally {
      LogContext.restore(previous);
    }
  }

  private void logCompletedRequest(ServletRequestEvent event) {
    if (!(event.getServletRequest() instanceof HttpServletRequest request)
        || !(request.getAttribute(RESPONSE) instanceof HttpServletResponse response)
        || !(request.getAttribute(START) instanceof Long started)) {
      return;
    }
    ObjectNode collected = LogContext.fields(request);
    if (collected == null) {
      return;
    }
    ObjectNode fields = collected.deepCopy();
    populateMethod(request, fields);
    populateOutcome(request, response.getStatus(), fields);
    fields.put(RESPONSE_TIME, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    ObjectNode args = fields.with(ARGS);
    String serviceType = request.getHeader(CommonUtil.SERVICE_TYPE_HEADER);
    args.put(SERVICE_TYPE, serviceType == null ? CommonUtil.DEFAULT_SERVICE_TYPE : serviceType);
    fields.put(ARGS, args.toString());
    Map<String, String> previous = MDC.getCopyOfContextMap();
    try {
      MDC.clear();
      populateMdc(fields);
      LogContext.log(log, completionLevel(fields), LogMessages.API_OPERATION_COMPLETED, fields);
    } finally {
      LogContext.restore(previous);
    }
  }

  private void populateMethod(HttpServletRequest request, ObjectNode fields) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    if (livenessPath.equals(path)) {
      fields.put(METHOD, LIVENESS);
    } else if (readinessPath.equals(path)) {
      fields.put(METHOD, READINESS);
    } else if (!fields.has(METHOD)
        && request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE)
            instanceof HandlerMethod handler
        && handler.getBeanType().getPackageName().startsWith(CONTROLLER_PACKAGE)) {
      fields.put(METHOD, handler.getMethod().getName());
    }
  }

  private static void populateOutcome(HttpServletRequest request, int code, ObjectNode fields) {
    if (code >= 100 && code <= 599) {
      fields.put(HTTP_CODE, code);
    }
    if (STATUS_KO.equals(fields.path(STATUS).asText()) || code >= 400) {
      fields.put(STATUS, STATUS_KO);
    } else if (code >= 200 && code < 400) {
      fields.put(STATUS, STATUS_OK);
    }
    if (request.getAttribute(FAULT_STATUS) instanceof Integer faultStatus && faultStatus != code) {
      fields.remove(FAULT_CODE);
      fields.remove(FAULT_DETAIL);
    }
  }

  private static Level completionLevel(ObjectNode fields) {
    if (PaaErrorEnum.PAA_SYSTEM_ERROR.getFaultCode().equals(fields.path(FAULT_CODE).asText())) {
      return Level.ERROR;
    }
    if (HEALTH.contains(fields.path(METHOD).asText())) {
      return STATUS_OK.equals(fields.path(STATUS).asText()) ? Level.DEBUG : Level.WARN;
    }
    return Level.INFO;
  }

  private static void populateMdc(ObjectNode fields) {
    for (String name : new String[] {
        LogContext.CORRELATION_ID, METHOD, CTX_CREDITOR_ID, CTX_NOTICE_NUMBER, CTX_IUV}) {
      if (fields.hasNonNull(name)) {
        MDC.put(name, fields.path(name).asText());
      }
    }
  }
}
