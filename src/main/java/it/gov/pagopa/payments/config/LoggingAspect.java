package it.gov.pagopa.payments.config;

import static it.gov.pagopa.payments.config.LogContext.ARGS;
import static it.gov.pagopa.payments.config.LogContext.CTX_CREDITOR_ID;
import static it.gov.pagopa.payments.config.LogContext.CTX_DETAILS;
import static it.gov.pagopa.payments.config.LogContext.CTX_IUV;
import static it.gov.pagopa.payments.config.LogContext.CTX_NOTICE_NUMBER;
import static it.gov.pagopa.payments.config.LogContext.CTX_SOAP_OUTCOME;
import static it.gov.pagopa.payments.config.LogContext.DEPENDENCY;
import static it.gov.pagopa.payments.config.LogContext.ERROR_TYPE;
import static it.gov.pagopa.payments.config.LogContext.FAULT_CODE;
import static it.gov.pagopa.payments.config.LogContext.FAULT_DETAIL;
import static it.gov.pagopa.payments.config.LogContext.METHOD;
import static it.gov.pagopa.payments.config.LogContext.NAME;
import static it.gov.pagopa.payments.config.LogContext.PATH;
import static it.gov.pagopa.payments.config.LogContext.RESPONSE_TIME;
import static it.gov.pagopa.payments.config.LogContext.STATUS;
import static it.gov.pagopa.payments.config.LogContext.STATUS_KO;
import static it.gov.pagopa.payments.config.LogContext.STATUS_OK;

import com.fasterxml.jackson.databind.node.ObjectNode;
import it.gov.pagopa.payments.model.ProblemJson;
import it.gov.pagopa.payments.model.partner.CtQrCode;
import it.gov.pagopa.payments.model.partner.CtResponse;
import it.gov.pagopa.payments.model.partner.PaDemandPaymentNoticeRequest;
import it.gov.pagopa.payments.model.partner.PaGetPaymentReq;
import it.gov.pagopa.payments.model.partner.PaGetPaymentRes;
import it.gov.pagopa.payments.model.partner.PaGetPaymentV2Request;
import it.gov.pagopa.payments.model.partner.PaGetPaymentV2Response;
import it.gov.pagopa.payments.model.partner.PaSendRTReq;
import it.gov.pagopa.payments.model.partner.PaSendRTV2Request;
import it.gov.pagopa.payments.model.partner.PaVerifyPaymentNoticeReq;
import it.gov.pagopa.payments.model.partner.StOutcome;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.concurrent.TimeUnit;
import javax.annotation.PostConstruct;
import javax.xml.bind.JAXBElement;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.springframework.aop.support.AopUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Collects approved API metadata and emits completed repository invocations. */
@Aspect
@Component
@Slf4j
public class LoggingAspect {
  private static final String ID_BROKER_PA = "idBrokerPA";
  private static final String ID_STATION = "idStation";
  private static final String ID_SERVIZIO = "idServizio";
  private static final String IO_OPERATION_COMPLETED = "Completed I/O operation";

  @PostConstruct
  public void logStartup() {
    log.info("Application logging initialized");
  }

  @Around("execution(public * it.gov.pagopa.payments.controller..*.*(..))"
      + " || @within(org.springframework.ws.server.endpoint.annotation.Endpoint)")
  public Object logApiInvocation(ProceedingJoinPoint joinPoint) throws Throwable {
    ObjectNode fields = LogContext.fields();
    if (fields == null) {
      return joinPoint.proceed();
    }
    fields.put(METHOD, joinPoint.getSignature().getName());
    MDC.put(METHOD, joinPoint.getSignature().getName());
    ObjectNode args = LogContext.event();
    Method method = AopUtils.getMostSpecificMethod(
        ((MethodSignature) joinPoint.getSignature()).getMethod(), joinPoint.getTarget().getClass());
    Parameter[] parameters = method.getParameters();
    Object[] values = joinPoint.getArgs();
    for (int i = 0; i < values.length; i++) {
      LogField annotation = parameters[i].getAnnotation(LogField.class);
      if (annotation != null) {
        LogContext.putScalar(annotation.argument() ? args : fields, annotation.value(), values[i]);
      }
      soapIdentifiers(values[i], fields, args);
    }
    fields.set(ARGS, args);
    for (String name : new String[] {CTX_CREDITOR_ID, CTX_NOTICE_NUMBER, CTX_IUV}) {
      if (fields.hasNonNull(name)) {
        MDC.put(name, fields.path(name).asText());
      }
    }
    try {
      Object result = joinPoint.proceed();
      recordResponse(result);
      return result;
    } catch (Throwable failure) {
      fields.put(STATUS, STATUS_KO);
      throw failure;
    }
  }

  @AfterReturning(
      value = "@within(org.springframework.web.bind.annotation.ControllerAdvice)",
      returning = "response")
  public void recordErrorResponse(ResponseEntity<?> response) {
    recordResponse(response);
  }

  private void recordResponse(Object response) {
    ObjectNode fields = LogContext.fields();
    if (fields == null) {
      return;
    }
    if (response instanceof ResponseEntity<?> entity && entity.getStatusCodeValue() >= 400) {
      fields.put(STATUS, STATUS_KO);
      fields.remove(FAULT_CODE);
      fields.remove(FAULT_DETAIL);
      if (entity.getBody() instanceof ProblemJson problem) {
        put(fields, FAULT_CODE, problem.getTitle());
        put(fields, FAULT_DETAIL, problem.getDetail());
      }
      if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes request) {
        request.getRequest().setAttribute(RequestFilter.FAULT_STATUS, entity.getStatusCodeValue());
      }
    } else if (response instanceof JAXBElement<?> element
        && element.getValue() instanceof CtResponse soap) {
      if (soap.getOutcome() != null) {
        fields.put(CTX_SOAP_OUTCOME, soap.getOutcome().name());
      }
      if (soap.getOutcome() == StOutcome.KO || soap.getFault() != null) {
        fields.put(STATUS, STATUS_KO);
      }
      if (soap.getFault() != null) {
        put(fields, FAULT_CODE, soap.getFault().getFaultCode());
        put(fields, FAULT_DETAIL, soap.getFault().getDescription() == null
            ? soap.getFault().getFaultString() : soap.getFault().getDescription());
      }
      if (element.getValue() instanceof PaGetPaymentRes payment && payment.getData() != null) {
        put(fields, CTX_IUV, payment.getData().getCreditorReferenceId());
      } else if (element.getValue() instanceof PaGetPaymentV2Response payment
          && payment.getData() != null) {
        put(fields, CTX_IUV, payment.getData().getCreditorReferenceId());
      }
    }
  }

  @Around("@within(org.springframework.stereotype.Repository)")
  public Object logIoInvocation(ProceedingJoinPoint joinPoint) throws Throwable {
    long started = System.nanoTime();
    Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
    ObjectNode fields = LogContext.event();
    fields.putObject(CTX_DETAILS)
        .put(PATH, method.getName())
        .putObject(DEPENDENCY)
        .put(NAME, method.getDeclaringClass().getSimpleName() + "." + method.getName());
    Level level = Level.INFO;
    try {
      Object result = joinPoint.proceed();
      fields.put(STATUS, STATUS_OK);
      return result;
    } catch (Throwable failure) {
      fields.put(STATUS, STATUS_KO).put(ERROR_TYPE, failure.getClass().getName());
      level = Level.WARN;
      throw failure;
    } finally {
      fields.put(RESPONSE_TIME, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
      LogContext.log(log, level, IO_OPERATION_COMPLETED, fields);
    }
  }

  private static void soapIdentifiers(Object value, ObjectNode fields, ObjectNode args) {
    Object request = value instanceof JAXBElement<?> element ? element.getValue() : value;
    if (request instanceof PaVerifyPaymentNoticeReq soap) {
      common(fields, args, soap.getIdPA(), soap.getIdBrokerPA(), soap.getIdStation());
      notice(fields, soap.getQrCode());
    } else if (request instanceof PaGetPaymentReq soap) {
      common(fields, args, soap.getIdPA(), soap.getIdBrokerPA(), soap.getIdStation());
      notice(fields, soap.getQrCode());
    } else if (request instanceof PaGetPaymentV2Request soap) {
      common(fields, args, soap.getIdPA(), soap.getIdBrokerPA(), soap.getIdStation());
      notice(fields, soap.getQrCode());
    } else if (request instanceof PaDemandPaymentNoticeRequest soap) {
      common(fields, args, soap.getIdPA(), soap.getIdBrokerPA(), soap.getIdStation());
      put(args, ID_SERVIZIO, soap.getIdServizio());
    } else if (request instanceof PaSendRTReq soap) {
      common(fields, args, soap.getIdPA(), soap.getIdBrokerPA(), soap.getIdStation());
      if (soap.getReceipt() != null) {
        receipt(fields, soap.getReceipt().getNoticeNumber(), soap.getReceipt().getCreditorReferenceId(),
            soap.getIdPA() == null ? soap.getReceipt().getFiscalCode() : soap.getIdPA());
      }
    } else if (request instanceof PaSendRTV2Request soap) {
      common(fields, args, soap.getIdPA(), soap.getIdBrokerPA(), soap.getIdStation());
      if (soap.getReceipt() != null) {
        receipt(fields, soap.getReceipt().getNoticeNumber(), soap.getReceipt().getCreditorReferenceId(),
            soap.getIdPA() == null ? soap.getReceipt().getFiscalCode() : soap.getIdPA());
      }
    }
  }

  private static void common(
      ObjectNode fields, ObjectNode args, String creditor, String broker, String station) {
    put(fields, CTX_CREDITOR_ID, creditor);
    put(args, ID_BROKER_PA, broker);
    put(args, ID_STATION, station);
  }

  private static void notice(ObjectNode fields, CtQrCode qrCode) {
    if (qrCode != null) {
      put(fields, CTX_NOTICE_NUMBER, qrCode.getNoticeNumber());
    }
  }

  private static void receipt(ObjectNode fields, String nav, String iuv, String creditor) {
    put(fields, CTX_CREDITOR_ID, creditor);
    put(fields, CTX_NOTICE_NUMBER, nav);
    put(fields, CTX_IUV, iuv);
  }

  private static void put(ObjectNode fields, String name, String value) {
    if (value != null) {
      fields.put(name, value);
    }
  }
}
