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
import org.junit.jupiter.api.Test;
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
  void preservesProfileSpecificAppenderAndLegacyOutput(String profiles) throws Exception {
    String legacy = logForProfile(profiles, false);

    assertTrue(legacy.contains("Legacy operation completed"));
    assertFalse(legacy.contains("event.duration"));
    assertFalse(legacy.contains("args="));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "default", "default,local"})
  void jsonProfilesPreserveTypedContextAndTechnicalFailure(String profiles) throws Exception {
    String text = logForProfile(profiles, true);
    JsonNode logEntry = new ObjectMapper().readTree(text);

    assertEquals(1, text.lines().count());
    assertEquals("pagopa-gpd-payments", logEntry.path("service.name").asText());
    assertEquals("test-version", logEntry.path("service.version").asText());
    assertEquals("dev", logEntry.path("service.environment").asText());
    assertEquals("Operation completed", logEntry.path("message").asText());
    assertEquals("request-1", logEntry.path("correlation_id").asText());
    assertEquals("testOperation", logEntry.path("method").asText());
    assertEquals("OK", logEntry.path("status").asText());
    assertFalse(logEntry.has("event_action"));
    assertFalse(logEntry.has("event_outcome"));
    assertTrue(logEntry.path("event.duration").isIntegralNumber());
    assertTrue(logEntry.path("http.response.status_code").isIntegralNumber());
    assertTrue(logEntry.path("args").isObject());
    assertEquals(IllegalStateException.class.getName(), logEntry.path("error.type").asText());
  }

  @Test
  void localProfilePreservesReadableContextAndTechnicalFailure() throws Exception {
    String text = logForProfile("local", true);

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

  private String logForProfile(String profiles, boolean structured) throws Exception {
    MockEnvironment environment = environment("dev", "pagopa-gpd-payments");
    if (!profiles.isEmpty()) {
      environment.setActiveProfiles(profiles.split(","));
    }
    boolean jsonOutput = profiles.isEmpty() || profiles.contains("default");
    LoggerContext context = new LoggerContext();
    Map<String, String> previousMdc = MDC.getCopyOfContextMap();
    try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
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
      appender.setOutputStream(output);
      if (structured) {
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
      } else {
        context.getLogger("test.logger").info("Legacy operation completed");
      }
      return output.toString(StandardCharsets.UTF_8);
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
