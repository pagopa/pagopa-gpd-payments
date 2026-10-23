package it.gov.pagopa.payments.logging;

import static it.gov.pagopa.payments.config.RequestLogCapture.arguments;
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

    JsonNode logEntry = json.readTree(encoder.encode(event));

    assertEquals(Instant.ofEpochMilli(event.getTimeStamp()), Instant.parse(logEntry.path("@timestamp").asText()));
    assertEquals("INFO", logEntry.path("log.level").asText());
    assertEquals("Operation completed", logEntry.path("message").asText());
    assertEquals("1.2.0", logEntry.path("ecs.version").asText());
    assertEquals("pagopa-gpd-payments", logEntry.path("service.name").asText());
    assertEquals("test-version", logEntry.path("service.version").asText());
    assertEquals("dev", logEntry.path("service.environment").asText());
    assertEquals("pagopa-gpd-payments", logEntry.path("event.dataset").asText());
    assertEquals("test-worker", logEntry.path("process.thread.name").asText());
    assertEquals("test.logger", logEntry.path("log.logger").asText());
    assertFalse(logEntry.has("event.duration"));
    assertFalse(logEntry.has("args"));
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
        event(null, null, Map.of("event.duration", "123", "http.response.status_code", "200",
            "args", "{\"pageSize\":20}"));

    JsonNode logEntry = json.readTree(encoder.encode(event));

    assertTrue(logEntry.path("event.duration").isTextual());
    assertTrue(logEntry.path("http.response.status_code").isTextual());
    assertTrue(logEntry.path("args").isTextual());
    assertFalse(logEntry.has("response_time"));
    assertFalse(logEntry.has("http_code"));
  }

  @Test
  void writesTypedFieldsWithoutChangingMessageOrMdc() throws Exception {
    ObjectNode values = json.createObjectNode();
    values.put("response_time", 123456L);
    values.put("http_code", 200);
    values.put("args", "{\"pageSize\":20,\"standIn\":false}");
    values.putObject("ctx_details").put("dependency", "gpd").put("path", "/paymentoptions/{nav}");
    StructuredLogFields fields = new StructuredLogFields(values);
    LoggingEvent event =
        event(new Object[] {fields}, null, Map.of("correlation_id", "request-1"));

    JsonNode logEntry = json.readTree(encoder.encode(event));

    assertEquals("Operation completed", logEntry.path("message").asText());
    assertEquals("request-1", logEntry.path("correlation_id").asText());
    assertFalse(logEntry.has("event.duration"));
    assertFalse(logEntry.has("http.response.status_code"));
    assertFalse(logEntry.has("ctx_soap_fault_code"));
    assertEquals(20, arguments(logEntry).path("pageSize").intValue());
    assertFalse(arguments(logEntry).path("standIn").booleanValue());
    assertTrue(logEntry.path("http_code").isIntegralNumber());
    assertEquals(200, logEntry.path("http_code").intValue());
    assertTrue(logEntry.path("response_time").isIntegralNumber());
    assertEquals(123456L, logEntry.path("response_time").longValue());
    assertEquals("gpd", logEntry.path("ctx_details").path("dependency").asText());
    assertEquals(Map.of("correlation_id", "request-1"), event.getMDCPropertyMap());
  }

  @Test
  void typedFieldsReplaceSameNamedMdcValuesWithoutDuplicateKeys() throws Exception {
    ObjectNode values = json.createObjectNode();
    values.put("response_time", 10L);
    values.put("http_code", 204);
    values.put("args", "{\"pageNum\":0}");
    LoggingEvent event =
        event(new Object[] {new StructuredLogFields(values)}, null,
            Map.of("args", "{legacy=value}",
                "http_code", "500", "response_time", "99"));

    JsonNode logEntry = json.readTree(encoder.encode(event));

    assertEquals(0, arguments(logEntry).path("pageNum").intValue());
    assertEquals(204, logEntry.path("http_code").intValue());
    assertEquals(10, logEntry.path("response_time").longValue());
    assertEquals("{legacy=value}", event.getMDCPropertyMap().get("args"));
  }

  @ParameterizedTest
  @ValueSource(longs = {0L, 1L, 123L, 123_456_789L, Long.MAX_VALUE})
  void responseTimePreservesNumericMillisecondsWithoutConversion(long millis) throws Exception {
    ObjectNode values = json.createObjectNode().put("response_time", millis);
    StructuredLogFields fields = new StructuredLogFields(values);

    JsonNode logEntry = json.readTree(encoder.encode(event(new Object[] {fields}, null)));

    assertEquals(values, fields.toJson());
    assertTrue(logEntry.path("response_time").isIntegralNumber());
    assertEquals(millis, logEntry.path("response_time").longValue());
    assertFalse(logEntry.has("http_code"));
    assertFalse(logEntry.has("event.duration"));
    assertTrue(fields.toReadableText().contains("response_time=" + millis));
  }

  @Test
  void httpCodeDoesNotInventADurationOrCarryFieldsBetweenEvents() throws Exception {
    ObjectNode values = json.createObjectNode().put("http_code", 503);
    JsonNode status = json.readTree(encoder.encode(
        event(new Object[] {new StructuredLogFields(values)}, null)));
    assertTrue(status.path("http_code").isIntegralNumber());
    assertEquals(503, status.path("http_code").intValue());
    assertFalse(status.has("response_time"));

    JsonNode next = json.readTree(encoder.encode(
        event(new Object[] {new StructuredLogFields(json.createObjectNode())}, null)));
    assertFalse(next.has("http_code"));
    assertFalse(next.has("response_time"));
    assertFalse(next.has("args"));
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
    JsonNode logEntry = json.readTree(encoded);

    assertEquals(1, encoded.lines().count());
    assertTrue(encoded.endsWith("\n"));
    assertEquals(value, logEntry.path("ctx_details").path("value").asText());
    assertEquals(RuntimeException.class.getName(), logEntry.path("error.type").asText());
    assertEquals("technical\nfailure", logEntry.path("error.message").asText());
    assertTrue(logEntry.path("error.stack_trace").asText().contains("IllegalStateException: cause"));
  }

  @Test
  void takesAnImmutableSnapshotOfTypedValues() throws Exception {
    ObjectNode values = json.createObjectNode();
    values.put("args", "{\"pageNum\":1}");
    values.putObject("ctx_details").put("path", "original");
    StructuredLogFields fields = new StructuredLogFields(values);
    values.put("args", "{\"pageNum\":99}");
    values.with("ctx_details").put("path", "mutated");
    values.put("response_time", 99);
    ObjectNode snapshot = fields.toJson();
    snapshot.put("args", "{\"pageNum\":100}");
    snapshot.with("ctx_details").put("path", "snapshot-mutation");
    snapshot.put("response_time", 100);

    JsonNode logEntry = json.readTree(encoder.encode(event(new Object[] {fields}, null)));

    assertEquals(1, arguments(logEntry).path("pageNum").intValue());
    assertEquals("original", logEntry.path("ctx_details").path("path").textValue());
    assertFalse(logEntry.has("event.duration"));
    assertFalse(logEntry.has("response_time"));
    assertFalse(logEntry.has("http_code"));
  }

  @Test
  void omitsEmptyDetailsAndPreservesPreSerializedArgumentValues() throws Exception {
    ObjectNode values = json.createObjectNode();
    values.putObject("ctx_details");
    JsonNode empty = json.readTree(
        encoder.encode(event(new Object[] {new StructuredLogFields(values)}, null)));
    assertFalse(empty.has("args"));
    assertFalse(empty.has("ctx_details"));

    ObjectNode args = json.createObjectNode();
    args.put("identifier", "00123");
    args.put("enabled", false);
    args.put("count", 0);
    args.putNull("optional");
    args.putArray("codes").add("01").add("02");
    args.put("serviceType", "custom \"service\"\nvalue");
    values.put("args", args.toString());
    JsonNode logEntry = json.readTree(
        encoder.encode(event(new Object[] {new StructuredLogFields(values)}, null)));
    JsonNode decoded = arguments(logEntry);
    assertEquals("00123", decoded.path("identifier").textValue());
    assertTrue(decoded.path("enabled").isBoolean());
    assertFalse(decoded.path("enabled").booleanValue());
    assertTrue(decoded.path("count").isIntegralNumber());
    assertEquals(0, decoded.path("count").intValue());
    assertTrue(decoded.path("optional").isNull());
    assertEquals("01", decoded.path("codes").get(0).textValue());
    assertEquals("custom \"service\"\nvalue", decoded.path("serviceType").textValue());
    assertEquals(args.toString(), logEntry.path("args").textValue());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "ordinary text", "{pageNum=0}", "{\"pageNum\":1}", "[]", "null"})
  void argumentsRemainOrdinaryTextWithoutParsingOrReformatting(String text) throws Exception {
    ObjectNode values = json.createObjectNode().put("args", text);
    StructuredLogFields fields = new StructuredLogFields(values);

    assertEquals(values, fields.toJson());
    JsonNode logEntry = json.readTree(encoder.encode(event(new Object[] {fields}, null)));
    assertTrue(logEntry.path("args").isTextual());
    assertEquals(text, logEntry.path("args").textValue());
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "{\"response_time\":\"10\"}",
      "{\"response_time\":-1}",
      "{\"response_time\":1.5}",
      "{\"response_time\":9223372036854775808}",
      "{\"http_code\":\"200\"}",
      "{\"http_code\":99}",
      "{\"http_code\":600}",
      "{\"http_code\":200.5}",
      "{\"args\":{}}",
      "{\"args\":[]}",
      "{\"args\":null}",
      "{\"faultCode\":500}",
      "{\"faultDetail\":{}}",
      "{\"event.duration\":1}",
      "{\"http.response.status_code\":200}",
      "{\"ctx_soap_fault_code\":\"PAA_SEMANTICA\"}",
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
    values.putPOJO("args", new Object());
    assertThrows(IllegalArgumentException.class, () -> new StructuredLogFields(values));
    values.remove("args");
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

    JsonNode logEntry = json.readTree(encoder.encode(event));

    assertEquals("Operation test completed", logEntry.path("message").asText());
    assertFalse(logEntry.has("args"));
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
