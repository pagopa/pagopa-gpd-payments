package it.gov.pagopa.payments.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.util.Map;

import it.gov.pagopa.payments.config.logging.LogContext;
import it.gov.pagopa.payments.config.logging.VerticalLoggingInterceptor;
import org.slf4j.event.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class VerticalLoggingInterceptorTest {
  @ParameterizedTest
  @CsvSource({"INFO,200,INFO", "DEBUG,200,DEBUG", "DEBUG,204,DEBUG",
      "DEBUG,302,WARN", "DEBUG,409,WARN", "DEBUG,503,ERROR"})
  void configuredLevelPreservesResponseErrorHandling(
      Level configured, int code, String level) {
    RestTemplate template = new RestTemplateConfig().restTemplate(
        new VerticalLoggingInterceptor(configured));
    MockRestServiceServer server = MockRestServiceServer.bindTo(template).build();
    server.expect(requestTo("https://example.test/vertical"))
        .andRespond(withStatus(HttpStatus.valueOf(code)));
    try (LogCapture logs = new LogCapture(VerticalLoggingInterceptor.class)) {
      if (code >= 400) {
        assertThrows(org.springframework.web.client.HttpStatusCodeException.class,
            () -> template.postForEntity("https://example.test/vertical", null, String.class));
      } else {
        assertEquals(code, template.postForEntity(
            "https://example.test/vertical", null, String.class).getStatusCodeValue());
      }
      assertEquals(1, logs.events().size());
      assertEquals(level, logs.events().get(0).path(LogContext.LOG_LEVEL).asText());
    }
    server.verify();
  }

  @ParameterizedTest
  @CsvSource({"200,INFO,OK", "204,INFO,OK", "302,WARN,OK", "404,WARN,KO", "500,ERROR,KO"})
  void responsePreservesTransportAndEmitsSafeCorrelatedFields(int code, String level, String status)
      throws IOException {
    Map<String, String> previous = MDC.getCopyOfContextMap();
    ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
    ClientHttpResponse response = mock(ClientHttpResponse.class);
    MockClientHttpRequest request = new MockClientHttpRequest(
        HttpMethod.POST, URI.create("https://example.test/private-path?key=private-key"));
    byte[] body = "private-body".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    when(execution.execute(request, body)).thenReturn(response);
    when(response.getRawStatusCode()).thenReturn(code);
    try (LogCapture logs = new LogCapture(VerticalLoggingInterceptor.class)) {
      MDC.clear();
      MDC.put(LogContext.CORRELATION_ID, "request-123");
      MDC.put(LogContext.METHOD, "paDemandPaymentNotice");
      assertSame(response, new VerticalLoggingInterceptor().intercept(request, body, execution));
      assertEquals(1, logs.events().size());
      JsonNode event = logs.events().get(0);
      assertEquals("vertical-service", event.path(LogContext.DEPENDENCY).textValue());
      assertEquals("request-123", event.path(LogContext.REQUEST_ID).textValue());
      assertEquals(level, event.path(LogContext.LOG_LEVEL).textValue());
      assertEquals(status, event.path(LogContext.STATUS).textValue());
      assertEquals("vertical-service.POST", LogCapture.dependency(event).path(LogContext.NAME).asText());
      assertEquals(code, LogCapture.dependency(event).path(LogContext.HTTP_CODE).intValue());
      assertTrue(event.path(LogContext.RESPONSE_TIME).isIntegralNumber());
      assertFalse(event.has(LogContext.METHOD));
      assertFalse(event.has(LogContext.OPERATION_ID));
      assertFalse(event.has(LogContext.HTTP_CODE));
      assertFalse(event.toString().contains("private-"));
      assertEquals("paDemandPaymentNotice", MDC.get(LogContext.METHOD));
      verify(response).getRawStatusCode();
      verifyNoMoreInteractions(response);
      verify(execution).execute(same(request), same(body));
    } finally {
      LogContext.restore(previous);
    }
  }

  @Test
  void transportFailureKeepsExceptionWithoutInventingHttpCode() throws IOException {
    ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
    MockClientHttpRequest request = new MockClientHttpRequest();
    byte[] body = new byte[0];
    IOException failure = new IOException("private-exception");
    when(execution.execute(request, body)).thenThrow(failure);
    VerticalLoggingInterceptor interceptor = new VerticalLoggingInterceptor();
    try (LogCapture logs = new LogCapture(VerticalLoggingInterceptor.class)) {
      assertSame(failure, assertThrows(IOException.class,
          () -> interceptor.intercept(request, body, execution)));
      assertEquals(1, logs.events().size());
      JsonNode event = logs.events().get(0);
      assertEquals("vertical-service", event.path(LogContext.DEPENDENCY).textValue());
      assertEquals("WARN", event.path(LogContext.LOG_LEVEL).textValue());
      assertEquals("KO", event.path(LogContext.STATUS).textValue());
      assertEquals(IOException.class.getName(), event.path(LogContext.ERROR_TYPE).textValue());
      assertFalse(LogCapture.dependency(event).has(LogContext.HTTP_CODE));
      assertFalse(event.toString().contains("private-exception"));
    }
  }

  @Test
  void configuredRestTemplateLogsBeforeItsExistingErrorHandlerThrows() {
    RestTemplate template = new RestTemplateConfig().restTemplate(
        new VerticalLoggingInterceptor(Level.INFO));
    MockRestServiceServer server = MockRestServiceServer.bindTo(template).build();
    server.expect(requestTo("https://example.test/vertical"))
        .andRespond(withStatus(HttpStatus.CONFLICT));
    try (LogCapture logs = new LogCapture(VerticalLoggingInterceptor.class)) {
      assertThrows(HttpClientErrorException.Conflict.class,
          () -> template.postForEntity("https://example.test/vertical", null, String.class));
      assertEquals(1, logs.events().size());
      assertEquals(409, LogCapture.dependency(logs.events().get(0)).path(LogContext.HTTP_CODE).intValue());
      assertEquals("vertical-service", logs.events().get(0).path(LogContext.DEPENDENCY).asText());
    }
    server.verify();
  }
}
