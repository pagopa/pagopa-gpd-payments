package it.gov.pagopa.payments.config;

import it.gov.pagopa.payments.utils.LogMasker;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Milestone-driven logging: one event per completed business action or I/O boundary, never a
 * start/end pair. Payloads are never serialized and identifiers are read through a whitelist, so
 * debtor data cannot reach a log event.
 */
@Aspect
@Component
@Slf4j
public class LoggingAspect {

  public static final String EVENT_ACTION = "event_action";
  public static final String EVENT_OUTCOME = "event_outcome";
  public static final String CORRELATION_ID = "correlation_id";

  public static final String CTX_DETAILS_PATH = LogContext.CTX_DETAILS_PREFIX + "path";
  public static final String CTX_DETAILS_HTTP_CODE = LogContext.CTX_DETAILS_PREFIX + "http_code";
  public static final String CTX_DETAILS_RESPONSE_TIME =
      LogContext.CTX_DETAILS_PREFIX + "response_time_ms";
  public static final String CTX_DETAILS_METHOD = LogContext.CTX_DETAILS_PREFIX + "method";

  /** In the MDC only to compute the duration: removed before the milestone is emitted. */
  public static final String START_TIME = "startTime";

  public static final String OUTCOME_SUCCESS = "success";
  public static final String OUTCOME_FAILURE = "failure";

  private static final String API_OPERATION_COMPLETED = "Completed API operation";

  /** Emitted by the outcome owner, never by the aspect: only it knows the fault code. */
  public static final String API_OPERATION_FAILED = "Failed API operation";

  private static final String IO_OPERATION_COMPLETED = "Completed I/O operation";
  private static final String INTERNAL_OPERATION_COMPLETED = "Completed internal operation";

  /** JAXB classes generated from {@code paForNode.xsd}. */
  private static final String SOAP_MODEL_PACKAGE = "it.gov.pagopa.payments.model.partner";

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

  /** Bootstrap is not a business milestone. */
  @PostConstruct
  public void logStartup() {
    log.debug("Starting {} version {} - environment {}", name, version, environment);
  }

  /**
   * Emits the end-of-call milestone of an API operation, REST or SOAP. On failure it logs nothing
   * and leaves the context in place for the outcome owner; {@link RequestFilter} clears the MDC when
   * the request ends, so nothing leaks into the next one.
   */
  @Around(value = "(restController() || endpointClass())")
  public Object logApiInvocation(ProceedingJoinPoint joinPoint) throws Throwable {
    Set<String> managedKeys = new LinkedHashSet<>();
    String method = httRequest.getMethod();
    String uri = httRequest.getRequestURI();
    String action =
        method != null && uri != null ? method + " " + uri : joinPoint.getSignature().getName();

    put(managedKeys, EVENT_ACTION, action);
    put(managedKeys, CTX_DETAILS_METHOD, joinPoint.getSignature().getName());
    put(managedKeys, START_TIME, String.valueOf(System.currentTimeMillis()));
    addIdentifiersToContext(joinPoint, managedKeys);

    try {
      Object result = joinPoint.proceed();

      put(managedKeys, EVENT_OUTCOME, OUTCOME_SUCCESS);
      put(managedKeys, CTX_DETAILS_HTTP_CODE, String.valueOf(httpResponse.getStatus()));
      put(managedKeys, CTX_DETAILS_RESPONSE_TIME, getExecutionTime());
      MDC.remove(START_TIME);

      log.info(API_OPERATION_COMPLETED);
      return result;
    } catch (Throwable e) {
      MDC.put(EVENT_OUTCOME, OUTCOME_FAILURE);
      throw e;
    } finally {
      if (OUTCOME_SUCCESS.equals(MDC.get(EVENT_OUTCOME))) {
        managedKeys.forEach(MDC::remove);
      }
    }
  }

  /** Emits the milestone of an I/O operation towards a Feign client or the storage. */
  @Around(value = "repository() || feignClient()")
  public Object logIoInvocation(ProceedingJoinPoint joinPoint) throws Throwable {
    String previousPath = MDC.get(CTX_DETAILS_PATH);
    MDC.put(CTX_DETAILS_PATH, joinPoint.getSignature().getName());
    try {
      Object result = joinPoint.proceed();
      log.info(IO_OPERATION_COMPLETED);
      return result;
    } finally {
      restore(CTX_DETAILS_PATH, previousPath);
    }
  }

  /** Internal steps are volatile processing, not milestones: DEBUG only. */
  @Around(value = "service()")
  public Object logServiceInvocation(ProceedingJoinPoint joinPoint) throws Throwable {
    Object result = joinPoint.proceed();
    if (log.isDebugEnabled()) {
      log.debug("{} [{}]", INTERNAL_OPERATION_COMPLETED, joinPoint.getSignature().getName());
    }
    return result;
  }

  /** Milliseconds since the operation started, or {@code -} when unknown. */
  public String getExecutionTime() {
    String startTime = MDC.get(START_TIME);
    if (startTime == null) {
      return "-";
    }
    try {
      return String.valueOf(System.currentTimeMillis() - Long.parseLong(startTime));
    } catch (NumberFormatException e) {
      return "-";
    }
  }

  private void addIdentifiersToContext(JoinPoint joinPoint, Set<String> managedKeys) {
    Object[] args = joinPoint.getArgs();
    if (args == null || args.length == 0) {
      return;
    }

    Annotation[][] parameterAnnotations = parameterAnnotations(joinPoint);
    for (int i = 0; i < args.length; i++) {
      if (i < parameterAnnotations.length) {
        addAnnotatedValue(parameterAnnotations[i], args[i], managedKeys);
      }
      addSoapIdentifiers(args[i], managedKeys);
    }
  }

  private Annotation[][] parameterAnnotations(JoinPoint joinPoint) {
    if (!(joinPoint.getSignature() instanceof MethodSignature signature)) {
      return new Annotation[0][];
    }
    Method method = signature.getMethod();
    return method != null ? method.getParameterAnnotations() : new Annotation[0][];
  }

  private void addAnnotatedValue(Annotation[] annotations, Object value, Set<String> managedKeys) {
    if (annotations == null || value == null) {
      return;
    }
    for (Annotation annotation : annotations) {
      if (annotation instanceof LogEntity logEntity) {
        put(managedKeys, logEntity.name(), resolve(value, logEntity.mask()));
      } else if (annotation instanceof LogDetails logDetails) {
        put(
            managedKeys,
            LogContext.CTX_DETAILS_PREFIX + logDetails.name(),
            resolve(value, logDetails.mask()));
      }
    }
  }

  /**
   * Reads the identifiers shared by every {@code paForNode.xsd} operation. The whitelist excludes
   * the debtor, so a new schema element cannot silently start being logged.
   */
  private void addSoapIdentifiers(Object argument, Set<String> managedKeys) {
    if (argument == null || !argument.getClass().getName().startsWith(SOAP_MODEL_PACKAGE)) {
      return;
    }

    put(managedKeys, LogContext.CTX_DETAILS_STATION, readString(argument, "getIdStation"));
    String organizationFiscalCode = readString(argument, "getIdPA");

    Object payment = read(argument, "getReceipt");
    if (payment == null) {
      payment = read(argument, "getQrCode");
    }
    if (payment != null) {
      put(managedKeys, LogContext.CTX_NAV, readString(payment, "getNoticeNumber"));
      put(managedKeys, LogContext.CTX_IUV, readString(payment, "getCreditorReferenceId"));
      put(managedKeys, LogContext.CTX_TRANSACTION_ID, readString(payment, "getReceiptId"));
      if (organizationFiscalCode == null) {
        organizationFiscalCode = readString(payment, "getFiscalCode");
      }
    }
    put(
        managedKeys,
        LogContext.CTX_ORGANIZATION_FISCAL_CODE,
        LogMasker.maskIfPersonal(organizationFiscalCode));
  }

  private String resolve(Object value, boolean mask) {
    String asString = String.valueOf(value);
    return mask ? LogMasker.maskIfPersonal(asString) : asString;
  }

  private String readString(Object source, String getter) {
    Object value = read(source, getter);
    return value != null ? String.valueOf(value) : null;
  }

  private Object read(Object source, String getter) {
    try {
      Method method = source.getClass().getMethod(getter);
      return method.invoke(source);
    } catch (ReflectiveOperationException | RuntimeException e) {
      // absent getter or partial request: logging must never fail the business call
      return null;
    }
  }

  private void put(Set<String> managedKeys, String key, String value) {
    if (value == null) {
      return;
    }
    MDC.put(key, value);
    managedKeys.add(key);
  }

  private void restore(String key, String previousValue) {
    if (previousValue != null) {
      MDC.put(key, previousValue);
    } else {
      MDC.remove(key);
    }
  }
}
