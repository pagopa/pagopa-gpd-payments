package it.gov.pagopa.payments.logging;

import static org.junit.jupiter.api.Assertions.*;

import com.azure.core.http.HttpClient;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpMethod;
import com.azure.core.http.HttpPipeline;
import com.azure.core.http.HttpPipelineBuilder;
import com.azure.core.http.HttpPipelinePosition;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.azure.core.http.policy.FixedDelayOptions;
import com.azure.core.http.policy.RetryOptions;
import com.azure.core.http.policy.RetryPolicy;
import com.azure.core.http.rest.PagedFlux;
import com.azure.core.http.rest.PagedResponse;
import com.azure.core.http.rest.PagedResponseBase;
import com.azure.core.util.Context;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

class AzureStorageLoggingPolicyTest extends DependencyLoggingTestSupport {
  @ParameterizedTest
  @ValueSource(ints = {200, 404, 503})
  void observesAsyncBodyWithoutChangingStatusOrBytes(int status) {
    HttpPipeline pipeline = pipeline("azure-tables",
        request -> Mono.just(response(request, status, Flux.just(bytes()))));
    HttpResponse response = pipeline.send(request()).block();
    assertTrue(events.isEmpty());
    assertEquals(status, response.getStatusCode());
    assertEquals("SECRET body", response.getBodyAsString().block());
    response.close();
    assertEquals(1, events.size());
    assertEquals("azure-tables.getEntity", events.get(0).getMDCPropertyMap().get("method"));
    assertEvent(0, status, status >= 400 ? "KO" : "OK", status >= 500 ? "WARN" : "INFO");
    assertCallerUnchanged();
  }

  @Test
  void observesNativeSyncPipelineAndClose() {
    HttpPipeline pipeline = pipeline("azure-queue",
        request -> Mono.just(response(request, 201, Flux.just(bytes()))));
    HttpResponse response = pipeline.sendSync(
        new HttpRequest(HttpMethod.POST, "https://example.test/SECRET/messages?sig=SECRET"),
        Context.NONE);
    assertTrue(events.isEmpty());
    response.close();
    response.close();
    assertEquals(1, events.size());
    assertEvent(0, 201, "OK", "INFO");
    assertEquals("azure-queue.sendMessage", events.get(0).getMDCPropertyMap().get("method"));
  }

  @Test
  void networkFailureAndStreamFailurePreserveOriginalErrors() {
    IOException failure = new IOException("SECRET credential");
    HttpPipeline unavailable = pipeline("azure-blob", request -> Mono.error(failure));
    RuntimeException thrown = assertThrows(RuntimeException.class,
        () -> unavailable.send(request()).block());
    assertSame(failure, reactor.core.Exceptions.unwrap(thrown));
    assertEvent(0, null, "KO", "WARN");

    HttpPipeline brokenBody = pipeline("azure-blob",
        request -> Mono.just(response(request, 200, Flux.error(failure))));
    HttpResponse response = brokenBody.send(request()).block();
    RuntimeException bodyError = assertThrows(RuntimeException.class,
        () -> response.getBody().collectList().block());
    assertSame(failure, reactor.core.Exceptions.unwrap(bodyError));
    response.close();
    assertEquals(2, events.size());
    assertEvent(1, 200, "KO", "WARN");
  }

  @Test
  void actualRetryPolicyProducesExactlyOneEventPerAttempt() {
    AtomicInteger attempts = new AtomicInteger();
    HttpClient transport = request -> Mono.fromSupplier(() ->
        response(request, attempts.incrementAndGet() == 1 ? 503 : 200, Flux.just(bytes())));
    AzureStorageLoggingPolicy observer = new AzureStorageLoggingPolicy("azure-tables");
    assertEquals(HttpPipelinePosition.PER_RETRY, observer.getPipelinePosition());
    HttpPipeline pipeline = new HttpPipelineBuilder().httpClient(transport)
        .policies(new RetryPolicy(new RetryOptions(new FixedDelayOptions(1, Duration.ofMillis(1)))),
            observer).build();
    HttpResponse response = pipeline.send(request()).block();
    assertEquals("SECRET body", response.getBodyAsString().block());
    response.close();
    assertEquals(2, attempts.get());
    assertEquals(2, events.size());
    assertEvent(0, 503, "KO", "WARN");
    assertEvent(1, 200, "OK", "INFO");
  }

  @Test
  void paginationIsDeferredAndLogsOnlyConsumedNetworkPages() {
    AtomicInteger attempts = new AtomicInteger();
    HttpPipeline pipeline = pipeline("azure-tables", request -> {
      attempts.incrementAndGet();
      return Mono.just(response(request, 200, Flux.just(bytes())));
    });
    java.util.function.Function<String, Mono<PagedResponse<String>>> page = continuation ->
        pipeline.send(new HttpRequest(HttpMethod.GET,
            "https://example.test/SECRET?continuation=SECRET"))
            .flatMap(response -> response.getBodyAsString().map(body -> {
              response.close();
              return new PagedResponseBase<Void, String>(response.getRequest(), 200,
                  response.getHeaders(), List.of("item"),
                  continuation == null ? "next" : null, null);
            }));
    PagedFlux<String> pages = new PagedFlux<>(() -> page.apply(null), page);
    assertEquals(0, attempts.get());
    assertTrue(events.isEmpty());
    assertEquals(2, pages.byPage().collectList().block().size());
    assertEquals(2, attempts.get());
    assertEquals(2, events.size());
    for (int index = 0; index < 2; index++) {
      assertEvent(index, 200, "OK", "INFO");
      assertEquals("azure-tables.listEntities", events.get(index).getMDCPropertyMap().get("method"));
    }
  }

  @Test
  void capturesCallerMdcAndRestoresWorkerMdcOnAsyncBodyCompletion() {
    AtomicReference<Map<String, String>> workerAfter = new AtomicReference<>();
    HttpPipeline pipeline = pipeline("azure-blob", request ->
        Mono.just(response(request, 200, Flux.defer(() -> {
          MDC.clear();
          MDC.put("method", "worker");
          MDC.put("status", "worker-status");
          return Flux.just(bytes());
        }))));
    HttpResponse response = pipeline.send(request()).block();
    response.getBody().subscribeOn(Schedulers.boundedElastic())
        .doOnComplete(() -> {
          workerAfter.set(MDC.getCopyOfContextMap());
          MDC.clear();
        }).collectList().block();
    assertEvent(0, 200, "OK", "INFO");
    assertEquals("worker", workerAfter.get().get("method"));
    assertEquals("worker-status", workerAfter.get().get("status"));
    assertCallerUnchanged();
  }

  @Test
  void rejectsUncontrolledDependencyLabels() {
    assertThrows(IllegalArgumentException.class,
        () -> new AzureStorageLoggingPolicy("https://SECRET"));
  }

  @Test
  void nativeInputStreamIsObservedUntilConsumedAndBlobListIsControlled() throws Exception {
    HttpPipeline pipeline = pipeline("azure-blob",
        request -> Mono.just(response(request, 200, Flux.just(bytes()))));
    HttpResponse response = pipeline.send(new HttpRequest(HttpMethod.GET,
        "https://example.test/SECRET?comp=list&sig=SECRET")).block();
    InputStream body = response.getBodyAsInputStream().block();
    assertTrue(events.isEmpty());
    assertEquals("SECRET body", new String(body.readAllBytes(), StandardCharsets.UTF_8));
    body.close();
    response.close();
    assertEquals(1, events.size());
    assertEvent(0, 200, "OK", "INFO");
    assertEquals("azure-blob.list", events.get(0).getMDCPropertyMap().get("method"));
  }

  private static HttpPipeline pipeline(String dependency, HttpClient transport) {
    return new HttpPipelineBuilder().httpClient(transport)
        .policies(new AzureStorageLoggingPolicy(dependency)).build();
  }

  private static HttpRequest request() {
    return new HttpRequest(HttpMethod.GET,
        "https://example.test/SECRET(PartitionKey='SECRET',RowKey='SECRET')?sig=SECRET");
  }

  private static ByteBuffer bytes() {
    return ByteBuffer.wrap("SECRET body".getBytes(StandardCharsets.UTF_8));
  }

  private static HttpResponse response(HttpRequest request, int status, Flux<ByteBuffer> body) {
    return new HttpResponse(request) {
      private final HttpHeaders headers = new HttpHeaders();
      @Override
      public int getStatusCode() { return status; }
      @Override
      public String getHeaderValue(String name) { return headers.getValue(name); }
      @Override
      public HttpHeaders getHeaders() { return headers; }
      @Override
      public Flux<ByteBuffer> getBody() { return body; }
      @Override
      public Mono<byte[]> getBodyAsByteArray() {
        return body.collectList().map(buffers -> {
          java.io.ByteArrayOutputStream result = new java.io.ByteArrayOutputStream();
          buffers.forEach(buffer -> {
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            result.write(bytes, 0, bytes.length);
          });
          return result.toByteArray();
        });
      }
      @Override
      public Mono<String> getBodyAsString() { return getBodyAsString(StandardCharsets.UTF_8); }
      @Override
      public Mono<String> getBodyAsString(Charset charset) {
        return getBodyAsByteArray().map(bytes -> new String(bytes, charset));
      }
    };
  }
}
