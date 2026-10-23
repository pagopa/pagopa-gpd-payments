package it.gov.pagopa.payments.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import com.fasterxml.jackson.databind.JsonNode;
import it.gov.pagopa.payments.controller.BaseController;
import it.gov.pagopa.payments.controller.DeadLetterController;
import it.gov.pagopa.payments.controller.receipt.impl.PaymentsController;
import it.gov.pagopa.payments.entity.ReceiptEntity;
import it.gov.pagopa.payments.exception.AppError;
import it.gov.pagopa.payments.exception.AppException;
import it.gov.pagopa.payments.exception.ErrorHandler;
import it.gov.pagopa.payments.service.DeadLetterService;
import it.gov.pagopa.payments.service.PaymentsService;
import it.gov.pagopa.payments.utils.CommonUtil;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RequestLoggingMvcTest {

  private final RequestFilter filter = new RequestFilter();
  private final PaymentsService payments = mock(PaymentsService.class);
  private RequestLogCapture capture;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    capture = new RequestLogCapture();
    PaymentsController controller = new PaymentsController();
    ReflectionTestUtils.setField(controller, "paymentsService", payments);
    DeadLetterService deadLetters = mock(DeadLetterService.class);
    when(deadLetters.getDeadLetters(anyInt())).thenReturn(List.of());
    mvc = MockMvcBuilders
        .standaloneSetup(controller, new DeadLetterController(deadLetters), new BaseController())
        .setControllerAdvice(new ErrorHandler())
        .addInterceptors(new RequestLoggingInterceptor())
        .addFilters(filter)
        .build();
  }

  @AfterEach
  void tearDown() {
    capture.close();
  }

  @Test
  void repeatedSuccessfulHealthChecksProduceNoInfoSummaries() throws Exception {
    for (int index = 0; index < 3; index++) {
      MvcResult result = mvc.perform(get("/info")).andExpect(status().isOk()).andReturn();
      filter.logCompletedRequest(result.getRequest());
    }

    assertTrue(capture.summaries().isEmpty());
  }

  @Test
  void healthChecksRemainAvailableAtDebugWithTheSameStructuredFields() throws Exception {
    capture.close();
    capture = new RequestLogCapture(Level.DEBUG);
    MvcResult result = mvc.perform(get("/info")
        .header("X-Request-Id", "health-request")
        .header(CommonUtil.SERVICE_TYPE_HEADER, "ACA"))
        .andExpect(status().isOk()).andReturn();
    filter.logCompletedRequest(result.getRequest());
    filter.logCompletedRequest(result.getRequest());

    JsonNode logEntry = capture.singleSummary();
    assertEquals("DEBUG", logEntry.path("log.level").textValue());
    assertEquals("healthCheck", logEntry.path("method").textValue());
    assertEquals("health-request", logEntry.path("correlation_id").textValue());
    assertEquals("ACA", logEntry.path("args").path("serviceType").textValue());
    assertEquals(200, logEntry.path("http.response.status_code").intValue());
    assertEquals("OK", logEntry.path("status").textValue());
  }

  @Test
  void logsControllerAnnotatedWithControllerWithoutDumpingReceipt() throws Exception {
    ReceiptEntity receipt = new ReceiptEntity("org", "iuv");
    receipt.setDocument("<receipt>private-body</receipt>");
    when(payments.getReceiptByOrganizationFCAndIUV(anyString(), anyString(), isNull()))
        .thenReturn(receipt);

    MvcResult result = mvc.perform(get("/payments/org/receipts/iuv")
        .header("X-Request-Id", "mvc-request")).andExpect(status().isOk())
        .andExpect(content().string("<receipt>private-body</receipt>")).andReturn();
    filter.logCompletedRequest(result.getRequest());

    JsonNode logEntry = capture.singleSummary();
    assertEquals("getReceiptByIUV", logEntry.path("method").asText());
    assertEquals("mvc-request", logEntry.path("correlation_id").asText());
    assertEquals("org", logEntry.path("ctx_creditor_id").asText());
    assertEquals("iuv", logEntry.path("ctx_iuv").asText());
    assertFalse(logEntry.has("ctx_notice_number"));
    assertEquals("OK", logEntry.path("status").asText());
    assertFalse(logEntry.toString().contains("private-body"));
    assertEquals("GPD", logEntry.path("args").path("serviceType").textValue());
    assertEquals(1, logEntry.path("args").size());
  }

  @ParameterizedTest
  @ValueSource(strings = {
    "/payments/org/receipts/iuv", "/payments/org/receipts", "/error-messages"
  })
  void everyRestOperationProjectsTheServiceTypeHeader(String path) throws Exception {
    ReceiptEntity receipt = new ReceiptEntity("org", "iuv");
    receipt.setDocument("<receipt/>");
    when(payments.getReceiptByOrganizationFCAndIUV(anyString(), anyString(), isNull()))
        .thenReturn(receipt);

    MvcResult result = mvc.perform(get(path).header(CommonUtil.SERVICE_TYPE_HEADER, "ACA"))
        .andExpect(status().isOk()).andReturn();
    filter.logCompletedRequest(result.getRequest());

    JsonNode logEntry = capture.singleSummary();
    assertEquals("ACA", logEntry.path("args").path("serviceType").textValue());
    if (path.equals("/payments/org/receipts")) {
      assertEquals(0, logEntry.path("args").path("pageNum").intValue());
      assertEquals(20, logEntry.path("args").path("pageSize").intValue());
    }
  }

  @Test
  void recordsResolvedActionEvenWhenBindingFailsBeforeTheController() throws Exception {
    MvcResult result = mvc.perform(get("/error-messages").param("maxMessages", "not-a-number"))
        .andExpect(status().isBadRequest()).andReturn();
    filter.logCompletedRequest(result.getRequest());

    JsonNode logEntry = capture.singleSummary();
    assertEquals("getDeadLetters", logEntry.path("method").asText());
    assertEquals(400, logEntry.path("http.response.status_code").intValue());
    assertEquals("KO", logEntry.path("status").asText());
    assertFalse(logEntry.toString().contains("not-a-number"));
  }

  @Test
  void handledAppExceptionProducesOneSummaryWithTheActualResponseStatus() throws Exception {
    when(payments.getReceiptByOrganizationFCAndIUV(anyString(), anyString(), isNull()))
        .thenThrow(new AppException(AppError.RECEIPT_NOT_FOUND, "org", "iuv"));
    MvcResult result = mvc.perform(get("/payments/org/receipts/iuv"))
        .andExpect(status().isNotFound()).andReturn();
    filter.logCompletedRequest(result.getRequest());

    JsonNode logEntry = capture.singleSummary();
    assertEquals("getReceiptByIUV", logEntry.path("method").asText());
    assertEquals(404, logEntry.path("http.response.status_code").intValue());
    assertEquals("KO", logEntry.path("status").asText());
  }
}
