package it.gov.pagopa.payments.config;

import static it.gov.pagopa.payments.config.RequestLogCapture.arguments;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.gov.pagopa.payments.logging.HttpRequestLogContext;
import it.gov.pagopa.payments.logging.RequestLogProjection;
import it.gov.pagopa.payments.utils.CommonUtil;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.servlet.DispatcherType;
import javax.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

class RequestFilterTest {

  private final RequestFilter filter = new RequestFilter();
  private Map<String, String> previousMdc;
  private RequestLogCapture capture;

  @BeforeEach
  void setUp() {
    previousMdc = MDC.getCopyOfContextMap();
    MDC.clear();
    capture = new RequestLogCapture();
  }

  @AfterEach
  void tearDown() {
    capture.close();
    if (previousMdc == null) {
      MDC.clear();
    } else {
      MDC.setContextMap(previousMdc);
    }
  }

  @Test
  void scopesCorrelationAndRestoresMdcAtDispatchAndCompletion() throws Exception {
    Map<String, String> outer =
        Map.of("correlation_id", "outer", "method", "outer-method", "status", "KO",
            "args", "outer-args");
    MDC.setContextMap(outer);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Request-Id", "request-123:abc");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, (req, res) -> {
      assertEquals(Map.of("correlation_id", "request-123:abc"), MDC.getCopyOfContextMap());
      HttpRequestLogContext.from(request).setMethod("getReceiptByIUV");
      response.setStatus(201);
    });

    assertEquals(outer, MDC.getCopyOfContextMap());
    assertTrue(capture.summaries().isEmpty());
    filter.logCompletedRequest(request);
    assertEquals(outer, MDC.getCopyOfContextMap());
    JsonNode logEntry = capture.singleSummary();
    assertEquals("request-123:abc", response.getHeader("X-Request-Id"));
    assertEquals("request-123:abc", logEntry.path("correlation_id").asText());
    assertEquals("getReceiptByIUV", logEntry.path("method").asText());
    assertEquals("OK", logEntry.path("status").asText());
    assertFalse(logEntry.has("event_action"));
    assertFalse(logEntry.has("event_outcome"));
    assertFalse(logEntry.has("event.duration"));
    assertFalse(logEntry.has("http.response.status_code"));
    assertFalse(logEntry.has("ctx_soap_fault_code"));
    assertEquals("GPD", arguments(logEntry).path("serviceType").textValue());
    assertEquals(1, arguments(logEntry).size());
    assertTrue(logEntry.path("http_code").isIntegralNumber());
    assertEquals(201, logEntry.path("http_code").intValue());
    assertTrue(logEntry.path("response_time").isIntegralNumber());
    assertTrue(logEntry.path("response_time").longValue() >= 0);
    assertFalse(logEntry.has("requestId"));
    assertFalse(logEntry.has("startTime"));
    assertFalse(logEntry.has("operationId"));
    assertFalse(logEntry.has("httpCode"));
  }

  @ParameterizedTest
  @ValueSource(ints = {200, 204, 302, 400, 503})
  void healthCheckSeverityDependsOnTheFinalOutcome(int httpStatus) throws Exception {
    capture.close();
    capture = new RequestLogCapture(Level.DEBUG);
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, (req, res) -> {
      HttpRequestLogContext.from(request).setMethod("healthCheck");
      response.setStatus(httpStatus);
    });
    filter.logCompletedRequest(request);

    JsonNode logEntry = capture.singleSummary();
    assertEquals(httpStatus < 400 ? "DEBUG" : "WARN", logEntry.path("log.level").textValue());
    assertEquals(httpStatus < 400 ? "OK" : "KO", logEntry.path("status").textValue());
    assertEquals(httpStatus, logEntry.path("http_code").intValue());
  }

  @ParameterizedTest
  @ValueSource(strings = {"healthCheck", "liveness", "readiness"})
  void failedHealthCheckIsVisibleEvenIfTheResponseStatusIs200(String method) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, (req, res) -> {
      HttpRequestLogContext.from(request).setMethod(method);
      HttpRequestLogContext.from(request).markFailure();
    });
    filter.logCompletedRequest(request);

    JsonNode logEntry = capture.singleSummary();
    assertEquals("WARN", logEntry.path("log.level").textValue());
    assertEquals("KO", logEntry.path("status").textValue());
  }

  @ParameterizedTest
  @ValueSource(strings = {"healthCheck", "liveness", "readiness"})
  void indeterminateHealthChecksRemainVisibleWithoutInventingAnOutcome(String method)
      throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, (req, res) -> {
      HttpRequestLogContext.from(request).setMethod(method);
      response.setStatus(100);
    });
    filter.logCompletedRequest(request);

    JsonNode logEntry = capture.singleSummary();
    assertEquals("WARN", logEntry.path("log.level").textValue());
    assertEquals(method, logEntry.path("method").textValue());
    assertFalse(logEntry.has("status"));
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"ACA", "aca", "GPD", "CUSTOM", "", " ACA "})
  void alwaysProjectsServiceTypeEvenWithoutAResolvedHandlerOrOtherArguments(String serviceType)
      throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    if (serviceType != null) {
      request.addHeader(CommonUtil.SERVICE_TYPE_HEADER, serviceType);
    }
    filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {});
    filter.logCompletedRequest(request);

    JsonNode logEntry = capture.singleSummary();
    assertEquals(serviceType == null ? "GPD" : serviceType,
        arguments(logEntry).path("serviceType").textValue());
    assertEquals(1, arguments(logEntry).size());
    assertNull(MDC.get("args"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "unsafe value", "unsafe\nvalue",
      "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
          + "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"})
  void replacesInvalidIdentifiersWithoutEchoingThem(String incoming) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Request-Id", incoming);
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, (req, res) -> {});
    filter.logCompletedRequest(request);

    String generated = response.getHeader("X-Request-Id");
    assertDoesNotThrow(() -> UUID.fromString(generated));
    assertEquals(generated, capture.singleSummary().path("correlation_id").asText());
    assertTrue(MDC.getCopyOfContextMap() == null || MDC.getCopyOfContextMap().isEmpty());
  }

  @Test
  void generatesCorrelationWhenMissingAndCompletesOnlyOnce() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, (req, res) -> {});
    filter.logCompletedRequest(request);
    filter.logCompletedRequest(request);

    assertDoesNotThrow(() -> UUID.fromString(response.getHeader("X-Request-Id")));
    capture.singleSummary();
  }

  @Test
  void responseTimeIsInMillisecondsAndIncludesTimeUntilActualCompletion() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, (req, res) -> {});
    HttpRequestLogContext context = HttpRequestLogContext.from(request);
    long durationBeforeCompletion = context.responseTimeMillis();

    filter.logCompletedRequest(request);

    long durationAfterCompletion = context.responseTimeMillis();
    long loggedDuration = capture.singleSummary().path("response_time").longValue();
    assertTrue(loggedDuration >= durationBeforeCompletion);
    assertTrue(loggedDuration <= durationAfterCompletion);
  }

  @Test
  void responseTimeMillisConvertsMonotonicElapsedTimeToWholeMilliseconds() {
    HttpRequestLogContext context =
        new HttpRequestLogContext("timed-request", new MockHttpServletResponse());
    long startedNanos = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(2345);
    ReflectionTestUtils.setField(context, "startedNanos", startedNanos);
    long minimumMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);

    long actualMillis = context.responseTimeMillis();

    long maximumMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    assertTrue(actualMillis >= 2345);
    assertTrue(actualMillis >= minimumMillis);
    assertTrue(actualMillis <= maximumMillis);
  }

  @ParameterizedTest
  @MethodSource("failures")
  void preservesThrownFailureAndWaitsForContainerStatus(Throwable failure) throws Exception {
    MDC.put("outer", "preserved");
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    Throwable thrown = assertThrows(failure.getClass(), () ->
        filter.doFilter(request, response, (req, res) -> {
          if (failure instanceof IOException exception) {
            throw exception;
          } else if (failure instanceof ServletException exception) {
            throw exception;
          } else if (failure instanceof RuntimeException exception) {
            throw exception;
          } else {
            throw (Error) failure;
          }
        }));

    assertSame(failure, thrown);
    assertEquals(Map.of("outer", "preserved"), MDC.getCopyOfContextMap());
    assertTrue(capture.summaries().isEmpty());
    response.setStatus(500);
    filter.logCompletedRequest(request);
    assertEquals(500, capture.singleSummary().path("http_code").intValue());
    assertEquals("KO", capture.singleSummary().path("status").asText());
    assertEquals(Map.of("outer", "preserved"), MDC.getCopyOfContextMap());
  }

  private static Stream<Throwable> failures() {
    return Stream.of(new IOException("io"), new ServletException("servlet"),
        new IllegalStateException("runtime"), new AssertionError("error"));
  }

  @Test
  void errorDispatchKeepsOriginalIdentityAndUsesTheFinalStatus() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Request-Id", "original-id");
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, (req, res) -> {
      HttpRequestLogContext.from(request).setMethod("getReceiptByIUV");
      response.sendError(404);
    });
    assertTrue(capture.summaries().isEmpty());

    request.setDispatcherType(DispatcherType.ERROR);
    response.setCommitted(false);
    filter.doFilter(request, response, (req, res) -> {
      assertEquals("original-id", MDC.get("correlation_id"));
      assertEquals("getReceiptByIUV", MDC.get("method"));
      response.setStatus(422);
    });
    filter.logCompletedRequest(request);

    JsonNode logEntry = capture.singleSummary();
    assertEquals("original-id", logEntry.path("correlation_id").asText());
    assertEquals("getReceiptByIUV", logEntry.path("method").asText());
    assertEquals(422, logEntry.path("http_code").intValue());
    assertEquals("KO", logEntry.path("status").asText());
  }

  @Test
  void soapApplicationRejectionDoesNotBecomeSuccessWithHttp200() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, (req, res) ->
        HttpRequestLogContext.from(request).markFailure());
    filter.logCompletedRequest(request);

    JsonNode logEntry = capture.singleSummary();
    assertEquals(200, logEntry.path("http_code").intValue());
    assertEquals("KO", logEntry.path("status").asText());
    assertFalse(logEntry.has("ctx_soap_outcome"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"PAA_SYSTEM_ERROR", "PAA_SEMANTICA", "PAA_PAGAMENTO_SCONOSCIUTO"})
  void soapSystemErrorCompletionUsesErrorEvenWithHttp200(String faultCode) throws Exception {
    Map<String, String> outer = Map.of("correlation_id", "outer",
        "ctx_soap_fault_code", "PAA_SYSTEM_ERROR");
    MDC.setContextMap(outer);
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, (req, res) -> {
      HttpRequestLogContext context = HttpRequestLogContext.from(request);
      context.setMethod("paGetPayment");
      context.recordSoapResponseLog("KO", faultCode);
    });

    filter.logCompletedRequest(request);
    filter.logCompletedRequest(request);

    JsonNode logEntry = capture.singleSummary();
    assertEquals("PAA_SYSTEM_ERROR".equals(faultCode) ? "ERROR" : "INFO",
        logEntry.path("log.level").asText());
    assertEquals("paGetPayment", logEntry.path("method").asText());
    assertEquals(200, logEntry.path("http_code").intValue());
    assertEquals("KO", logEntry.path("status").asText());
    assertEquals("KO", logEntry.path("ctx_soap_outcome").asText());
    assertFalse(logEntry.has("ctx_soap_fault_code"));
    assertEquals(faultCode, logEntry.path("faultCode").textValue());
    assertFalse(logEntry.has("faultDetail"));
    assertFalse(logEntry.has("error.stack_trace"));
    assertEquals(outer, MDC.getCopyOfContextMap());
  }

  @ParameterizedTest
  @ValueSource(ints = {200, 204, 302, 307})
  void successfulAndRedirectResponsesUseOkStatus(int httpStatus) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, (req, res) -> response.setStatus(httpStatus));
    filter.logCompletedRequest(request);

    JsonNode logEntry = capture.singleSummary();
    assertEquals("OK", logEntry.path("status").asText());
    assertEquals(httpStatus, logEntry.path("http_code").intValue());
    assertFalse(logEntry.has("event_action"));
    assertFalse(logEntry.has("event_outcome"));
  }

  @Test
  void indeterminateResultDoesNotInventAnOkKoOrThirdStatus() throws Exception {
    MDC.put("status", "OK");
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, (req, res) -> response.setStatus(103));
    filter.logCompletedRequest(request);

    JsonNode logEntry = capture.singleSummary();
    assertEquals(103, logEntry.path("http_code").intValue());
    assertFalse(logEntry.has("status"));
    assertFalse(logEntry.has("event_outcome"));
    assertEquals("OK", MDC.get("status"));
  }

  @Test
  void requestContextOwnsADefensiveSnapshotOfProjectionArguments() {
    HttpRequestLogContext context =
        new HttpRequestLogContext("snapshot-request", new MockHttpServletResponse());
    ObjectNode source = JsonNodeFactory.instance.objectNode();
    source.putObject("nested").put("value", "original");
    RequestLogProjection.Projection projection =
        new RequestLogProjection.Projection(null, null, null, source);
    context.recordRequestProjectionLog(projection);

    ((ObjectNode) source.path("nested")).put("value", "source-mutation");
    ((ObjectNode) projection.args().path("nested")).put("value", "accessor-mutation");
    ObjectNode fields = JsonNodeFactory.instance.objectNode();
    context.addRequestLogFields(fields);

    assertEquals("original", fields.path("args").path("nested").path("value").textValue());
    assertNull(MDC.get("args"));
  }

  @Test
  void requestContextDoesNotExposeStoredArgumentsThroughSummaryFields() {
    HttpRequestLogContext context =
        new HttpRequestLogContext("snapshot-request", new MockHttpServletResponse());
    ObjectNode source = JsonNodeFactory.instance.objectNode();
    source.putObject("nested").put("value", "original");
    context.recordRequestProjectionLog(
        new RequestLogProjection.Projection(null, null, null, source));
    ObjectNode firstFields = JsonNodeFactory.instance.objectNode();
    context.addRequestLogFields(firstFields);

    ((ObjectNode) firstFields.path("args").path("nested")).put("value", "summary-mutation");
    ObjectNode secondFields = JsonNodeFactory.instance.objectNode();
    context.addRequestLogFields(secondFields);

    assertEquals(source, secondFields.path("args"));
    assertNotSame(firstFields.path("args"), secondFields.path("args"));
    assertNotSame(firstFields.path("args").path("nested"), secondFields.path("args").path("nested"));
  }

  @Test
  void sequentialRequestsCannotInheritEachOthersMetadata() throws Exception {
    MockHttpServletRequest first = new MockHttpServletRequest();
    first.addHeader(CommonUtil.SERVICE_TYPE_HEADER, "ACA");
    MockHttpServletResponse firstResponse = new MockHttpServletResponse();
    filter.doFilter(first, firstResponse, (req, res) -> {
      HttpRequestLogContext.from(first).setMethod("firstMethod");
      HttpRequestLogContext.from(first).recordRequestProjectionLog(
          new RequestLogProjection.Projection("creditor", "000123", null,
              JsonNodeFactory.instance.objectNode().put("idStation", "station")));
      assertEquals("creditor", MDC.get("ctx_creditor_id"));
      assertEquals("000123", MDC.get("ctx_notice_number"));
      assertNull(MDC.get("ctx_iuv"));
      assertNull(MDC.get("args"));
      HttpRequestLogContext.from(first).recordResponseIuvLog("actual-iuv");
      HttpRequestLogContext.from(first).markFailure();
      firstResponse.setStatus(400);
      HttpRequestLogContext.from(first).recordRestFaultLog("Rejected", "Public rejection");
      MDC.put("args", "must-not-leak");
    });
    filter.logCompletedRequest(first);
    JsonNode firstRecord = capture.singleSummary();
    assertEquals("creditor", firstRecord.path("ctx_creditor_id").asText());
    assertEquals("000123", firstRecord.path("ctx_notice_number").asText());
    assertEquals("actual-iuv", firstRecord.path("ctx_iuv").asText());
    assertEquals("station", arguments(firstRecord).path("idStation").asText());
    assertEquals("ACA", arguments(firstRecord).path("serviceType").textValue());
    assertEquals("Rejected", firstRecord.path("faultCode").textValue());
    assertEquals("Public rejection", firstRecord.path("faultDetail").textValue());
    assertFalse(firstRecord.has("ctx_soap_fault_code"));
    MockHttpServletRequest second = new MockHttpServletRequest();
    MockHttpServletResponse secondResponse = new MockHttpServletResponse();
    filter.doFilter(second, secondResponse, (req, res) -> {
      assertNull(MDC.get("args"));
      assertNull(MDC.get("method"));
      assertNull(MDC.get("status"));
      assertNull(MDC.get("ctx_creditor_id"));
      assertNull(MDC.get("ctx_notice_number"));
      assertNull(MDC.get("ctx_iuv"));
    });
    filter.logCompletedRequest(second);

    assertEquals(2, capture.summaries().size());
    JsonNode logEntry = capture.encodedEvent(capture.summaries().get(1));
    assertNotEquals(firstResponse.getHeader("X-Request-Id"), secondResponse.getHeader("X-Request-Id"));
    assertFalse(logEntry.has("method"));
    assertEquals("GPD", arguments(logEntry).path("serviceType").textValue());
    assertEquals(1, arguments(logEntry).size());
    assertFalse(logEntry.has("ctx_creditor_id"));
    assertFalse(logEntry.has("ctx_notice_number"));
    assertFalse(logEntry.has("ctx_iuv"));
    assertFalse(logEntry.has("faultCode"));
    assertFalse(logEntry.has("faultDetail"));
    assertEquals("OK", logEntry.path("status").asText());
  }

  @Test
  void responseFaultFieldsStayOutOfRequestFieldsAndMdc() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, (req, res) -> {
      HttpRequestLogContext context = HttpRequestLogContext.from(request);
      context.recordSoapResponseLog("KO", "PAA_SEMANTICA", "Application rejection");
      ObjectNode requestFields = JsonNodeFactory.instance.objectNode();
      context.addRequestLogFields(requestFields);
      assertFalse(requestFields.has("faultCode"));
      assertFalse(requestFields.has("faultDetail"));
      assertNull(MDC.get("faultCode"));
      assertNull(MDC.get("faultDetail"));
    });
    filter.logCompletedRequest(request);

    JsonNode logEntry = capture.singleSummary();
    assertEquals("PAA_SEMANTICA", logEntry.path("faultCode").textValue());
    assertFalse(logEntry.has("ctx_soap_fault_code"));
    assertEquals("Application rejection", logEntry.path("faultDetail").textValue());
  }
}
