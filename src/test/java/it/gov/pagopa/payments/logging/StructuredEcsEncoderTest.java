package it.gov.pagopa.payments.logging;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import co.elastic.logging.logback.EcsEncoder;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StructuredEcsEncoderTest {

  private final ObjectMapper json =
      new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
  private final LoggerContext context = new LoggerContext();
  private final StructuredEcsEncoder encoder = new StructuredEcsEncoder();

  StructuredEcsEncoderTest() {
    encoder.setContext(context);
    encoder.setServiceName("pagopa-gpd-payments");
    encoder.setServiceVersion("test-version");
    encoder.setServiceEnvironment("dev");
    encoder.start();
  }

  @AfterEach
  void close() {
    encoder.stop();
    context.stop();
  }

  @Test
  void preservesAutomaticEcsMetadata() throws Exception {
    LoggingEvent event = event(null, null);
    event.setTimeStamp(1792573234567L);
    event.setThreadName("test-worker");

    JsonNode record = json.readTree(encoder.encode(event));

    assertEquals(Instant.ofEpochMilli(event.getTimeStamp()), Instant.parse(record.path("@timestamp").asText()));
    assertEquals("INFO", record.path("log.level").asText());
    assertEquals("Operation completed", record.path("message").asText());
    assertEquals("1.2.0", record.path("ecs.version").asText());
    assertEquals("pagopa-gpd-payments", record.path("service.name").asText());
    assertEquals("test-version", record.path("service.version").asText());
    assertEquals("dev", record.path("service.environment").asText());
    assertEquals("pagopa-gpd-payments", record.path("event.dataset").asText());
    assertEquals("test-worker", record.path("process.thread.name").asText());
    assertEquals("test.logger", record.path("log.logger").asText());
    assertFalse(record.has("event.duration"));
    assertFalse(record.has("args"));
  }

  @Test
  void leavesLegacyEventsByteForByteUnchanged() {
    EcsEncoder original = new EcsEncoder();
    original.setContext(context);
    original.setServiceName("pagopa-gpd-payments");
    original.setServiceVersion("test-version");
    original.setServiceEnvironment("dev");
    original.start();
    try {
      LoggingEvent event =
          event(new Object[] {"ordinary argument"}, new RuntimeException("failure"),
              Map.of("args", "{legacy=value}", "httpCode", "200"));

      assertArrayEquals(original.encode(event), encoder.encode(event));
    } finally {
      original.stop();
    }
  }

  @Test
  void nativeEncoderSerializesMdcAsStrings() throws Exception {
    LoggingEvent event =
        event(null, null, Map.of("event.duration", "123", "args", "{\"pageSize\":20}"));

    JsonNode record = json.readTree(encoder.encode(event));

    assertTrue(record.path("event.duration").isTextual());
    assertTrue(record.path("args").isTextual());
  }

  @Test
  void writesTypedFieldsWithoutChangingMessageOrMdc() throws Exception {
    ObjectNode values = json.createObjectNode();
    values.put("event.duration", 123456L);
    values.put("http.response.status_code", 200);
    values.putObject("args").put("pageSize", 20).put("standIn", false);
    values.putObject("ctx_details").put("dependency", "gpd").put("path", "/paymentoptions/{nav}");
    StructuredLogFields fields = new StructuredLogFields(values);
    LoggingEvent event =
        event(new Object[] {fields}, null, Map.of("correlation_id", "request-1"));

    JsonNode record = json.readTree(encoder.encode(event));

    assertEquals("Operation completed", record.path("message").asText());
    assertEquals("request-1", record.path("correlation_id").asText());
    assertTrue(record.path("event.duration").isIntegralNumber());
    assertEquals(123456L, record.path("event.duration").longValue());
    assertTrue(record.path("http.response.status_code").isIntegralNumber());
    assertEquals(200, record.path("http.response.status_code").intValue());
    assertTrue(record.path("args").isObject());
    assertEquals(20, record.path("args").path("pageSize").intValue());
    assertFalse(record.path("args").path("standIn").booleanValue());
    assertEquals("gpd", record.path("ctx_details").path("dependency").asText());
    assertEquals(Map.of("correlation_id", "request-1"), event.getMDCPropertyMap());
  }

  @Test
  void typedFieldsReplaceMdcAliasesWithoutDuplicateKeys() throws Exception {
    ObjectNode values = json.createObjectNode();
    values.put("event.duration", 10L);
    values.putObject("args").put("pageNum", 0);
    LoggingEvent event =
        event(new Object[] {new StructuredLogFields(values)}, null,
            Map.of("args", "{legacy=value}", "event.duration", "9"));

    JsonNode record = json.readTree(encoder.encode(event));

    assertTrue(record.path("args").isObject());
    assertEquals(10, record.path("event.duration").longValue());
    assertEquals("{legacy=value}", event.getMDCPropertyMap().get("args"));
  }

  @Test
  void escapesValuesAndRetainsTechnicalDiagnosticOnOnePhysicalLine() throws Exception {
    ObjectNode values = json.createObjectNode();
    String value = "quote \" slash \\ newline\nreturn\rtab\t";
    values.putObject("ctx_details").put("value", value);
    RuntimeException failure =
        new RuntimeException("technical\nfailure", new IllegalStateException("cause"));
    LoggingEvent event = event(new Object[] {new StructuredLogFields(values)}, failure);

    String encoded = new String(encoder.encode(event), StandardCharsets.UTF_8);
    JsonNode record = json.readTree(encoded);

    assertEquals(1, encoded.lines().count());
    assertTrue(encoded.endsWith("\n"));
    assertEquals(value, record.path("ctx_details").path("value").asText());
    assertEquals(RuntimeException.class.getName(), record.path("error.type").asText());
    assertEquals("technical\nfailure", record.path("error.message").asText());
    assertTrue(record.path("error.stack_trace").asText().contains("IllegalStateException: cause"));
  }

  @Test
  void takesAnImmutableSnapshotOfTypedValues() throws Exception {
    ObjectNode values = json.createObjectNode();
    ObjectNode args = values.putObject("args").put("pageNum", 1);
    StructuredLogFields fields = new StructuredLogFields(values);
    args.put("pageNum", 99);
    values.put("event.duration", 99);
    fields.toJson().with("args").put("pageNum", 100);

    JsonNode record = json.readTree(encoder.encode(event(new Object[] {fields}, null)));

    assertEquals(1, record.path("args").path("pageNum").intValue());
    assertFalse(record.has("event.duration"));
  }

  @Test
  void omitsEmptyObjectsAndPreservesScalarJsonTypes() throws Exception {
    ObjectNode values = json.createObjectNode();
    values.putObject("args");
    values.putObject("ctx_details");
    JsonNode empty = json.readTree(
        encoder.encode(event(new Object[] {new StructuredLogFields(values)}, null)));
    assertFalse(empty.has("args"));
    assertFalse(empty.has("ctx_details"));

    ObjectNode args = values.with("args");
    args.put("identifier", "00123");
    args.put("enabled", false);
    args.put("count", 0);
    args.putNull("optional");
    args.putArray("codes").add("01").add("02");
    JsonNode record = json.readTree(
        encoder.encode(event(new Object[] {new StructuredLogFields(values)}, null)));
    assertEquals("00123", record.path("args").path("identifier").textValue());
    assertTrue(record.path("args").path("enabled").isBoolean());
    assertFalse(record.path("args").path("enabled").booleanValue());
    assertTrue(record.path("args").path("count").isIntegralNumber());
    assertEquals(0, record.path("args").path("count").intValue());
    assertTrue(record.path("args").path("optional").isNull());
    assertEquals("01", record.path("args").path("codes").get(0).textValue());
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "{\"event.duration\":\"10\"}",
      "{\"event.duration\":-1}",
      "{\"event.duration\":1.5}",
      "{\"event.duration\":9223372036854775808}",
      "{\"http.response.status_code\":\"200\"}",
      "{\"http.response.status_code\":99}",
      "{\"http.response.status_code\":600}",
      "{\"http.response.status_code\":200.5}",
      "{\"args\":\"{pageNum=0}\"}",
      "{\"args\":[]}",
      "{\"ctx_details\":null}",
      "{\"message\":\"replacement\"}",
      "{\"service.name\":\"replacement\"}",
      "{\"unexpected\":\"value\"}"
  })
  void rejectsInvalidTypesAndReservedFields(String input) throws Exception {
    ObjectNode values = (ObjectNode) json.readTree(input);

    assertThrows(IllegalArgumentException.class, () -> new StructuredLogFields(values));
  }

  @Test
  void rejectsArbitraryJavaObjectsRawJsonAndNonFiniteNumbers() {
    ObjectNode values = json.createObjectNode();
    ObjectNode details = values.putObject("ctx_details");
    details.putPOJO("payload", new Object());
    assertThrows(IllegalArgumentException.class, () -> new StructuredLogFields(values));

    details.removeAll().putRawValue("payload", new com.fasterxml.jackson.databind.util.RawValue("{}"));
    assertThrows(IllegalArgumentException.class, () -> new StructuredLogFields(values));

    details.removeAll().put("number", Double.NaN);
    assertThrows(IllegalArgumentException.class, () -> new StructuredLogFields(values));

    details.removeAll().put("number", Double.POSITIVE_INFINITY);
    assertThrows(IllegalArgumentException.class, () -> new StructuredLogFields(values));
  }

  @Test
  void supportsFormattedArgumentsAndAnEmptyProjection() throws Exception {
    LoggingEvent event =
        new LoggingEvent(
            getClass().getName(), context.getLogger("test.logger"), Level.INFO,
            "Operation {} completed", null,
            new Object[] {"test", new StructuredLogFields(json.createObjectNode())});
    event.setMDCPropertyMap(Map.of());

    JsonNode record = json.readTree(encoder.encode(event));

    assertEquals("Operation test completed", record.path("message").asText());
    assertFalse(record.has("args"));
  }

  private LoggingEvent event(Object[] arguments, Throwable failure) {
    return event(arguments, failure, Map.of());
  }

  private LoggingEvent event(
      Object[] arguments, Throwable failure, Map<String, String> mdc) {
    LoggingEvent event =
        new LoggingEvent(
            getClass().getName(), context.getLogger("test.logger"), Level.INFO,
            "Operation completed", failure, arguments);
    event.setMDCPropertyMap(mdc);
    return event;
  }
}
