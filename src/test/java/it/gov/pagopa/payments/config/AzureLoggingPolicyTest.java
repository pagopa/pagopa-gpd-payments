package it.gov.pagopa.payments.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.azure.core.http.HttpClient;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpMethod;
import com.azure.core.http.HttpPipeline;
import com.azure.core.http.HttpPipelineBuilder;
import com.azure.core.http.HttpPipelineCallContext;
import com.azure.core.http.HttpPipelineNextPolicy;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.azure.core.http.policy.FixedDelay;
import com.azure.core.http.policy.HttpPipelinePolicy;
import com.azure.core.http.policy.RetryPolicy;
import com.azure.core.util.Context;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.MDC;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

class AzureLoggingPolicyTest {
  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private Map<String, String> originalMdc;
  private Map<String, String> callerMdc;

  @BeforeEach
  void saveMdc() {
    originalMdc = MDC.getCopyOfContextMap();
    callerMdc = Map.of(
        "correlation_id", "request-correlation",
        "ctx_creditor_id", "creditor",
        "ctx_notice_number", "notice",
        "ctx_iuv", "iuv",
        "method", "inherited-inbound-method",
        "unrelated", "private-mdc-value");
    MDC.setContextMap(callerMdc);
  }

  @AfterEach
  void restoreMdc() {
    LogContext.restore(originalMdc);
  }

  @ParameterizedTest
  @CsvSource({"200, INFO, OK", "204, INFO, OK", "404, INFO, KO", "429, WARN, KO", "503, WARN, KO"})
  void syncResponsesPreserveResultAndLogSafeTypedFields(int status, String level, String outcome) {
    HttpClient client = mock(HttpClient.class);
    HttpResponse response = response(status);
    when(client.sendSync(any(HttpRequest.class), any(Context.class))).thenReturn(response);

    try (LogCapture capture = new LogCapture(AzureLoggingPolicy.class)) {
      assertSame(response, pipeline(client).sendSync(request(), Context.NONE));

      assertEquals(1, capture.events().size());
      assertResponse(capture.events().get(0), status, level, outcome);
      assertSafeOutput(capture);
      assertEquals(callerMdc, MDC.getCopyOfContextMap());
    }
    verifyNoBodyReads(response);
  }

  @Test
  void syncTransportFailurePreservesExceptionWithoutLoggingItsMessageOrHttpCode() {
    HttpClient client = mock(HttpClient.class);
    RuntimeException failure = new IllegalStateException("private-exception-message");
    when(client.sendSync(any(HttpRequest.class), any(Context.class))).thenThrow(failure);

    try (LogCapture capture = new LogCapture(AzureLoggingPolicy.class)) {
      assertSame(failure, assertThrows(IllegalStateException.class,
          () -> pipeline(client).sendSync(request(), Context.NONE)));

      assertFailure(capture, failure);
      assertEquals(callerMdc, MDC.getCopyOfContextMap());
    }
  }

  @Test
  void asyncRetryLogsEachAttemptWithCapturedCorrelationAndRestoresWorkerMdc() {
    HttpClient client = mock(HttpClient.class);
    HttpResponse retryable = response(503);
    HttpResponse success = response(200);
    when(client.send(any(HttpRequest.class), any(Context.class)))
        .thenReturn(Mono.just(retryable), Mono.just(success));
    Scheduler worker = Schedulers.newSingle("azure-policy-test");
    Map<String, String> workerMdc = Map.of(
        "correlation_id", "worker-correlation", "method", "worker-method", "worker", "kept");
    HttpPipelinePolicy switchThread = new HttpPipelinePolicy() {
      @Override
      public Mono<HttpResponse> process(HttpPipelineCallContext context, HttpPipelineNextPolicy next) {
        return Mono.defer(next::process).subscribeOn(worker);
      }
    };
    HttpPipeline pipeline = new HttpPipelineBuilder().httpClient(client)
        .policies(AzureLoggingPolicy.correlationPolicy(), switchThread,
            new RetryPolicy(new FixedDelay(1, Duration.ZERO)),
            new AzureLoggingPolicy(AzureLoggingPolicy.TABLES))
        .build();

    try (LogCapture capture = new LogCapture(AzureLoggingPolicy.class)) {
      Mono.fromRunnable(() -> MDC.setContextMap(workerMdc)).subscribeOn(worker).block(TIMEOUT);

      assertSame(success, pipeline.send(request()).block(TIMEOUT));

      assertEquals(2, capture.events().size());
      JsonNode first = capture.events().get(0);
      JsonNode second = capture.events().get(1);
      assertResponse(first, 503, "WARN", "KO");
      assertResponse(second, 200, "INFO", "OK");
      assertTrue(first.path("ctx_details").path("attempt").isIntegralNumber());
      assertEquals(1, first.path("ctx_details").path("attempt").intValue());
      assertTrue(second.path("ctx_details").path("attempt").isIntegralNumber());
      assertEquals(2, second.path("ctx_details").path("attempt").intValue());
      assertNotEquals(Thread.currentThread().getName(), first.path("process.thread.name").asText());
      assertEquals(workerMdc, Mono.fromCallable(MDC::getCopyOfContextMap)
          .subscribeOn(worker).block(TIMEOUT));
      assertEquals(callerMdc, MDC.getCopyOfContextMap());
      assertSafeOutput(capture);
    } finally {
      try {
        Mono.fromRunnable(MDC::clear).subscribeOn(worker).block(TIMEOUT);
      } finally {
        worker.dispose();
      }
    }
    verify(client, times(2)).send(any(HttpRequest.class), any(Context.class));
    verify(retryable).close();
    verify(success, never()).close();
    verifyNoBodyReads(retryable);
    verifyNoBodyReads(success);
  }

  @Test
  void asyncTransportFailurePreservesExceptionAndLogsOneSanitizedWarning() {
    HttpClient client = mock(HttpClient.class);
    RuntimeException failure = new IllegalArgumentException("private-exception-message");
    when(client.send(any(HttpRequest.class), any(Context.class))).thenReturn(Mono.error(failure));

    try (LogCapture capture = new LogCapture(AzureLoggingPolicy.class)) {
      assertSame(failure, assertThrows(IllegalArgumentException.class,
          () -> pipeline(client).send(request()).block(TIMEOUT)));

      assertFailure(capture, failure);
      assertEquals(callerMdc, MDC.getCopyOfContextMap());
    }
  }

  private static HttpPipeline pipeline(HttpClient client) {
    return new HttpPipelineBuilder().httpClient(client)
        .policies(AzureLoggingPolicy.correlationPolicy(),
            new AzureLoggingPolicy(AzureLoggingPolicy.TABLES))
        .build();
  }

  private static HttpRequest request() {
    return new HttpRequest(HttpMethod.GET,
        "https://storage.example.test/private-url-path?token=private-query-value")
        .setHeader("Authorization", "private-header-value")
        .setBody("private-body-value");
  }

  private static HttpResponse response(int status) {
    HttpResponse response = mock(HttpResponse.class);
    when(response.getStatusCode()).thenReturn(status);
    when(response.getHeaders()).thenReturn(new HttpHeaders());
    return response;
  }

  private static void assertResponse(JsonNode event, int status, String level, String outcome) {
    assertCommon(event);
    JsonNode dependency = event.path("ctx_details").path("dependency");
    assertTrue(dependency.path("http_code").isIntegralNumber());
    assertEquals(status, dependency.path("http_code").intValue());
    assertEquals(level, event.path("log.level").asText());
    assertEquals(outcome, event.path("status").asText());
    assertFalse(event.has("error.type"));
  }

  private static void assertFailure(LogCapture capture, RuntimeException failure) {
    assertEquals(1, capture.events().size());
    JsonNode event = capture.events().get(0);
    assertCommon(event);
    assertEquals("WARN", event.path("log.level").asText());
    assertEquals("KO", event.path("status").asText());
    assertEquals(failure.getClass().getName(), event.path("error.type").asText());
    assertFalse(event.has("http_code"));
    assertFalse(event.path("ctx_details").path("dependency").has("http_code"));
    assertFalse(event.has("error.message"));
    assertFalse(event.has("error.stack_trace"));
    assertSafeOutput(capture);
  }

  private static void assertCommon(JsonNode event) {
    assertEquals("Completed Azure HTTP attempt", event.path("message").asText());
    assertEquals("request-correlation", event.path("correlation_id").asText());
    assertEquals("creditor", event.path("ctx_creditor_id").asText());
    assertEquals("notice", event.path("ctx_notice_number").asText());
    assertEquals("iuv", event.path("ctx_iuv").asText());
    assertEquals("azure-tables.GET",
        event.path("ctx_details").path("dependency").path("name").asText());
    assertEquals("storage.example.test", event.path("ctx_details").path("host").asText());
    assertTrue(event.path("response_time").isIntegralNumber());
    assertTrue(event.path("response_time").longValue() >= 0);
    for (String forbidden : new String[] {
        "method", "dependency", "http_code", "path", "url", "query", "body", "headers", "unrelated", "worker"}) {
      assertFalse(event.has(forbidden), forbidden);
    }
  }

  private static void assertSafeOutput(LogCapture capture) {
    String output = String.join("\n", capture.lines());
    for (String secret : new String[] {
        "private-url-path", "private-query-value", "private-header-value", "private-body-value",
        "private-exception-message", "private-mdc-value", "inherited-inbound-method", "worker-method",
        "worker-correlation"}) {
      assertFalse(output.contains(secret), secret);
    }
  }

  private static void verifyNoBodyReads(HttpResponse response) {
    verify(response, never()).getBody();
    verify(response, never()).getBodyAsBinaryData();
    verify(response, never()).getBodyAsByteArray();
    verify(response, never()).getBodyAsString();
    verify(response, never()).getBodyAsString(any(java.nio.charset.Charset.class));
  }
}
