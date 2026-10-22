package it.gov.pagopa.payments.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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
    JsonNode record = capture.singleSummary();
    assertEquals("request-123:abc", response.getHeader("X-Request-Id"));
    assertEquals("request-123:abc", record.path("correlation_id").asText());
    assertEquals("getReceiptByIUV", record.path("method").asText());
    assertEquals("OK", record.path("status").asText());
    assertFalse(record.has("event_action"));
    assertFalse(record.has("event_outcome"));
    assertTrue(record.path("event.duration").isIntegralNumber());
    assertTrue(record.path("event.duration").longValue() >= 0);
    assertTrue(record.path("http.response.status_code").isIntegralNumber());
    assertEquals(201, record.path("http.response.status_code").intValue());
    assertEquals("GPD", record.path("args").path("serviceType").textValue());
    assertEquals(1, record.path("args").size());
    assertFalse(record.has("requestId"));
    assertFalse(record.has("startTime"));
    assertFalse(record.has("operationId"));
    assertFalse(record.has("httpCode"));
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

    JsonNode record = capture.singleSummary();
    assertEquals(serviceType == null ? "GPD" : serviceType,
        record.path("args").path("serviceType").textValue());
    assertEquals(1, record.path("args").size());
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
  void durationIsInNanosecondsAndIncludesTimeUntilActualCompletion() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, (req, res) -> {});
    long start = System.nanoTime();
    TimeUnit.MILLISECONDS.sleep(5);
    long completionDelay = System.nanoTime() - start;

    filter.logCompletedRequest(request);

    assertTrue(capture.singleSummary().path("event.duration").longValue() >= completionDelay);
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
    assertEquals(500, capture.singleSummary().path("http.response.status_code").intValue());
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

    JsonNode record = capture.singleSummary();
    assertEquals("original-id", record.path("correlation_id").asText());
    assertEquals("getReceiptByIUV", record.path("method").asText());
    assertEquals(422, record.path("http.response.status_code").intValue());
    assertEquals("KO", record.path("status").asText());
  }

  @Test
  void soapApplicationRejectionDoesNotBecomeSuccessWithHttp200() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, (req, res) ->
        HttpRequestLogContext.from(request).markFailure());
    filter.logCompletedRequest(request);

    JsonNode record = capture.singleSummary();
    assertEquals(200, record.path("http.response.status_code").intValue());
    assertEquals("KO", record.path("status").asText());
    assertFalse(record.has("ctx_soap_outcome"));
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

    JsonNode record = capture.singleSummary();
    assertEquals("PAA_SYSTEM_ERROR".equals(faultCode) ? "ERROR" : "INFO",
        record.path("log.level").asText());
    assertEquals("paGetPayment", record.path("method").asText());
    assertEquals(200, record.path("http.response.status_code").intValue());
    assertEquals("KO", record.path("status").asText());
    assertEquals("KO", record.path("ctx_soap_outcome").asText());
    assertEquals(faultCode, record.path("ctx_soap_fault_code").asText());
    assertFalse(record.has("error.stack_trace"));
    assertEquals(outer, MDC.getCopyOfContextMap());
  }

  @ParameterizedTest
  @ValueSource(ints = {200, 204, 302, 307})
  void successfulAndRedirectResponsesUseOkStatus(int httpStatus) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, (req, res) -> response.setStatus(httpStatus));
    filter.logCompletedRequest(request);

    JsonNode record = capture.singleSummary();
    assertEquals("OK", record.path("status").asText());
    assertEquals(httpStatus, record.path("http.response.status_code").intValue());
    assertFalse(record.has("event_action"));
    assertFalse(record.has("event_outcome"));
  }

  @Test
  void indeterminateResultDoesNotInventAnOkKoOrThirdStatus() throws Exception {
    MDC.put("status", "OK");
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, (req, res) -> response.setStatus(103));
    filter.logCompletedRequest(request);

    JsonNode record = capture.singleSummary();
    assertEquals(103, record.path("http.response.status_code").intValue());
    assertFalse(record.has("status"));
    assertFalse(record.has("event_outcome"));
    assertEquals("OK", MDC.get("status"));
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
      MDC.put("args", "must-not-leak");
    });
    filter.logCompletedRequest(first);
    JsonNode firstRecord = capture.singleSummary();
    assertEquals("creditor", firstRecord.path("ctx_creditor_id").asText());
    assertEquals("000123", firstRecord.path("ctx_notice_number").asText());
    assertEquals("actual-iuv", firstRecord.path("ctx_iuv").asText());
    assertEquals("station", firstRecord.path("args").path("idStation").asText());
    assertEquals("ACA", firstRecord.path("args").path("serviceType").textValue());
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
    JsonNode record = capture.record(capture.summaries().get(1));
    assertNotEquals(firstResponse.getHeader("X-Request-Id"), secondResponse.getHeader("X-Request-Id"));
    assertFalse(record.has("method"));
    assertEquals("GPD", record.path("args").path("serviceType").textValue());
    assertEquals(1, record.path("args").size());
    assertFalse(record.has("ctx_creditor_id"));
    assertFalse(record.has("ctx_notice_number"));
    assertFalse(record.has("ctx_iuv"));
    assertEquals("OK", record.path("status").asText());
  }
}
