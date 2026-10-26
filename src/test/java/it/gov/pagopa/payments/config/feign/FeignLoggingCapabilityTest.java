package it.gov.pagopa.payments.config.feign;

import static it.gov.pagopa.payments.config.LogContext.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import feign.Client;
import feign.Feign;
import feign.FeignException;
import feign.MethodMetadata;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import feign.Retryer;
import feign.Target;
import feign.codec.Decoder;
import it.gov.pagopa.payments.client.ApiConfigCacheClient;
import it.gov.pagopa.payments.client.GpdClient;
import it.gov.pagopa.payments.config.LogCapture;
import it.gov.pagopa.payments.config.LogContext;
import it.gov.pagopa.payments.config.LoggingAspect;
import it.gov.pagopa.payments.model.PaymentOptionModel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.MDC;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class FeignLoggingCapabilityTest {
  private final Client transport = mock(Client.class);
  private final Decoder decoder = mock(Decoder.class);
  private final Request.Options options = new Request.Options();
  private Map<String, String> previousMdc;

  @BeforeEach
  void isolateMdc() {
    previousMdc = MDC.getCopyOfContextMap();
    MDC.clear();
    MDC.put(CORRELATION_ID, "request-123");
    MDC.put(METHOD, "paVerifyPaymentNotice");
  }

  @AfterEach
  void restoreMdc() {
    LogContext.restore(previousMdc);
  }

  @ParameterizedTest
  @CsvSource({"200,DEBUG,OK", "201,WARN,OK", "204,WARN,OK", "404,WARN,KO",
      "409,WARN,KO", "429,WARN,KO", "500,ERROR,KO", "503,ERROR,KO"})
  void actualFeignResponseProducesExactlyOneHttpEvent(int code, String level, String outcome)
      throws IOException {
    when(transport.execute(any(), any())).thenAnswer(invocation ->
        Response.builder().request(invocation.getArgument(0)).status(code).reason("Response")
            .headers(Map.of()).body(code == 204 ? null : "{}", StandardCharsets.UTF_8).build());
    GpdClient client = client(Retryer.NEVER_RETRY);
    try (LogCapture httpLogs = new LogCapture(FeignLoggingCapability.class);
        LogCapture aspectLogs = new LogCapture(LoggingAspect.class)) {
      if (code >= 400) {
        assertThrows(FeignException.class, () -> client.getPaymentOption("private-creditor", "private-nav"));
      } else {
        assertNull(client.getPaymentOption("private-creditor", "private-nav"));
      }
      assertEquals(1, httpLogs.events().size());
      assertTrue(aspectLogs.events().isEmpty(), "The aspect must not log Feign calls");
      JsonNode event = httpLogs.events().get(0);
      assertEquals("Completed Feign HTTP attempt", event.path(MESSAGE).asText());
      JsonNode dependency = LogCapture.dependency(event);
      assertEquals("/organizations/{organizationfiscalcode}/paymentoptions/{nav}",
          dependency.path(PATH).asText());
      assertEquals("gpd.getPaymentOption", dependency.path(NAME).asText());
      assertEquals(code, dependency.path(HTTP_CODE).intValue());
      assertTrue(dependency.path(HTTP_CODE).isIntegralNumber());
      assertEquals(3, dependency.size(), "Dependency must contain only path, name and http_code");
      assertEquals(level, event.path(LOG_LEVEL).asText());
      assertEquals(outcome, event.path(STATUS).asText());
      assertEquals("request-123", event.path(CORRELATION_ID).asText());
      assertEquals("request-123", event.path(REQUEST_ID).asText());
      assertFalse(event.has(OPERATION_ID));
      assertTrue(event.path(RESPONSE_TIME).isIntegralNumber());
      assertTrue(event.path(RESPONSE_TIME).longValue() >= 0);
      assertFalse(event.has(HTTP_CODE));
      assertFalse(event.has(DEPENDENCY));
      assertFalse(event.has(METHOD));
      assertFalse(event.toString().contains("private-"));
      assertEquals("paVerifyPaymentNotice", MDC.get(METHOD));
    }
    verify(transport).execute(any(), same(options));
  }

  @Test
  void wrapperPreservesResponseBodyAndExceptionIdentity() throws IOException {
    Response.Body body = mock(Response.Body.class);
    Request request = requestWithMetadata();
    Response response = Response.builder().request(request)
        .status(200).headers(Map.of()).body(body).build();
    IOException failure = new IOException("Connection refused");
    when(transport.execute(any(), any())).thenReturn(response).thenThrow(failure);
    Client decorated = new FeignLoggingCapability().enrich(transport);
    try (LogCapture logs = new LogCapture(FeignLoggingCapability.class)) {
      assertSame(response, decorated.execute(request, options));
      verify(body).length();
      verifyNoMoreInteractions(body);
      assertSame(failure, assertThrows(IOException.class, () -> decorated.execute(request, options)));
      assertEquals(2, logs.events().size());
      JsonNode event = logs.events().get(1);
      assertEquals("KO", event.path(STATUS).asText());
      assertEquals("WARN", event.path(LOG_LEVEL).asText());
      assertEquals(IOException.class.getName(), event.path(ERROR_TYPE).asText());
      assertEquals("Connection refused", event.path(ERROR_MESSAGE).asText());
      assertFalse(LogCapture.dependency(event).has(HTTP_CODE));
      assertFalse(event.has(HTTP_CODE));
    }
    verify(transport, times(2)).execute(same(request), same(options));
  }

  @Test
  void expectedReceiptConflictIsInfoButKeepsHttpFailureAndActualStatus() throws IOException {
    when(transport.execute(any(), any())).thenAnswer(invocation ->
        Response.builder().request(invocation.getArgument(0)).status(409).reason("Conflict")
            .headers(Map.of()).build());
    GpdClient client = client(Retryer.NEVER_RETRY);
    try (LogCapture logs = new LogCapture(FeignLoggingCapability.class)) {
      assertThrows(FeignException.Conflict.class,
          () -> client.sendPaymentOptionReceipt("creditor", "nav", new PaymentOptionModel()));
      assertEquals(1, logs.events().size());
      JsonNode event = logs.events().get(0);
      assertEquals("INFO", event.path(LOG_LEVEL).asText());
      assertEquals(STATUS_KO, event.path(STATUS).asText());
      assertEquals(409, LogCapture.dependency(event).path(HTTP_CODE).intValue());
    }
  }

  @Test
  void decoderFailureDoesNotRewriteSuccessfulHttpOutcomeOrAddAnEvent() throws IOException {
    when(transport.execute(any(), any())).thenAnswer(invocation ->
        Response.builder().request(invocation.getArgument(0)).status(200)
            .headers(Map.of()).body("invalid", StandardCharsets.UTF_8).build());
    when(decoder.decode(any(), any())).thenThrow(new IOException("Invalid response"));
    GpdClient client = client(Retryer.NEVER_RETRY);
    try (LogCapture logs = new LogCapture(FeignLoggingCapability.class);
        LogCapture aspectLogs = new LogCapture(LoggingAspect.class)) {
      assertThrows(FeignException.class, () -> client.getPaymentOption("creditor", "nav"));
      assertEquals(1, logs.events().size());
      assertEquals(STATUS_OK, logs.events().get(0).path(STATUS).asText());
      assertTrue(aspectLogs.events().isEmpty());
    }
  }

  @ParameterizedTest
  @CsvSource({
      "getPaymentOption, 200, DEBUG", "getPaymentOption, 204, WARN",
      "getPaymentOption, 302, WARN", "getPaymentOption, 404, WARN",
      "getPaymentOption, 409, WARN", "getPaymentOption, 500, ERROR",
      "sendPaymentOptionReceipt, 200, DEBUG", "sendPaymentOptionReceipt, 201, WARN",
      "sendPaymentOptionReceipt, 302, WARN", "sendPaymentOptionReceipt, 400, WARN",
      "sendPaymentOptionReceipt, 401, WARN", "sendPaymentOptionReceipt, 403, WARN",
      "sendPaymentOptionReceipt, 404, WARN", "sendPaymentOptionReceipt, 409, INFO",
      "sendPaymentOptionReceipt, 429, WARN", "sendPaymentOptionReceipt, 503, ERROR",
      "getCacheByKeys, 200, INFO", "getCacheByKeys, 204, INFO",
      "getCacheByKeys, 302, WARN", "getCacheByKeys, 404, WARN",
      "getCacheByKeys, 409, WARN", "getCacheByKeys, 429, WARN",
      "getCacheByKeys, 500, ERROR", "getCacheByKeys, 503, ERROR"
  })
  void responseLevelsAreScopedToTheClientAndOperation(String operation, int code, String level)
      throws IOException {
    Class<?> type = operation.equals("getCacheByKeys") ? ApiConfigCacheClient.class : GpdClient.class;
    MethodMetadata metadata = new SpringMvcContract().parseAndValidateMetadata(type).stream()
        .filter(method -> method.method().getName().equals(operation)).findFirst().orElseThrow();
    RequestTemplate template = new RequestTemplate().methodMetadata(metadata)
        .feignTarget(new Target.HardCodedTarget<>(type, "test-target", "http://localhost"));
    Request request = Request.create(Request.HttpMethod.GET, "http://localhost", Map.of(),
        null, StandardCharsets.UTF_8, template);
    Response response = Response.builder().request(request).status(code).headers(Map.of())
        .body("{}", StandardCharsets.UTF_8).build();
    when(transport.execute(any(), any())).thenReturn(response);
    try (LogCapture logs = new LogCapture(FeignLoggingCapability.class)) {
      assertSame(response, new FeignLoggingCapability().enrich(transport).execute(request, options));
      assertEquals(1, logs.events().size());
      JsonNode event = logs.events().get(0);
      assertEquals(level, event.path(LOG_LEVEL).asText());
      assertEquals(code, LogCapture.dependency(event).path(HTTP_CODE).intValue());
    }
  }

  @Test
  void retriesEmitOneEventPerTransportAttemptNotAnExtraLogicalSummary() throws IOException {
    when(transport.execute(any(), any())).thenThrow(new IOException("Connection refused"))
        .thenAnswer(invocation -> Response.builder().request(invocation.getArgument(0))
            .status(204).headers(Map.of()).build());
    GpdClient client = client(new Retryer.Default(1, 1, 2));
    try (LogCapture logs = new LogCapture(FeignLoggingCapability.class);
        LogCapture aspectLogs = new LogCapture(LoggingAspect.class)) {
      assertNull(client.getPaymentOption("creditor", "nav"));
      assertEquals(2, logs.events().size());
      assertEquals("KO", logs.events().get(0).path(STATUS).asText());
      assertEquals(204, LogCapture.dependency(logs.events().get(1)).path(HTTP_CODE).intValue());
      assertTrue(aspectLogs.events().isEmpty());
    }
    verify(transport, times(2)).execute(any(), same(options));
  }

  @Test
  void springFeignConfigurationInstallsCapabilityOnlyOnce() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            FeignAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class))
        .withUserConfiguration(FeignTestConfiguration.class, GPDFeignConfig.class,
            ApiConfigFeignConfig.class, GPSFeignConfig.class)
        .withPropertyValues("service.gpd.host=http://localhost", "service.cache.host=http://localhost",
            "gpd.subscription-key=local-test", "config-cache.subscription-key=local-test",
            "gps.subscription-key=local-test")
        .run(context -> {
          assertNull(context.getStartupFailure());
          try (LogCapture logs = new LogCapture(FeignLoggingCapability.class)) {
            assertNull(context.getBean(GpdClient.class).getPaymentOption("creditor", "nav"));
            assertEquals(1, logs.events().size());
            assertEquals(204, LogCapture.dependency(logs.events().get(0)).path(HTTP_CODE).intValue());
            assertNull(context.getBean(ApiConfigCacheClient.class).getCacheByKeys("private-query"));
            assertEquals(2, logs.events().size());
            assertEquals("cache.getCacheByKeys",
                LogCapture.dependency(logs.events().get(1)).path(NAME).asText());
            assertEquals("/cache", LogCapture.dependency(logs.events().get(1)).path(PATH).asText());
            assertFalse(logs.lines().toString().contains("private-query"));
            assertFalse(logs.lines().toString().contains("local-test"));
          }
        });
  }

  private GpdClient client(Retryer retryer) {
    GpdClient target = Feign.builder().client(transport).contract(new SpringMvcContract())
        .decoder(decoder).options(options).retryer(retryer)
        .encoder((object, type, template) -> {})
        .addCapability(new GPDFeignConfig("local-test").feignLoggingCapability())
        .target(new Target.HardCodedTarget<>(GpdClient.class, "gpd", "http://localhost"));
    AspectJProxyFactory factory = new AspectJProxyFactory(target);
    factory.setInterfaces(GpdClient.class);
    factory.addAspect(new LoggingAspect());
    return factory.getProxy();
  }

  @ParameterizedTest
  @CsvSource({
      "getPaymentOption,absent,WARN", "getPaymentOption,0,WARN",
      "getPaymentOption,unknown,DEBUG", "getPaymentOption,2,DEBUG",
      "sendPaymentOptionReceipt,absent,WARN", "sendPaymentOptionReceipt,0,WARN",
      "sendPaymentOptionReceipt,unknown,DEBUG", "sendPaymentOptionReceipt,2,DEBUG",
      "getCacheByKeys,absent,INFO", "getCacheByKeys,0,INFO",
      "getCacheByKeys,unknown,INFO", "getCacheByKeys,2,INFO"
  })
  void emptyBodyWarningUsesOnlyGpd200BodyMetadata(String operation, String length, String level)
      throws IOException {
    Class<?> type = operation.equals("getCacheByKeys") ? ApiConfigCacheClient.class : GpdClient.class;
    Request request = requestWithMetadata(type, operation);
    Response.Body body = "absent".equals(length) ? null : mock(Response.Body.class);
    if (body != null && type == GpdClient.class) {
      when(body.length()).thenReturn("unknown".equals(length) ? null : Integer.valueOf(length));
    }
    Response response = Response.builder().request(request).status(200)
        .headers(Map.of()).body(body).build();
    when(transport.execute(any(), any())).thenReturn(response);
    try (LogCapture logs = new LogCapture(FeignLoggingCapability.class)) {
      assertSame(response, new FeignLoggingCapability().enrich(transport).execute(request, options));
      assertEquals(1, logs.events().size());
      JsonNode event = logs.events().get(0);
      boolean warning = "WARN".equals(level);
      assertEquals(level, event.path(LOG_LEVEL).asText());
      assertEquals(warning ? STATUS_KO : STATUS_OK, event.path(STATUS).asText());
      assertEquals(200, LogCapture.dependency(event).path(HTTP_CODE).intValue());
      assertEquals(3, LogCapture.dependency(event).size());
      assertFalse(event.has(HTTP_CODE));
      assertFalse(event.has(ERROR_TYPE));
      assertEquals(warning, event.has(ERROR_MESSAGE));
      if (warning) {
        assertEquals("GPD returned HTTP 200 with an empty response body",
            event.path(ERROR_MESSAGE).asText());
      }
    }
    if (body != null) {
      if (type == GpdClient.class) {
        verify(body).length();
      }
      verifyNoMoreInteractions(body);
    }
    verify(transport).execute(same(request), same(options));
  }

  @ParameterizedTest
  @CsvSource({"false,false", "false,true", "true,false", "true,true"})
  void emptyGpd200OnlyChangesLoggingWithoutExceptionsOrExtraRetries(boolean pay, boolean absent)
      throws IOException {
    when(transport.execute(any(), any())).thenAnswer(invocation ->
        Response.builder().request(invocation.getArgument(0)).status(200).headers(Map.of())
            .body(absent ? null : "", StandardCharsets.UTF_8).build());
    GpdClient client = client(new Retryer.Default(1, 1, 2));
    try (LogCapture logs = new LogCapture(FeignLoggingCapability.class)) {
      if (pay) {
        assertNull(client.sendPaymentOptionReceipt("creditor", "nav", new PaymentOptionModel()));
      } else {
        assertNull(client.getPaymentOption("creditor", "nav"));
      }
      assertEquals(1, logs.events().size());
      JsonNode event = logs.events().get(0);
      assertEquals("WARN", event.path(LOG_LEVEL).asText());
      assertEquals(STATUS_KO, event.path(STATUS).asText());
      assertEquals(200, LogCapture.dependency(event).path(HTTP_CODE).intValue());
    }
    verify(transport).execute(any(), same(options));
  }

  private Request requestWithMetadata() {
    return requestWithMetadata(GpdClient.class, "getPaymentOption");
  }

  private Request requestWithMetadata(Class<?> type, String operation) {
    MethodMetadata metadata = new SpringMvcContract().parseAndValidateMetadata(type)
        .stream().filter(method -> method.method().getName().equals(operation))
        .findFirst().orElseThrow();
    RequestTemplate template = new RequestTemplate().methodMetadata(metadata)
        .feignTarget(new Target.HardCodedTarget<>(type, "test-target", "http://localhost"));
    return Request.create(Request.HttpMethod.GET, "http://localhost/private", Map.of(),
        null, StandardCharsets.UTF_8, template);
  }

  @Configuration(proxyBeanMethods = false)
  @EnableFeignClients(clients = {GpdClient.class, ApiConfigCacheClient.class})
  static class FeignTestConfiguration {
    @Bean
    Client client() {
      return (request, options) -> {
        assertEquals(java.util.List.of("request-123"), request.headers().get(HEADER_REQUEST_ID));
        assertEquals(java.util.List.of("local-test"), request.headers().get("Ocp-Apim-Subscription-Key"));
        return Response.builder().request(request).status(204).headers(Map.of()).build();
      };
    }
  }
}
