package it.gov.pagopa.payments.config;

import it.gov.pagopa.payments.utils.LogMasker;
import java.util.Map;
import java.util.UUID;
import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Milestone-driven logging.
 *
 * <p>A single log event is emitted for each completed business action or I/O boundary: no
 * start/end pairs and no intermediate steps at INFO level. The message is always a short static
 * string, while every variable information is published as an ECS field: business identifiers as
 * isolated top level {@code ctx.*} fields and volatile technical data under {@code ctx.details.*}.
 *
 * <p>Request and response payloads are never serialized into the logs, to avoid disclosing PII
 * (names, e-mails, addresses, personal fiscal codes) and financial data (IBAN).
 */
@Aspect
@Component
@Slf4j
public class LoggingAspect {

  public static final String EVENT_ACTION = "event.action";
  public static final String CORRELATION_ID = "correlation.id";
  public static final String EVENT_OUTCOME = "event.outcome";
  public static final String ERROR_MESSAGE = "error.message";
  public static final String ERROR_TYPE = "error.type";

  public static final String CTX_DETAILS_HTTP_CODE = "ctx.details.httpCode";
  public static final String CTX_DETAILS_RESPONSE_TIME = "ctx.details.responseTime";
  public static final String CTX_DETAILS_DEPENDENCY = "ctx.details.dependency";
  public static final String CTX_DETAILS_PATH = "ctx.details.path";
  public static final String CTX_DETAILS_OPERATION = "ctx.details.operation";

  public static final String START_TIME = "startTime";

  public static final String OUTCOME_SUCCESS = "success";
  public static final String OUTCOME_FAILURE = "failure";

  private static final String API_OPERATION_COMPLETED = "Completed API operation";
  /** Static message of the failure milestone, shared with the components that own the outcome. */
  public static final String API_OPERATION_FAILED = "Failed API operation";
  private static final String IO_OPERATION_COMPLETED = "Completed I/O operation";
  private static final String IO_OPERATION_FAILED = "Failed I/O operation";
  private static final String INTERNAL_OPERATION_COMPLETED = "Completed internal operation";
  private static final String INTERNAL_OPERATION_FAILED = "Failed internal operation";

  final HttpServletRequest httRequest;
  final HttpServletResponse httpResponse;

  @Value("${info.application.name}")
  private String name;

  @Value("${info.application.version}")
  private String version;

  @Value("${info.properties.environment}")
  private String environment;

  public LoggingAspect(HttpServletRequest httRequest, HttpServletResponse httpResponse) {
    this.httRequest = httRequest;
    this.httpResponse = httpResponse;
  }

  public static String getExecutionTime() {
    String startTime = MDC.get(START_TIME);
    if (startTime != null) {
      long endTime = System.currentTimeMillis();
      long executionTime = endTime - Long.parseLong(startTime);
      return String.valueOf(executionTime);
    }
    return "-";
  }

  @Pointcut(
      "@within(org.springframework.web.bind.annotation.RestController)"
          + " || @within(org.springframework.stereotype.Controller)")
  public void restController() {
    // all rest controllers
  }

  @Pointcut("@within(org.springframework.ws.server.endpoint.annotation.Endpoint)")
  public void endpointClass() {
    // all endpoint classes
  }

  @Pointcut("@within(org.springframework.stereotype.Repository)")
  public void repository() {
    // all repository methods
  }

  @Pointcut("@within(org.springframework.stereotype.Service)")
  public void service() {
    // all service methods
  }

  @Pointcut("@within(org.springframework.cloud.openfeign.FeignClient)")
  public void feignClient() {
    // all feign clients
  }

  /** Application bootstrap is not a business milestone: it is kept at DEBUG level. */
  @PostConstruct
  public void logStartup() {
    log.debug("Starting {} version {} - environment {}", name, version, environment);
  }

  /**
   * Logs the milestone of an API operation (REST controller or SOAP endpoint), the outermost I/O
   * boundary of the application.
   *
   * <p>Only the successful milestone is emitted here: the failure milestone belongs to the
   * component that owns the outcome of the request, i.e. {@code ErrorHandler} for REST and {@code
   * SoapMessageDispatcher} for SOAP. When the operation fails the context is intentionally left in
   * the MDC, so that the failure milestone carries the same identifiers and the same elapsed time;
   * it is discarded by {@code RequestFilter} at the end of the request.
   */
  @Around(
      value =
          "(restController() || endpointClass())"
              + " && !within(it.gov.pagopa.payments.controller.BaseController)")
  public Object logApiInvocation(ProceedingJoinPoint joinPoint) throws Throwable {
    String method = httRequest.getMethod();
    String uri = httRequest.getRequestURI();
    String action =
        method != null && uri != null ? method + " " + uri : joinPoint.getSignature().getName();

    MDC.put(EVENT_ACTION, action);
    MDC.put(START_TIME, String.valueOf(System.currentTimeMillis()));
    MDC.put(CTX_DETAILS_OPERATION, joinPoint.getSignature().getName());

    if (MDC.get(CORRELATION_ID) == null) {
      MDC.put(CORRELATION_ID, UUID.randomUUID().toString());
    }

    Map<String, String> previousContext = enrichContext(joinPoint);
    boolean succeeded = false;

    try {
      Object result = joinPoint.proceed();

      LogContext.enrichFromResult(result);
      MDC.put(EVENT_OUTCOME, OUTCOME_SUCCESS);
      MDC.put(CTX_DETAILS_HTTP_CODE, String.valueOf(httpResponse.getStatus()));
      MDC.put(CTX_DETAILS_RESPONSE_TIME, getExecutionTime());

      log.info(API_OPERATION_COMPLETED);
      succeeded = true;
      return result;
    } finally {
      if (succeeded) {
        MDC.remove(EVENT_OUTCOME);
        MDC.remove(CTX_DETAILS_HTTP_CODE);
        MDC.remove(CTX_DETAILS_RESPONSE_TIME);
        MDC.remove(CTX_DETAILS_OPERATION);
        MDC.remove(START_TIME);
        removeError();
        LogContext.restore(previousContext);
      }
    }
  }

  /**
   * Publishes in the MDC the outcome of a failed API operation. It is called by the component that
   * owns the failure, so that a single milestone per operation is emitted.
   *
   * @param e the exception that made the operation fail
   */
  public static void markApiFailure(Exception e) {
    MDC.put(EVENT_OUTCOME, OUTCOME_FAILURE);
    MDC.put(CTX_DETAILS_RESPONSE_TIME, getExecutionTime());
    putError(e);
  }

  /**
   * Logs the milestone of an I/O operation towards an external dependency (Feign client) or the
   * storage (repository).
   */
  @Around(value = "repository() || feignClient()")
  public Object logIoInvocation(ProceedingJoinPoint joinPoint) throws Throwable {
    return logInvocation(joinPoint, true);
  }

  /**
   * Internal service steps are not milestones: they are traced at DEBUG level, so that they can be
   * enabled on demand without polluting the production log stream.
   */
  @Around(value = "service()")
  public Object logServiceInvocation(ProceedingJoinPoint joinPoint) throws Throwable {
    return logInvocation(joinPoint, false);
  }

  private Object logInvocation(ProceedingJoinPoint joinPoint, boolean ioBoundary) throws Throwable {
    String targetClass = joinPoint.getTarget().getClass().getSimpleName();
    String methodName = joinPoint.getSignature().getName();

    Map<String, String> previousContext = enrichContext(joinPoint);
    long startTime = System.currentTimeMillis();
    try {
      Object result = joinPoint.proceed();

      LogContext.enrichFromResult(result);
      putOperationDetails(targetClass, methodName, startTime);

      if (ioBoundary) {
        log.info(IO_OPERATION_COMPLETED);
      } else {
        log.debug(INTERNAL_OPERATION_COMPLETED);
      }
      return result;
    } catch (Exception e) {
      MDC.put(EVENT_OUTCOME, OUTCOME_FAILURE);
      MDC.put(CTX_DETAILS_DEPENDENCY, targetClass);
      MDC.put(CTX_DETAILS_PATH, methodName);
      MDC.put(CTX_DETAILS_RESPONSE_TIME, String.valueOf(System.currentTimeMillis() - startTime));
      putError(e);

      if (ioBoundary) {
        log.error(IO_OPERATION_FAILED);
      } else {
        log.debug(INTERNAL_OPERATION_FAILED);
      }
      throw e;
    } finally {
      MDC.remove(EVENT_OUTCOME);
      MDC.remove(CTX_DETAILS_DEPENDENCY);
      MDC.remove(CTX_DETAILS_PATH);
      MDC.remove(CTX_DETAILS_RESPONSE_TIME);
      removeError();
      LogContext.restore(previousContext);
    }
  }

  private static void putOperationDetails(String targetClass, String methodName, long startTime) {
    MDC.put(EVENT_OUTCOME, OUTCOME_SUCCESS);
    MDC.put(CTX_DETAILS_DEPENDENCY, targetClass);
    MDC.put(CTX_DETAILS_PATH, methodName);
    MDC.put(CTX_DETAILS_RESPONSE_TIME, String.valueOf(System.currentTimeMillis() - startTime));
  }

  private Map<String, String> enrichContext(ProceedingJoinPoint joinPoint) {
    MethodSignature signature = (MethodSignature) joinPoint.getSignature();
    return LogContext.enrich(joinPoint.getArgs(), signature.getParameterNames());
  }

  private static void putError(Exception e) {
    MDC.put(ERROR_TYPE, e.getClass().getName());
    String message = LogMasker.redact(e.getMessage());
    if (message != null) {
      MDC.put(ERROR_MESSAGE, message);
    }
  }

  private static void removeError() {
    MDC.remove(ERROR_TYPE);
    MDC.remove(ERROR_MESSAGE);
  }
}
