package it.gov.pagopa.payments.config.feign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import feign.RequestTemplate;
import feign.Feign;
import feign.RequestLine;
import feign.Response;
import it.gov.pagopa.payments.logging.ApplicationLogCapture;
import it.gov.pagopa.payments.logging.HttpRequestLogContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class AuthFeignConfigTest {

  private Map<String, String> previousMdc;
  private final AuthFeignConfig configuration = new TestConfiguration();

  @BeforeEach
  void setUp() {
    previousMdc = MDC.getCopyOfContextMap();
    MDC.clear();
  }

  @AfterEach
  void tearDown() {
    if (previousMdc == null) {
      MDC.clear();
    } else {
      MDC.setContextMap(previousMdc);
    }
  }

  @Test
  void propagatesTheSharedCorrelationKeyWithoutChangingAuthentication() {
    MDC.put(HttpRequestLogContext.CORRELATION_ID, "request-1");
    MDC.put("requestId", "obsolete-value");
    RequestTemplate request = new RequestTemplate();

    configuration.requestIdInterceptor().apply(request);

    assertEquals(List.of("request-1"), List.copyOf(request.headers().get("X-Request-Id")));
    assertEquals(List.of("test-key"),
        List.copyOf(request.headers().get("Ocp-Apim-Subscription-Key")));
  }

  @Test
  void doesNotInventAnOutgoingIdentifierWhenThereIsNoExecutionContext() {
    RequestTemplate request = new RequestTemplate();

    configuration.requestIdInterceptor().apply(request);

    assertFalse(request.headers().containsKey("X-Request-Id"));
  }

  @Test
  void fullWireLoggingCannotDumpCredentialsOrBodies() throws Exception {
    try (ApplicationLogCapture logs = new ApplicationLogCapture()) {
      String response = Feign.builder()
          .logger(configuration.feignLogger())
          .logLevel(feign.Logger.Level.FULL)
          .addCapability(configuration.dependencyLoggingCapability())
          .requestInterceptor(configuration.requestIdInterceptor())
          .client((request, options) -> Response.builder()
              .request(request).status(200).headers(Map.of())
              .body("SECRET response", java.nio.charset.StandardCharsets.UTF_8).build())
          .target(TestApi.class, "https://example.invalid")
          .get();
      assertEquals("SECRET response", response);
      assertEquals(1, logs.events.size());
      assertEquals("Dependency interaction completed", logs.events.get(0).getMessage());
      assertFalse(logs.encodedEvent(logs.events.get(0)).toString().contains("SECRET"));
    }
  }

  public interface TestApi {
    @RequestLine("GET /SECRET?key=SECRET")
    String get();
  }

  private static class TestConfiguration extends AuthFeignConfig {
    TestConfiguration() {
      subscriptionKey = "test-key";
    }
  }
}
