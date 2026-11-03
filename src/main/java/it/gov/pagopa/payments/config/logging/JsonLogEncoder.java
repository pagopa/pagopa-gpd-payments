package it.gov.pagopa.payments.config.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import co.elastic.logging.logback.EcsEncoder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Converts application logs into structured ECS JSON, ready for collection by ELK.
 * Merges metadata, context and detail fields, preserving types and generating requestId and operationId aliases.
 * Ensures a consistent output format; it neither chooses log levels nor sends logs directly to Elasticsearch.
 *
 * <p>For example, a GPD payment call returning HTTP 409 after 120 ms can produce the following
 * fields (standard ECS metadata omitted):
 * <pre>{@code
 * {
 *   "message": "Completed Feign HTTP attempt",
 *   "correlation_id": "req-123",
 *   "requestId": "req-123",
 *   "dependency": "gpd",
 *   "status": "KO",
 *   "response_time": 120,
 *   "ctx_details": {
 *     "dependency": "{\"name\":\"gpd.sendPaymentOptionReceipt\",\"http_code\":409}"
 *   }
 * }
 * }</pre>
 * The producer supplies dependency, outcome, duration and details through {@link LogContext}.
 * This encoder merges them into ECS JSON and derives requestId from correlation_id. It also
 * removes the inherited API method from this I/O event, so operationId is absent.
 *
 * <p>Replacing this encoder with the standard {@link EcsEncoder} retains base ECS metadata and
 * MDC, but loses the explicit application field merge and alias derivation. The message may
 * still be logged, but an ELK filter such as {@code dependency: "gpd"} would no longer match it.
 * Deleting this class without updating logback-spring.xml instead leaves the appender
 * referencing an encoder that cannot be loaded.
 */
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
      alias(json, LogContext.REQUEST_ID, LogContext.CORRELATION_ID);
      alias(json, LogContext.OPERATION_ID, LogContext.METHOD);
      return (JSON.writeValueAsString(json) + "\n").getBytes(StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("Cannot encode application log fields", exception);
    }
  }

  private static void alias(ObjectNode json, String alias, String source) {
    json.remove(alias);
    if (json.hasNonNull(source)) {
      json.set(alias, json.get(source));
    }
  }
}
