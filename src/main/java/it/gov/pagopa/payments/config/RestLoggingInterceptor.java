package it.gov.pagopa.payments.config;

import it.gov.pagopa.payments.logging.LogEventWriter;
import it.gov.pagopa.payments.logging.FailureDiagnostics;
import it.gov.pagopa.payments.logging.ContextSnapshot;
import it.gov.pagopa.payments.logging.LogEvent;
import it.gov.pagopa.payments.logging.OperationLogScope;
import it.gov.pagopa.payments.logging.RequestArgs;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/** Owns the REST boundary, including failures before controller argument binding completes. */
@Slf4j
public class RestLoggingInterceptor implements HandlerInterceptor {
  private static final String EXECUTION = RestLoggingInterceptor.class.getName() + ".execution";
  private static final LogEventWriter LOG_EVENTS = new LogEventWriter(log);

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod method)) {
      return true;
    }
    String controllerPackage = method.getBeanType().getPackageName();
    if (!controllerPackage.equals("it.gov.pagopa.payments.controller")
        && !controllerPackage.startsWith("it.gov.pagopa.payments.controller.")) {
      return true;
    }
    if (request.getAttribute(EXECUTION) != null) {
      return true;
    }
    String creditorFiscalCode = null;
    Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
    if (variables instanceof Map<?, ?> path) {
      Object creditor = path.get("organizationFiscalCode");
      if (creditor == null) {
        creditor = path.get("organizationfiscalcode");
      }
      if (creditor instanceof String fiscalCode) {
        creditorFiscalCode = fiscalCode;
      }
    }
    OperationLogScope scope =
        new ContextSnapshot(MDC.get("requestId"), null, null, null, null, creditorFiscalCode)
            .openOperation(method.getMethod().getName());
    try {
      scope.summaryArgs(RequestArgs.forRest(request, method));
      request.setAttribute(EXECUTION, new Execution(scope, System.nanoTime()));
      return true;
    } catch (RuntimeException | Error failure) {
      scope.close();
      throw failure;
    }
  }

  @Override
  public void afterCompletion(
      HttpServletRequest request, HttpServletResponse response, Object handler, Exception failure) {
    Object execution = request.getAttribute(EXECUTION);
    if (!(execution instanceof Execution state)) {
      return;
    }
    request.removeAttribute(EXECUTION);
    try (OperationLogScope scope = state.scope()) {
      if (failure != null) {
        FailureDiagnostics.technicalFailure(LOG_EVENTS, failure);
      }
      MDC.put("httpCode", Integer.toString(response.getStatus()));
      MDC.put("status", failure != null || response.getStatus() >= 400 ? "KO" : "OK");
      MDC.put("responseTime",
          Long.toString(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - state.started())));
      LOG_EVENTS.logEvent(LogEvent.REST_OPERATION_COMPLETED);
    }
  }

  private record Execution(OperationLogScope scope, long started) {}
}
