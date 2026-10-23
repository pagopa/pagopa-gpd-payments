package it.gov.pagopa.payments.config;

import static it.gov.pagopa.payments.config.RequestLogCapture.arguments;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import ch.qos.logback.classic.Level;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@SpringBootTest(
    classes = ActuatorHealthLoggingTest.TestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.endpoint.health.probes.enabled=true",
      "management.health.defaults.enabled=false",
      "management.endpoints.web.exposure.include=health"
    })
class ActuatorHealthLoggingTest {

  private static final HttpClient CLIENT = HttpClient.newHttpClient();
  private static final String REQUEST_ID = "probe-request";

  @LocalServerPort private int port;
  @Autowired private ApplicationContext application;
  @SpyBean private RequestFilter filter;
  private RequestLogCapture capture;

  @BeforeEach
  void setUp() {
    capture = new RequestLogCapture();
    resetAvailability();
  }

  @AfterEach
  void tearDown() {
    resetAvailability();
    capture.close();
  }

  @ParameterizedTest
  @ValueSource(strings = {"/actuator/health/liveness", "/actuator/health/readiness"})
  void repeatedSuccessfulProbesProduceNoInfoSummaries(String path) throws Exception {
    for (int index = 0; index < 3; index++) {
      assertEquals(200, getCompleted(path).statusCode());
    }

    assertTrue(capture.summaries().isEmpty());
  }

  @ParameterizedTest
  @CsvSource({
    "/actuator/health/liveness, liveness",
    "/actuator/health/readiness, readiness"
  })
  void successfulProbesKeepTheirStructuredContextAtDebug(String path, String method)
      throws Exception {
    capture.close();
    capture = new RequestLogCapture(Level.DEBUG);

    HttpResponse<String> response = getCompleted(path);

    assertEquals(200, response.statusCode());
    assertEquals(REQUEST_ID, response.headers().firstValue("X-Request-Id").orElseThrow());
    assertSummary(method, "DEBUG", "OK", 200);
  }

  @ParameterizedTest
  @CsvSource({
    "/actuator/health/liveness, liveness",
    "/actuator/health/readiness, readiness"
  })
  void failedProbesRemainVisibleAtWarn(String path, String method) throws Exception {
    AvailabilityChangeEvent.publish(application, LivenessState.BROKEN);
    AvailabilityChangeEvent.publish(application, ReadinessState.REFUSING_TRAFFIC);

    assertEquals(503, getCompleted(path).statusCode());

    assertSummary(method, "WARN", "KO", 503);
  }

  @ParameterizedTest
  @ValueSource(strings = {
    "/actuator/health",
    "/actuator/health/liveness-extra",
    "/actuator/health/readiness-extra"
  })
  void otherActuatorPathsAreNotSilenced(String path) throws Exception {
    HttpResponse<String> response = getCompleted(path);

    assertEquals(path.equals("/actuator/health") ? 200 : 404, response.statusCode());
    JsonNode logEntry = capture.singleSummary();
    assertEquals("INFO", logEntry.path("log.level").textValue());
    assertFalse(logEntry.has("method"));
    assertEquals(response.statusCode(), logEntry.path("http_code").intValue());
    if (response.statusCode() == 404) {
      assertTrue(response.body().isEmpty());
      assertFalse(logEntry.has("faultCode"));
      assertFalse(logEntry.has("faultDetail"));
      assertFalse(logEntry.has("ctx_soap_fault_code"));
    } else {
      assertFalse(logEntry.has("faultCode"));
      assertFalse(logEntry.has("faultDetail"));
    }
  }

  private HttpResponse<String> getCompleted(String path) throws Exception {
    CountDownLatch completed = new CountDownLatch(1);
    doAnswer(invocation -> {
      invocation.callRealMethod();
      completed.countDown();
      return null;
    }).when(filter).logCompletedRequest(any(HttpServletRequest.class));

    HttpResponse<String> response = CLIENT.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("X-Request-Id", REQUEST_ID)
            .header("X-Client-Service-Type", "ACA")
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
    assertTrue(completed.await(5, TimeUnit.SECONDS), "Missing servlet completion");
    return response;
  }

  private void assertSummary(String method, String level, String status, int httpStatus)
      throws Exception {
    JsonNode logEntry = capture.singleSummary();
    assertEquals(method, logEntry.path("method").textValue());
    assertEquals(level, logEntry.path("log.level").textValue());
    assertEquals(status, logEntry.path("status").textValue());
    assertEquals(REQUEST_ID, logEntry.path("correlation_id").textValue());
    assertTrue(logEntry.path("response_time").isIntegralNumber());
    assertTrue(logEntry.path("response_time").longValue() >= 0);
    assertEquals(httpStatus, logEntry.path("http_code").intValue());
    assertEquals("ACA", arguments(logEntry).path("serviceType").textValue());
    assertNotNull(logEntry.get("@timestamp"));
  }

  private void resetAvailability() {
    AvailabilityChangeEvent.publish(application, LivenessState.CORRECT);
    AvailabilityChangeEvent.publish(application, ReadinessState.ACCEPTING_TRAFFIC);
  }

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  @Import({RequestLoggingConfiguration.class, RequestLoggingInterceptor.class, RequestFilter.class,
      RestResponseLoggingAdvice.class})
  static class TestApplication {}
}
