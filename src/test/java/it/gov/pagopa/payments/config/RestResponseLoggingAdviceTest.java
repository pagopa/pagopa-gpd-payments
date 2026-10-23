package it.gov.pagopa.payments.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.fasterxml.jackson.databind.JsonNode;
import it.gov.pagopa.payments.model.ProblemJson;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.servlet.error.BasicErrorController;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RestResponseLoggingAdviceTest {

  @Test
  void basicErrorControllerProjectsOnlyItsPublicErrorAndMessage() throws Exception {
    Map<String, Object> body = Map.of(
        "error", "Not Found", "message", "Public error message",
        "exception", "PRIVATE exception", "trace", "PRIVATE trace", "path", "/PRIVATE");

    JsonNode logEntry = observe(body, basicErrorReturnType(), 404);

    assertEquals("Not Found", logEntry.path("faultCode").textValue());
    assertEquals("Public error message", logEntry.path("faultDetail").textValue());
    assertFalse(logEntry.toString().contains("PRIVATE"));
    assertFalse(logEntry.has("ctx_soap_fault_code"));
    assertFalse(logEntry.has("ctx_soap_outcome"));
  }

  @Test
  void arbitraryErrorResponseMapsAreNotInspected() throws Exception {
    JsonNode logEntry = observe(
        Map.of("error", "PRIVATE", "message", "PRIVATE"), ordinaryReturnType(), 400);

    assertFalse(logEntry.has("faultCode"));
    assertFalse(logEntry.has("faultDetail"));
    assertFalse(logEntry.toString().contains("PRIVATE"));
  }

  @Test
  void basicErrorControllerDoesNotStringifyNonTextualOrEmptyMetadata() throws Exception {
    JsonNode logEntry = observe(
        Map.of("error", Map.of("secret", "PRIVATE"), "message", ""), basicErrorReturnType(), 500);

    assertFalse(logEntry.has("faultCode"));
    assertFalse(logEntry.has("faultDetail"));
    assertFalse(logEntry.toString().contains("PRIVATE"));
  }

  @Test
  void successfulResponsesDoNotAcquireFaultMetadataFromProblemShapedBodies() throws Exception {
    ProblemJson body = ProblemJson.builder().status(200).title("Not a fault")
        .detail("Not a failure").build();

    JsonNode logEntry = observe(body, ordinaryReturnType(), 200);

    assertFalse(logEntry.has("faultCode"));
    assertFalse(logEntry.has("faultDetail"));
    assertEquals("OK", logEntry.path("status").textValue());
  }

  @Test
  void publicProblemMetadataDoesNotChangeTheActualHttpStatus() throws Exception {
    ProblemJson body = ProblemJson.builder().status(500).title("Public title")
        .detail("Public detail").build();

    JsonNode logEntry = observe(body, ordinaryReturnType(), 422);

    assertEquals(422, logEntry.path("http_code").intValue());
    assertEquals("Public title", logEntry.path("faultCode").textValue());
    assertEquals("Public detail", logEntry.path("faultDetail").textValue());
    assertEquals(500, body.getStatus());
  }

  private JsonNode observe(Object body, MethodParameter returnType, int httpStatus) throws Exception {
    RequestFilter filter = new RequestFilter();
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    try (RequestLogCapture logs = new RequestLogCapture()) {
      filter.doFilter(request, response, (req, res) -> {
        response.setStatus(httpStatus);
        assertSame(body, new RestResponseLoggingAdvice().beforeBodyWrite(
            body, returnType, MediaType.APPLICATION_JSON, MappingJackson2HttpMessageConverter.class,
            new ServletServerHttpRequest(request), new ServletServerHttpResponse(response)));
      });
      filter.logCompletedRequest(request);
      return logs.singleSummary();
    }
  }

  private MethodParameter basicErrorReturnType() throws Exception {
    return new MethodParameter(
        BasicErrorController.class.getMethod("error", HttpServletRequest.class), -1);
  }

  private MethodParameter ordinaryReturnType() throws Exception {
    return new MethodParameter(getClass().getDeclaredMethod("ordinaryReturnType"), -1);
  }
}
