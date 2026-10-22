package it.gov.pagopa.payments.logging;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

class VerticalServiceLoggingInterceptorTest extends DependencyLoggingTestSupport {
  private final VerticalServiceLoggingInterceptor interceptor = new VerticalServiceLoggingInterceptor();
  private final MockClientHttpRequest request = new MockClientHttpRequest(
      HttpMethod.POST, URI.create("https://example.test/SECRET?credential=SECRET"));

  @ParameterizedTest
  @ValueSource(ints = {200, 400, 500})
  void doesNotConsumeOrAlterSoapBodyAndPreservesStatus(int status) throws Exception {
    byte[] body = "<SECRET/>".getBytes(StandardCharsets.UTF_8);
    ClientHttpResponse original = new MockClientHttpResponse(body, status);
    ClientHttpResponse response = interceptor.intercept(request, body, (incoming, bytes) -> {
      assertSame(request, incoming);
      assertSame(body, bytes);
      return original;
    });
    assertTrue(events.isEmpty());
    assertSame(original.getHeaders(), response.getHeaders());
    assertEquals(status, response.getRawStatusCode());
    assertArrayEquals(body, response.getBody().readAllBytes());
    response.close();
    assertEquals(1, events.size());
    assertEvent(0, status, status >= 400 ? "KO" : "OK", status >= 500 ? "WARN" : "INFO");
    assertCallerUnchanged();
  }

  @Test
  void transportFailureAndCallerMdcArePreserved() {
    IOException failure = new IOException("SECRET credential");
    assertSame(failure, assertThrows(IOException.class, () ->
        interceptor.intercept(request, new byte[0], (incoming, bytes) -> { throw failure; })));
    assertEvent(0, null, "KO", "WARN");
    assertCallerUnchanged();
  }

  @Test
  void streamErrorRemainsAnErrorAfterSuccessfulHeaders() throws Exception {
    IOException failure = new IOException("SECRET SOAP body");
    InputStream broken = new InputStream() {
      @Override
      public int read() throws IOException { throw failure; }
    };
    ClientHttpResponse response = interceptor.intercept(request, new byte[0],
        (incoming, bytes) -> new MockClientHttpResponse(broken, 200));
    assertSame(failure, assertThrows(IOException.class, () -> response.getBody().read()));
    response.close();
    assertEquals(1, events.size());
    assertEvent(0, 200, "KO", "WARN");
  }

  @Test
  void responseClosedWithoutReadingLogsOnce() throws Exception {
    ClientHttpResponse response = interceptor.intercept(request, new byte[0],
        (incoming, bytes) -> new MockClientHttpResponse(new ByteArrayInputStream(new byte[0]), 204));
    response.close();
    response.close();
    assertEquals(1, events.size());
    assertEvent(0, 204, "OK", "INFO");
  }
}
