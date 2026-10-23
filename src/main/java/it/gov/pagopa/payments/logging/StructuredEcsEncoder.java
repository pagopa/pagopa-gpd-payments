package it.gov.pagopa.payments.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import co.elastic.logging.logback.EcsEncoder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Extends ECS output with real JSON numbers and objects: the original encoder serializes MDC values
 * as strings, including numbers and JSON-encoded text.
 *
 * <p>Delegates standard metadata and diagnostics to the ECS encoder, then overlays explicit {@link
 * StructuredLogFields} without duplicate keys. This preserves field types at the source instead of
 * requiring conversion in the ELK pipeline.
 *
 * <p>Events without typed fields use the original encoder unchanged; legacy MDC values are never
 * interpreted as JSON. This encoder handles representation, not field collection or privacy policy.
 */
public class StructuredEcsEncoder extends EcsEncoder {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Override
  public byte[] encode(ILoggingEvent event) {
    byte[] encoded = super.encode(event);
    StructuredLogFields fields = StructuredLogFields.fromArguments(event.getArgumentArray());
    if (fields == null || fields.isEmpty()) {
      return encoded;
    }
    try {
      ObjectNode logRecord = (ObjectNode) JSON.readTree(encoded);
      // Replacing a legacy MDC field must not produce duplicate JSON keys.
      logRecord.setAll(fields.toJson());
      return (JSON.writeValueAsString(logRecord) + "\n").getBytes(StandardCharsets.UTF_8);
    } catch (JsonProcessingException e) {
      addError("Unable to serialize structured log fields", e);
      throw new IllegalStateException("Unable to serialize structured log fields", e);
    } catch (IOException e) {
      addError("Unable to read the ECS log record", e);
      throw new IllegalStateException("Unable to read the ECS log record", e);
    }
  }
}
