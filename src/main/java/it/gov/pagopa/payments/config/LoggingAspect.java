package it.gov.pagopa.payments.config;

import static it.gov.pagopa.payments.utils.CommonUtil.deNull;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.gov.pagopa.payments.exception.AppError;
import it.gov.pagopa.payments.model.ProblemJson;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.bind.JAXBElement;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

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
  public static final String CTX_DETAILS_ARGS = "ctx.details.args";
  public static final String CTX_DETAILS_DEPENDENCY = "ctx.details.dependency";
  public static final String CTX_DETAILS_PATH = "ctx.details.path";

  public static final String START_TIME = "startTime";

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

  private static String getDetail(ResponseEntity<ProblemJson> result) {
    if (result != null && result.getBody() != null && result.getBody().getDetail() != null) {
      return result.getBody().getDetail();
    } else return AppError.UNKNOWN.getDetails();
  }

  private static String getTitle(ResponseEntity<ProblemJson> result) {
    if (result != null && result.getBody() != null && result.getBody().getTitle() != null) {
      return result.getBody().getTitle();
    } else return AppError.UNKNOWN.getTitle();
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

  private static Map<String, String> getParams(ProceedingJoinPoint joinPoint) {
    MethodSignature signature = (MethodSignature) joinPoint.getSignature();
    Method method = signature.getMethod();
    Map<String, String> params = new HashMap<>();
    int i = 0;
    for (var parameter : method.getParameters()) {
      var paramName = parameter.getName();
      var arg = joinPoint.getArgs()[i++];
      arg = jaxToString(arg);
      params.put(paramName, deNull(arg));
    }
    return params;
  }

  @Pointcut("@within(org.springframework.web.bind.annotation.RestController)")
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

  @PostConstruct
  public void logStartup() {
    log.info("-> Starting {} version {} - environment {}", name, version, environment);
  }

  @Around(value = "restController() || endpointClass()")
  public Object logApiInvocation(ProceedingJoinPoint joinPoint) throws Throwable {
    String method = httRequest.getMethod();
    String uri = httRequest.getRequestURI();
    String action = method != null && uri != null ? method + " " + uri : joinPoint.getSignature().getName();
    
    MDC.put(EVENT_ACTION, action);
    MDC.put(START_TIME, String.valueOf(System.currentTimeMillis()));

    if (MDC.get(CORRELATION_ID) == null) {
      MDC.put(CORRELATION_ID, UUID.randomUUID().toString());
    }

    Map<String, String> params = getParams(joinPoint);
    MDC.put(CTX_DETAILS_ARGS, params.toString());

    Object result = null;
    try {
      result = joinPoint.proceed();

      MDC.put(EVENT_OUTCOME, "success");
      MDC.put(CTX_DETAILS_HTTP_CODE, String.valueOf(httpResponse.getStatus()));
      MDC.put(CTX_DETAILS_RESPONSE_TIME, getExecutionTime());
      
      log.info("Completed API operation - result: {}", jaxToString(result));
      return result;
    } finally {
      MDC.remove(EVENT_OUTCOME);
      MDC.remove(CTX_DETAILS_HTTP_CODE);
      MDC.remove(CTX_DETAILS_RESPONSE_TIME);
      MDC.remove(START_TIME);
      MDC.remove(CTX_DETAILS_ARGS);
    }
  }


  @Around(value = "repository() || service() || feignClient()")
  public Object logTrace(ProceedingJoinPoint joinPoint) throws Throwable {
    Map<String, String> params = getParams(joinPoint);
    String targetClass = joinPoint.getTarget().getClass().getSimpleName();
    String methodName = joinPoint.getSignature().getName();
    
    long startTime = System.currentTimeMillis();
    try {
        Object result = joinPoint.proceed();
        
        MDC.put(EVENT_OUTCOME, "success");
        MDC.put(CTX_DETAILS_DEPENDENCY, targetClass);
        MDC.put(CTX_DETAILS_PATH, methodName);
        long executionTime = System.currentTimeMillis() - startTime;
        MDC.put(CTX_DETAILS_RESPONSE_TIME, String.valueOf(executionTime));
        MDC.put(CTX_DETAILS_ARGS, params.toString());
        
        log.info("Completed I/O operation - result: {}", jaxToString(result));
        
        return result;
    } catch (Exception e) {
        MDC.put(EVENT_OUTCOME, "failure");
        MDC.put(CTX_DETAILS_DEPENDENCY, targetClass);
        MDC.put(CTX_DETAILS_PATH, methodName);
        long executionTime = System.currentTimeMillis() - startTime;
        MDC.put(CTX_DETAILS_RESPONSE_TIME, String.valueOf(executionTime));
        MDC.put(CTX_DETAILS_ARGS, params.toString());
        MDC.put(ERROR_TYPE, e.getClass().getName());
        MDC.put(ERROR_MESSAGE, e.getMessage());
        
        log.info("Failed I/O operation");
        throw e;
    } finally {
        MDC.remove(EVENT_OUTCOME);
        MDC.remove(CTX_DETAILS_DEPENDENCY);
        MDC.remove(CTX_DETAILS_PATH);
        MDC.remove(CTX_DETAILS_RESPONSE_TIME);
        MDC.remove(CTX_DETAILS_ARGS);
        MDC.remove(ERROR_TYPE);
        MDC.remove(ERROR_MESSAGE);
    }
  }

  private static Object jaxToString(Object arg) {
    if (arg instanceof JAXBElement<?>) {
      try {
        arg = new ObjectMapper().writer().writeValueAsString(arg);
      } catch (JsonProcessingException e) {
        arg = "unreadable!";
      }
    }
    return arg;
  }
}
