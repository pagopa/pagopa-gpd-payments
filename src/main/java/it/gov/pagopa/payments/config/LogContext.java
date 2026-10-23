package it.gov.pagopa.payments.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import javax.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.slf4j.event.Level;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Shared JSON emission and request metadata; business projections stay in the aspect. */
public final class LogContext {
  public static final String CORRELATION_ID = "correlation_id";
  public static final String HEADER_REQUEST_ID = "X-Request-Id";
  public static final String METHOD = "method";
  public static final String STATUS = "status";
  public static final String STATUS_OK = "OK";
  public static final String STATUS_KO = "KO";
  public static final String ARGS = "args";
  public static final String SERVICE_TYPE = "serviceType";
  public static final String FAULT_CODE = "faultCode";
  public static final String FAULT_DETAIL = "faultDetail";
  public static final String HTTP_CODE = "http_code";
  public static final String RESPONSE_TIME = "response_time";
  public static final String ERROR_TYPE = "error.type";
  public static final String ERROR_MESSAGE = "error.message";
  public static final String ERROR_STACK_TRACE = "error.stack_trace";
  public static final String CTX_SOAP_OUTCOME = "ctx_soap_outcome";
  public static final String CTX_CREDITOR_ID = "ctx_creditor_id";
  public static final String CTX_NOTICE_NUMBER = "ctx_notice_number";
  public static final String CTX_IUV = "ctx_iuv";
  public static final String CTX_DETAILS = "ctx_details";
  public static final String DEPENDENCY = "dependency";
  public static final String PATH = "path";
  public static final String TIMESTAMP = "@timestamp";
  public static final String MESSAGE = "message";
  public static final String LOG_LEVEL = "log.level";
  public static final String LOG_LOGGER = "log.logger";
  public static final String ECS_VERSION = "ecs.version";
  public static final String SERVICE_NAME = "service.name";
  public static final String SERVICE_VERSION = "service.version";
  public static final String SERVICE_ENVIRONMENT = "service.environment";
  public static final String THREAD_NAME = "process.thread.name";
  static final String REQUEST_FIELDS = LogContext.class.getName();
  static final Marker FIELDS = MarkerFactory.getMarker("APPLICATION_FIELDS");
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Logger LOG = LoggerFactory.getLogger(LogContext.class);

  private LogContext() {}

  public static ObjectNode event() {
    return JSON.createObjectNode();
  }

  public static ObjectNode fields(HttpServletRequest request) {
    Object value = request.getAttribute(REQUEST_FIELDS);
    return value instanceof ObjectNode fields ? fields : null;
  }

  public static ObjectNode fields() {
    return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
        ? fields(attributes.getRequest()) : null;
  }

  public static void putScalar(ObjectNode fields, String name, Object value) {
    if (value instanceof String || value instanceof Number || value instanceof Boolean) {
      fields.set(name, JSON.valueToTree(value));
    } else if (value instanceof Enum<?> enumeration) {
      fields.put(name, enumeration.name());
    } else if (value != null) {
      LOG.warn("Non-scalar logging parameter omitted: {}", name);
    }
  }

  public static void log(Logger logger, Level level, String message, ObjectNode fields) {
    ObjectNode snapshot = fields.deepCopy();
    switch (level) {
      case INFO -> logger.info(FIELDS, message, snapshot);
      case WARN -> logger.warn(FIELDS, message, snapshot);
      case ERROR -> logger.error(FIELDS, message, snapshot);
      case DEBUG -> logger.debug(FIELDS, message, snapshot);
      case TRACE -> logger.trace(FIELDS, message, snapshot);
    }
  }

  public static void diagnostic(Logger logger, Level level, String message, Throwable failure) {
    log(logger, level, message, diagnosticFields(failure));
  }

  static ObjectNode diagnosticFields(Throwable failure) {
    ObjectNode fields = event().put(ERROR_TYPE, failure.getClass().getName());
    if (failure.getMessage() != null) {
      fields.put(ERROR_MESSAGE, failure.getMessage());
    }
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    Throwable root = failure;
    seen.add(root);
    while (root.getCause() != null && seen.add(root.getCause())) {
      root = root.getCause();
    }
    boolean cyclic = root.getCause() != null;
    StringBuilder stack = new StringBuilder();
    appendFrames(stack, failure);
    if (!cyclic && root != failure) {
      stack.append("\nRoot cause: ");
      appendFrames(stack, root);
    }
    if (cyclic) {
      stack.append("\n[circular cause chain]");
    }
    fields.put(ERROR_STACK_TRACE, stack.toString());
    return fields;
  }

  private static void appendFrames(StringBuilder stack, Throwable failure) {
    stack.append(failure.getClass().getName());
    StackTraceElement[] frames = failure.getStackTrace();
    for (int i = 0; i < Math.min(3, frames.length); i++) {
      stack.append("\n\tat ").append(frames[i]);
    }
    if (frames.length > 3) {
      stack.append("\n\t... ").append(frames.length - 3).append(" frames omitted");
    }
  }

  public static void restore(Map<String, String> previous) {
    if (previous == null) {
      MDC.clear();
    } else {
      MDC.setContextMap(previous);
    }
  }
}
