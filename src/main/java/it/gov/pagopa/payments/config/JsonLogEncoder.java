package it.gov.pagopa.payments.config;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import co.elastic.logging.logback.EcsEncoder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Preserves numeric and object fields alongside the existing ECS metadata. */
public final class JsonLogEncoder extends EcsEncoder {
  private static final String APPLICATION_LOGGER_PREFIX = "it.gov.pagopa.payments.";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> RESERVED = Set.of(
      LogContext.TIMESTAMP, LogContext.MESSAGE, LogContext.LOG_LEVEL, LogContext.LOG_LOGGER,
      LogContext.ECS_VERSION, LogContext.SERVICE_NAME, LogContext.SERVICE_VERSION,
      LogContext.SERVICE_ENVIRONMENT, LogContext.THREAD_NAME);

  @Override
  public byte[] encode(ILoggingEvent event) {
    byte[] encoded = super.encode(event);
    boolean explicit = event.getMarker() != null && event.getMarker().contains(LogContext.FIELDS);
    boolean diagnostic = event.getLoggerName().startsWith(APPLICATION_LOGGER_PREFIX)
        && event.getThrowableProxy() instanceof ThrowableProxy;
    if (!explicit && !diagnostic) {
      return encoded;
    }
    ObjectNode fields = LogContext.event();
    if (explicit) {
      Object[] arguments = event.getArgumentArray();
      if (arguments == null || arguments.length != 1
          || !(arguments[0] instanceof ObjectNode values)) {
        throw new IllegalArgumentException("Application log fields must be a JSON object");
      }
      fields.setAll(values);
    }
    if (diagnostic && event.getThrowableProxy() instanceof ThrowableProxy proxy) {
      fields.setAll(LogContext.diagnosticFields(proxy.getThrowable()));
    }
    try {
      ObjectNode json = (ObjectNode) JSON.readTree(encoded);
      if (fields.path(LogContext.CTX_DETAILS).hasNonNull(LogContext.DEPENDENCY)) {
        json.remove(LogContext.METHOD);
      }
      fields.fields().forEachRemaining(field -> {
        if (RESERVED.contains(field.getKey())) {
          throw new IllegalArgumentException("Reserved log field: " + field.getKey());
        }
        json.set(field.getKey(), field.getValue());
      });
      return (JSON.writeValueAsString(json) + "\n").getBytes(StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("Cannot encode application log fields", exception);
    }
  }
}
