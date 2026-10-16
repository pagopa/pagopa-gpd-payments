package it.gov.pagopa.payments.logging.helper;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import javax.xml.datatype.XMLGregorianCalendar;

/**
 * Serializes named request fields without inspecting request objects. Field factories accept only
 * the approved scalar types, so domain objects cannot accidentally enter a JSON projection.
 */
public final class RequestProjectionJson {
  private RequestProjectionJson() {}

  /** A typed extractor and scalar writer; construction is restricted to the scalar factories. */
  public static final class Field<T> {
    private final BiConsumer<ObjectNode, T> writer;

    private Field(BiConsumer<ObjectNode, T> writer) {
      this.writer = writer;
    }
  }

  /** Preserves strings verbatim, including empty strings and leading zeros. */
  public static <T> Field<T> stringField(String name, Function<T, String> extractor) {
    return new Field<>((json, source) -> {
      String value = extractor.apply(source);
      if (value != null) {
        json.put(name, value);
      }
    });
  }

  /** Omits absent boxed booleans without conflating them with an explicit false. */
  public static <T> Field<T> booleanField(String name, Function<T, Boolean> extractor) {
    return new Field<>((json, source) -> {
      Boolean value = extractor.apply(source);
      if (value != null) {
        json.put(name, value);
      }
    });
  }

  /** Writes integer counts as JSON numbers, omitting values unavailable after conversion. */
  public static <T> Field<T> integerField(String name, Function<T, Integer> extractor) {
    return new Field<>((json, source) -> {
      Integer value = extractor.apply(source);
      if (value != null) {
        json.put(name, value);
      }
    });
  }

  /** Uses the XML representation without timezone inference or calendar conversion. */
  public static <T> Field<T> dateTimeField(
      String name, Function<T, XMLGregorianCalendar> extractor) {
    return stringField(name, source -> {
      XMLGregorianCalendar value = extractor.apply(source);
      return value == null ? null : value.toXMLFormat();
    });
  }

  /**
   * Returns JSON for the explicit allowlist only, or null when there is nothing to summarize.
   * This method neither emits logs nor installs MDC, and applies no size limits or redaction.
   */
  public static <T> String serialize(T source, List<Field<T>> fields) {
    if (source == null) {
      return null;
    }
    ObjectNode json = JsonNodeFactory.instance.objectNode();
    for (Field<T> field : fields) {
      field.writer.accept(json, source);
    }
    return json.isEmpty() ? null : json.toString();
  }
}
