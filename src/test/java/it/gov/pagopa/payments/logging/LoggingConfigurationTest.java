package it.gov.pagopa.payments.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.status.Status;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.beans.BeanUtils;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.env.MockPropertySource;

class LoggingConfigurationTest {

  @ParameterizedTest
  @ValueSource(strings = {"", "default", "default,local", "local"})
  void preservesProfileSpecificOutputAndTypedContext(String profiles) throws Exception {
    MockEnvironment environment = environment("dev", "pagopa-gpd-payments");
    if (!profiles.isEmpty()) {
      environment.setActiveProfiles(profiles.split(","));
    }
    boolean jsonOutput = profiles.isEmpty() || profiles.contains("default");
    LoggerContext context = new LoggerContext();
    Map<String, String> previousMdc = MDC.getCopyOfContextMap();
    try {
      configure(context, environment);
      assertTrue(
          context.getStatusManager().getCopyOfStatusList().stream()
              .noneMatch(status -> status.getLevel() == Status.ERROR),
          () -> context.getStatusManager().getCopyOfStatusList().toString());
      Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
      OutputStreamAppender<?> appender =
          (OutputStreamAppender<?>) root.getAppender(jsonOutput ? "ECS_JSON_CONSOLE" : "stdout");
      assertNotNull(appender);
      assertNull(root.getAppender(jsonOutput ? "stdout" : "ECS_JSON_CONSOLE"));
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      appender.setOutputStream(output);
      context.getLogger("test.logger").info("Legacy operation completed");
      String legacy = output.toString(StandardCharsets.UTF_8);
      assertTrue(legacy.contains("Legacy operation completed"));
      assertFalse(legacy.contains("event.duration"));
      assertFalse(legacy.contains("args="));
      output.reset();
      ObjectNode values = new ObjectMapper().createObjectNode();
      values.put("event.duration", 123L);
      values.put("http.response.status_code", 200);
      values.putObject("args").put("pageNum", 1);
      MDC.put("correlation_id", "request-1");
      MDC.put("method", "testOperation");
      MDC.put("status", "OK");
      context.getLogger("test.logger").info(
          "Operation completed", new StructuredLogFields(values),
          new IllegalStateException("technical failure"));

      String text = output.toString(StandardCharsets.UTF_8);
      if (jsonOutput) {
        JsonNode record = new ObjectMapper().readTree(text);
        assertEquals(1, text.lines().count());
        assertEquals("pagopa-gpd-payments", record.path("service.name").asText());
        assertEquals("test-version", record.path("service.version").asText());
        assertEquals("dev", record.path("service.environment").asText());
        assertEquals("Operation completed", record.path("message").asText());
        assertEquals("request-1", record.path("correlation_id").asText());
        assertEquals("testOperation", record.path("method").asText());
        assertEquals("OK", record.path("status").asText());
        assertFalse(record.has("event_action"));
        assertFalse(record.has("event_outcome"));
        assertTrue(record.path("event.duration").isIntegralNumber());
        assertTrue(record.path("http.response.status_code").isIntegralNumber());
        assertTrue(record.path("args").isObject());
        assertEquals(IllegalStateException.class.getName(), record.path("error.type").asText());
      } else {
        assertFalse(text.stripLeading().startsWith("{"));
        assertTrue(text.contains("INFO"));
        assertTrue(text.contains("Operation completed"));
        assertTrue(text.contains("correlation_id=request-1"));
        assertTrue(text.contains("method=testOperation"));
        assertTrue(text.contains("status=OK"));
        assertTrue(text.contains("event.duration=123"));
        assertTrue(text.contains("http.response.status_code=200"));
        assertTrue(text.contains("args={\"pageNum\":1}"));
        assertTrue(text.contains("IllegalStateException: technical failure"));
      }
    } finally {
      if (previousMdc == null) {
        MDC.clear();
      } else {
        MDC.setContextMap(previousMdc);
      }
      context.stop();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"dev", "uat", "prod"})
  void resolvesGlobalMetadataFromDeploymentEnvironment(String runtimeEnvironment) throws Exception {
    MockEnvironment environment = environment(runtimeEnvironment, "custom-service");

    assertEquals("custom-service", environment.getRequiredProperty("info.application.name"));
    assertEquals(runtimeEnvironment, environment.getRequiredProperty("info.properties.environment"));
    for (String path : new String[] {
        "src/main/resources/application-local.properties",
        "src/test/resources/application.properties"
    }) {
      environment.getPropertySources().addFirst(propertiesFrom(path));
      assertEquals("custom-service", environment.getRequiredProperty("info.application.name"));
      assertEquals(runtimeEnvironment, environment.getRequiredProperty("info.properties.environment"));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"azure", "local", "test"})
  void providesMetadataFallbacksWithoutDeploymentVariables(String localEnvironment) throws Exception {
    MockEnvironment environment = environment(null, null);
    if ("local".equals(localEnvironment)) {
      environment.getPropertySources().addFirst(
          propertiesFrom("src/main/resources/application-local.properties"));
    } else if ("test".equals(localEnvironment)) {
      environment.getPropertySources().addFirst(
          propertiesFrom("src/test/resources/application.properties"));
    }

    assertEquals("pagopa-gpd-payments", environment.getRequiredProperty("info.application.name"));
    assertEquals(localEnvironment, environment.getRequiredProperty("info.properties.environment"));
  }

  private MockEnvironment environment(String runtimeEnvironment, String serviceName) throws Exception {
    MockEnvironment environment = new MockEnvironment();
    environment.getPropertySources().addLast(
        propertiesFrom("src/main/resources/application.properties"));
    environment.setProperty("info.application.version", "test-version");
    if (runtimeEnvironment != null) {
      environment.setProperty("ENV", runtimeEnvironment);
    }
    if (serviceName != null) {
      environment.setProperty("SERVICE_NAME", serviceName);
    }
    return environment;
  }

  private MockPropertySource propertiesFrom(String path) throws Exception {
    MockPropertySource properties = new MockPropertySource(path);
    PropertiesLoaderUtils.loadProperties(new FileSystemResource(path))
        .forEach((key, value) -> properties.setProperty(key.toString(), value));
    return properties;
  }

  private void configure(LoggerContext context, MockEnvironment environment) throws Exception {
    // Spring's configurator is package-private; isolate the real XML in its own logger context.
    Constructor<?> constructor =
        Class.forName("org.springframework.boot.logging.logback.SpringBootJoranConfigurator")
            .getDeclaredConstructor(LoggingInitializationContext.class);
    JoranConfigurator configurator =
        (JoranConfigurator)
            BeanUtils.instantiateClass(constructor, new LoggingInitializationContext(environment));
    configurator.setContext(context);
    configurator.doConfigure(new ClassPathResource("logback-spring.xml").getURL());
  }
}
