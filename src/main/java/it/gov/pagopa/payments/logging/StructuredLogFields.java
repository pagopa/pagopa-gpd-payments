package it.gov.pagopa.payments.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/**
 * Carries typed fields for a single log event: MDC only stores strings and would lose their numeric
 * or object representation. Keeping these values on the event also avoids retaining them in MDC for
 * subsequent logs.
 *
 * <p>Pass as the last non-Throwable SLF4J argument without a message placeholder. The JSON encoder and
 * local text converter render the same snapshot in their respective formats.
 *
 * <p>Validates supported field names and types, prevents overriding reserved ECS metadata, copies
 * values and omits empty objects. Request allowlists and sensitive-data checks remain the caller's
 * responsibility; this class does not manage correlation, outcomes or retries.
 */
public final class StructuredLogFields {

  private final ObjectNode fields;

  public StructuredLogFields(ObjectNode fields) {
    Objects.requireNonNull(fields, "fields");
    fields.fields().forEachRemaining(StructuredLogFields::validateField);
    this.fields = fields.deepCopy();
    for (String name : new String[] {"args", "ctx_details"}) {
      if (this.fields.has(name) && this.fields.get(name).isEmpty()) {
        this.fields.remove(name);
      }
    }
  }

  private static void validateField(Map.Entry<String, JsonNode> field) {
    JsonNode value = field.getValue();
    boolean valid =
        switch (field.getKey()) {
          case "event.duration" ->
              value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0;
          case "http.response.status_code" ->
              value.isIntegralNumber()
                  && value.canConvertToInt()
                  && value.intValue() >= 100
                  && value.intValue() <= 599;
          case "args", "ctx_details" -> value.isObject();
          case "error.type", "error.message", "error.stack_trace" -> value.isTextual();
          default -> false;
        };
    if (!valid) {
      throw new IllegalArgumentException("Invalid structured logging field: " + field.getKey());
    }
    validateJsonValue(value);
  }

  /** Rejects embedded Java objects and raw JSON nodes rather than serializing arbitrary payloads. */
  private static void validateJsonValue(JsonNode value) {
    if (value.isContainerNode()) {
      value.forEach(StructuredLogFields::validateJsonValue);
    } else if (!(value.isNull()
        || value.isTextual()
        || value.isBoolean()
        || (value.isNumber()
            && (!(value.isDouble() || value.isFloat())
                || Double.isFinite(value.doubleValue()))))) {
      throw new IllegalArgumentException("Structured logging values must be JSON values");
    }
  }

  ObjectNode toJson() {
    return fields.deepCopy();
  }

  boolean isEmpty() {
    return fields.isEmpty();
  }

  static StructuredLogFields fromArguments(Object[] arguments) {
    if (arguments != null
        && arguments.length > 0
        && arguments[arguments.length - 1] instanceof StructuredLogFields fields) {
      return fields;
    }
    return null;
  }

  /** Keeps context readable, including actual newlines for privacy-safe diagnostic stack frames. */
  String toReadableText() {
    StringBuilder text = new StringBuilder();
    Iterator<Map.Entry<String, JsonNode>> entries = fields.fields();
    while (entries.hasNext()) {
      Map.Entry<String, JsonNode> entry = entries.next();
      text.append(' ').append(entry.getKey()).append('=');
      if ("error.stack_trace".equals(entry.getKey())) {
        text.append('\n').append(entry.getValue().textValue());
      } else {
        text.append(entry.getValue());
      }
    }
    return text.toString();
  }
}
