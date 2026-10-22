package it.gov.pagopa.payments.logging;

import static org.junit.jupiter.api.Assertions.*;

import feign.Client;
import feign.MethodMetadata;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import feign.Target;
import it.gov.pagopa.payments.client.ApiConfigCacheClient;
import it.gov.pagopa.payments.client.GpdClient;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FeignDependencyLoggingCapabilityTest extends DependencyLoggingTestSupport {
  private final FeignDependencyLoggingCapability capability = new FeignDependencyLoggingCapability();

  @ParameterizedTest
  @ValueSource(ints = {200, 404, 429, 503})
  void preservesResponseAndEmitsOnceAfterConsumption(int code) throws Exception {
    Request request = request(GpdClient.class, "getPaymentOption",
        "/organizations/{organizationfiscalcode}/paymentoptions/{nav}");
    byte[] bytes = "SECRET body".getBytes(StandardCharsets.UTF_8);
    Client original = (incoming, options) -> {
      assertSame(request, incoming);
      return response(incoming, code, new ByteArrayInputStream(bytes));
    };
    Response response = capability.enrich(original).execute(request, new Request.Options());
    assertEquals(code, response.status());
    assertTrue(events.isEmpty());
    assertArrayEquals(bytes, response.body().asInputStream().readAllBytes());
    response.close();
    assertEquals(1, events.size());
    var fields = assertEvent(0, code, code >= 400 ? "KO" : "OK",
        code >= 500 || code == 429 ? "WARN" : "INFO");
    assertEquals("/organizations/{organizationfiscalcode}/paymentoptions/{nav}",
        fields.path("ctx_details").path("path").asText());
    assertEquals("GpdClient#getPaymentOption", events.get(0).getMDCPropertyMap().get("method"));
    assertCallerUnchanged();
  }

  @Test
  void transportFailureHasNoInventedStatusAndEachRetryGetsAnEvent() {
    IOException failure = new IOException("SECRET credential timeout");
    Client original = (request, options) -> { throw failure; };
    Client logged = capability.enrich(original);
    for (int attempt = 0; attempt < 2; attempt++) {
      assertSame(failure, assertThrows(IOException.class,
          () -> logged.execute(unknownRequest(), new Request.Options())));
      assertEvent(attempt, null, "KO", "WARN");
    }
    assertEquals(2, events.size());
    assertCallerUnchanged();
  }

  @Test
  void streamFailureAfterHeadersDoesNotReportSuccess() throws Exception {
    IOException failure = new IOException("SECRET body");
    InputStream body = new InputStream() {
      @Override
      public int read() throws IOException { throw failure; }
    };
    Response response = capability.enrich((Client) (request, options) -> response(request, 200, body))
        .execute(unknownRequest(), new Request.Options());
    assertSame(failure, assertThrows(IOException.class, () -> response.body().asInputStream().read()));
    response.close();
    assertEquals(1, events.size());
    assertEvent(0, 200, "KO", "WARN");
  }

  @Test
  void closeFailureIsPreservedAndUnknownMetadataNeverUsesUrl() throws Exception {
    IOException failure = new IOException("SECRET close");
    InputStream body = new ByteArrayInputStream(new byte[0]) {
      @Override
      public void close() throws IOException { throw failure; }
    };
    Response response = capability.enrich((Client) (request, options) -> response(request, 200, body))
        .execute(unknownRequest(), new Request.Options());
    assertSame(failure, assertThrows(IOException.class, () -> response.body().close()));
    assertEquals("unknown", assertEvent(0, 200, "KO", "WARN").path("ctx_details").path("path").asText());
  }

  @Test
  void cacheMetadataDropsQueryAndReaderPreservesBytes() throws Exception {
    Request request = request(ApiConfigCacheClient.class, "getCacheByKeys", "/cache");
    Response response = capability.enrich((Client) (incoming, options) ->
        response(incoming, 200, new ByteArrayInputStream("à".getBytes(StandardCharsets.UTF_8))))
        .execute(request, new Request.Options());
    try (var reader = response.body().asReader(StandardCharsets.UTF_8)) {
      assertEquals('à', reader.read());
      assertEquals(-1, reader.read());
    }
    assertEquals("/cache", assertEvent(0, 200, "OK", "INFO").path("ctx_details").path("path").asText());
    assertEquals(1, events.size());
  }

  @Test
  void receiptMetadataUsesDeclaredPathAndNoBodyIsNotConsumed() throws Exception {
    Request request = request(GpdClient.class, "sendPaymentOptionReceipt",
        "/organizations/{organizationfiscalcode}/paymentoptions/{nav}/pay");
    Response response = capability.enrich((Client) (incoming, options) ->
        Response.builder().request(incoming).status(204).headers(Map.of()).build())
        .execute(request, new Request.Options());
    assertNull(response.body());
    assertEquals(1, events.size());
    assertEvent(0, 204, "OK", "INFO");
    assertEquals("GpdClient#sendPaymentOptionReceipt", events.get(0).getMDCPropertyMap().get("method"));
  }

  @Test
  void delegatesNativeReaderAndPreservesItsFailure() throws Exception {
    IOException failure = new IOException("SECRET reader");
    Response.Body body = new Response.Body() {
      @Override
      public Integer length() { return null; }
      @Override
      public boolean isRepeatable() { return false; }
      @Override
      public InputStream asInputStream() { fail("must preserve native reader"); return null; }
      @Override
      public java.io.Reader asReader(java.nio.charset.Charset charset) {
        return new java.io.Reader() {
          @Override
          public int read(char[] chars, int offset, int length) throws IOException { throw failure; }
          @Override
          public void close() {}
        };
      }
      @Override
      public void close() {}
    };
    Response response = capability.enrich((Client) (incoming, options) ->
        Response.builder().request(incoming).status(200).headers(Map.of()).body(body).build())
        .execute(unknownRequest(), new Request.Options());
    assertSame(failure, assertThrows(IOException.class,
        () -> response.body().asReader(StandardCharsets.UTF_8).read()));
    response.close();
    assertEquals(1, events.size());
    assertEvent(0, 200, "KO", "WARN");
  }

  private static Response response(Request request, int status, InputStream body) {
    return Response.builder().request(request).status(status).headers(Map.of()).body(body, null).build();
  }

  private static Request unknownRequest() {
    return Request.create(Request.HttpMethod.GET,
        "https://credential:SECRET@example.test/SECRET?token=SECRET", Map.of(),
        null, StandardCharsets.UTF_8, new RequestTemplate());
  }

  private static <T> Request request(Class<T> type, String name, String path) throws Exception {
    MethodMetadata metadata = new org.springframework.cloud.openfeign.support.SpringMvcContract()
        .parseAndValidateMetadata(type).stream()
        .filter(candidate -> candidate.method().getName().equals(name)).findFirst().orElseThrow();
    assertEquals(path, metadata.template().path());
    metadata.template().query("keys", "SECRET");
    RequestTemplate template = new RequestTemplate()
        .methodMetadata(metadata)
        .feignTarget(new Target.HardCodedTarget<>(type, "https://example.test"));
    return Request.create(Request.HttpMethod.GET, "https://example.test/SECRET?keys=SECRET",
        Map.of("Authorization", java.util.List.of("SECRET")), null, StandardCharsets.UTF_8, template);
  }
}
