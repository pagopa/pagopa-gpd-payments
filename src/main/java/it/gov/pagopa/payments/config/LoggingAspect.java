package it.gov.pagopa.payments.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.Map.Entry;

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
    public static final String ENTITY_ID = "entity.id";
    public static final String EVENT_OUTCOME = "event.outcome";
    public static final String ERROR_MESSAGE = "error.message";
    public static final String ERROR_STACK_TRACE = "error.stack_trace";
    public static final String ERROR_TYPE = "error.type";
    public static final String DEPENDENCY = "dependency";
    public static final String PATH = "path";

    public static final String CTX_DETAILS = "ctx.details";
    public static final String START_TIME = "startTime";
    public static final String HTTP_CODE = "httpCode";
    public static final String RESPONSE_TIME = "responseTime";
    public static final String METHOD = "method";


    public static final String OUTCOME_SUCCESS = "success";
    public static final String OUTCOME_FAILURE = "failure";

    private static final String API_OPERATION_COMPLETED = "Completed API operation";
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

    public String getExecutionTime() {
        Long startTime = getDetails(START_TIME, Long.class);

        if (startTime != null) {
            long endTime = System.currentTimeMillis();
            long executionTime = endTime - startTime;
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

    /**
     * Application bootstrap is not a business milestone: it is kept at DEBUG level.
     */
    @PostConstruct
    public void logStartup() {
        log.debug("Starting {} version {} - environment {}", name, version, environment);
    }


    @Around(
            value =
                    "(restController() || endpointClass())")
    public Object logApiInvocation(ProceedingJoinPoint joinPoint) throws Throwable {
        String method = httRequest.getMethod();
        String uri = httRequest.getRequestURI();
        String action =
                method != null && uri != null ? method + " " + uri : joinPoint.getSignature().getName();

        MDC.put(EVENT_ACTION, action);
        addDetails(METHOD, joinPoint.getSignature().getName());
        addDetails(START_TIME, System.currentTimeMillis());
        addAnnotatedArgumentsToContext(joinPoint);


        try {

            var result = joinPoint.proceed();
            MDC.put(EVENT_OUTCOME, OUTCOME_SUCCESS);
            addDetails(HTTP_CODE, httpResponse.getStatus());
            addDetails(RESPONSE_TIME, getExecutionTime());

            log.info(API_OPERATION_COMPLETED);
            return result;
        } catch (Throwable e) {
            MDC.put(EVENT_OUTCOME, OUTCOME_FAILURE);
            addDetails(ERROR_TYPE, e.getClass().getName());
            addDetails(ERROR_MESSAGE, e.getMessage());
            addDetails(ERROR_STACK_TRACE, Arrays.toString(e.getStackTrace()));
            addDetails(HTTP_CODE, httpResponse.getStatus());
            addDetails(RESPONSE_TIME, getExecutionTime());

            log.error(API_OPERATION_FAILED, e);
            throw e;
        }


    }

    public void addDetails(String name, Object infoToAdd) {
        String details = MDC.get(CTX_DETAILS);
        ObjectMapper mapper = new ObjectMapper();

        try {
            if (details == null) {
                details = mapper.writeValueAsString(new HashMap<>());
            }
            Map<String, Object> map = mapper.readValue(details, Map.class);
            map.put(name, infoToAdd);
            MDC.put(CTX_DETAILS, mapper.writeValueAsString(map));
        } catch (JsonProcessingException e) {
            MDC.put(CTX_DETAILS, "{\"" + name + "\":\"" + infoToAdd + "\"}");
        }
    }


    public <T> T getDetails(String name, Class<T> valueType) {
        String details = MDC.get(CTX_DETAILS);
        ObjectMapper mapper = new ObjectMapper();

        try {
            if (details == null) {
                details = mapper.writeValueAsString(new HashMap<>());
            }
            Map<String, Object> map = mapper.readValue(details, Map.class);
            Object value = map.get(name);
            return (T) value;
        } catch (Exception e) {
            return null;
        }
    }


    /**
     * Logs the milestone of an I/O operation towards an external dependency (Feign client) or the
     * storage (repository).
     */
    @Around(value = "repository() || feignClient()")
    public Object logIoInvocation(ProceedingJoinPoint joinPoint) throws Throwable {
        addAnnotatedArgumentsToContext(joinPoint);
        Object result = joinPoint.proceed();

        log.info(IO_OPERATION_COMPLETED);
        return result;
    }

    private void addAnnotatedArgumentsToContext(JoinPoint joinPoint) {
        Object[] args = joinPoint.getArgs();
        if (args == null || args.length == 0) {
            return;
        }

        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        MethodSignature signature = joinPoint.getSignature() instanceof MethodSignature
                ? (MethodSignature) joinPoint.getSignature()
                : null;
        Annotation[][] parameterAnnotations = signature != null
                ? signature.getMethod().getParameterAnnotations()
                : new Annotation[0][];

        for (int i = 0; i < args.length; i++) {
            Object argument = args[i];
            if (i < parameterAnnotations.length) {
                addAnnotatedValue(parameterAnnotations[i], argument);
            }
            scanAnnotatedFields(argument, visited);
        }
    }

    private void addAnnotatedValue(Annotation[] annotations, Object value) {
        if (annotations == null || value == null) {
            return;
        }
        for (Annotation annotation : annotations) {
            if (annotation instanceof LogEntity logEntity) {
                if (logEntity.mask()) {
                    // diplay only first 3 characters the rest is replaced by *
                    String stringValue = String.valueOf(value);
                    String maskedValue = stringValue.length() > 3
                            ? stringValue.substring(0, 3) + "*".repeat(stringValue.length() - 3)
                            : stringValue;
                    MDC.put(logEntity.name(), maskedValue);
                } else {
                    MDC.put(logEntity.name(), String.valueOf(value));
                }
            } else if (annotation instanceof LogDetails logDetails) {
                if (logDetails.mask()) {
                    // diplay only first 3 characters the rest is replaced by *
                    String stringValue = String.valueOf(value);
                    String maskedValue = stringValue.length() > 3
                            ? stringValue.substring(0, 3) + "*".repeat(stringValue.length() - 3)
                            : stringValue;
                    addDetails(logDetails.name(), maskedValue);
                } else {
                    addDetails(logDetails.name(), value);
                }
            }
        }
    }

    private void scanAnnotatedFields(Object source, Set<Object> visited) {
        if (source == null || isTerminalType(source.getClass()) || !visited.add(source)) {
            return;
        }

        if (source.getClass().isArray()) {
            int length = Array.getLength(source);
            for (int i = 0; i < length; i++) {
                scanAnnotatedFields(Array.get(source, i), visited);
            }
            return;
        }

        if (source instanceof Iterable<?> iterable) {
            for (Object element : iterable) {
                scanAnnotatedFields(element, visited);
            }
            return;
        }

        if (source instanceof Map<?, ?> map) {
            for (Entry<?, ?> entry : map.entrySet()) {
                scanAnnotatedFields(entry.getKey(), visited);
                scanAnnotatedFields(entry.getValue(), visited);
            }
            return;
        }

        Class<?> current = source.getClass();
        while (current != null && current != Object.class) {
            Field[] fields = current.getDeclaredFields();
            for (Field field : fields) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                Object fieldValue = readField(source, field);
                if (fieldValue == null) {
                    continue;
                }
                LogEntity logEntity = field.getAnnotation(LogEntity.class);
                if (logEntity != null) {
                    MDC.put(logEntity.name(), String.valueOf(fieldValue));
                }
                LogDetails logDetails = field.getAnnotation(LogDetails.class);
                if (logDetails != null) {
                    addDetails(logDetails.name(), fieldValue);
                }
                scanAnnotatedFields(fieldValue, visited);
            }
            current = current.getSuperclass();
        }
    }

    private Object readField(Object source, Field field) {
        boolean previousAccessible = field.canAccess(source);
        try {
            if (!previousAccessible) {
                field.setAccessible(true);
            }
            return field.get(source);
        } catch (IllegalAccessException ignored) {
            return null;
        } finally {
            if (!previousAccessible) {
                field.setAccessible(false);
            }
        }
    }

    private boolean isTerminalType(Class<?> type) {
        if (type.isPrimitive() || type.isEnum()) {
            return true;
        }
        if (Number.class.isAssignableFrom(type)
                || CharSequence.class.isAssignableFrom(type)
                || Boolean.class == type
                || Character.class == type
                || UUID.class == type) {
            return true;
        }
        Package pkg = type.getPackage();
        return pkg != null && pkg.getName().startsWith("java.");
    }


}
