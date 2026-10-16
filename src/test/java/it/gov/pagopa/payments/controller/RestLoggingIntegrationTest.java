package it.gov.pagopa.payments.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.gov.pagopa.payments.config.LoggingAspect;
import it.gov.pagopa.payments.config.RequestFilter;
import it.gov.pagopa.payments.config.RestLoggingConfiguration;
import it.gov.pagopa.payments.config.RestLoggingInterceptor;
import it.gov.pagopa.payments.controller.receipt.impl.PaymentsController;
import it.gov.pagopa.payments.entity.ReceiptEntity;
import it.gov.pagopa.payments.exception.AppError;
import it.gov.pagopa.payments.exception.AppException;
import it.gov.pagopa.payments.exception.ErrorHandler;
import it.gov.pagopa.payments.logging.LogEventWriter;
import it.gov.pagopa.payments.logging.OperationLogScope;
import it.gov.pagopa.payments.model.PaymentsResult;
import it.gov.pagopa.payments.service.DeadLetterService;
import it.gov.pagopa.payments.service.PaymentsService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.validation.Valid;
import javax.validation.ConstraintViolationException;
import javax.validation.constraints.NotBlank;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.boot.autoconfigure.web.ErrorProperties;
import org.springframework.boot.web.servlet.error.DefaultErrorAttributes;
import org.springframework.boot.autoconfigure.web.servlet.error.BasicErrorController;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.MethodValidationInterceptor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

class RestLoggingIntegrationTest {
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>() {
    @Override
    protected void append(ILoggingEvent event) {
      event.prepareForDeferredProcessing();
      super.append(event);
    }
  };
  private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
  private Map<String, String> previousMdc;
  private Level previousLevel;
  private MockMvc mvc;
  private PaymentsService payments;
  private DeadLetterService deadLetters;

  @BeforeEach
  void setUp() {
    previousMdc = MDC.getCopyOfContextMap();
    MDC.clear();
    previousLevel = root.getLevel();
    root.setLevel(Level.DEBUG);
    appender.start();
    root.addAppender(appender);
    payments = mock(PaymentsService.class);
    PaymentsController controller = new PaymentsController();
    ReflectionTestUtils.setField(controller, "paymentsService", payments);
    AspectJProxyFactory receiptProxy = new AspectJProxyFactory(controller);
    receiptProxy.setProxyTargetClass(true);
    receiptProxy.addAdvice(new MethodValidationInterceptor());
    LoggingAspect aspect =
        new LoggingAspect(new MockHttpServletRequest(), new MockHttpServletResponse());
    receiptProxy.addAspect(aspect);
    AspectJProxyFactory errorProxy = new AspectJProxyFactory(new ErrorHandler());
    errorProxy.setProxyTargetClass(true);
    errorProxy.addAspect(aspect);
    AspectJProxyFactory probeProxy = new AspectJProxyFactory(new ProbeController());
    probeProxy.setProxyTargetClass(true);
    probeProxy.addAspect(aspect);
    deadLetters = mock(DeadLetterService.class);
    BaseController base = new BaseController();
    ReflectionTestUtils.setField(base, "basePath", "/");
    mvc = MockMvcBuilders.standaloneSetup(
            receiptProxy.getProxy(), probeProxy.getProxy(),
            new DeadLetterController(deadLetters), base,
            new BasicErrorController(new DefaultErrorAttributes(), new ErrorProperties()))
        .setControllerAdvice((Object) errorProxy.getProxy())
        .addInterceptors(new RestLoggingInterceptor())
        .addFilters(new RequestFilter())
        .build();
    appender.list.clear();
  }

  @AfterEach
  void tearDown() {
    assertThrows(IllegalStateException.class, OperationLogScope::current, "REST scope leaked");
    root.detachAppender(appender);
    root.setLevel(previousLevel);
    appender.stop();
    if (previousMdc == null) {
      MDC.clear();
    } else {
      MDC.setContextMap(previousMdc);
    }
  }

  @Test
  void approvedArgsCoverEveryRestApiWithDefaultsAndNoSensitiveInputs() throws Exception {
    ReceiptEntity receipt = new ReceiptEntity("creditor", "000123");
    receipt.setDocument("receipt");
    when(payments.getReceiptByOrganizationFCAndIUV(anyString(), anyString(), any()))
        .thenReturn(receipt);
    when(payments.getOrganizationReceipts(anyString(), any(), any(), any(), any(),
        anyInt(), anyInt(), any(), any())).thenReturn(new PaymentsResult<>());
    mvc.perform(get("/payments/00123456789/receipts/000123")
            .param("segregationCodes", "01,02").param("unknown", "PRIVATE-QUERY")
            .header("Authorization", "PRIVATE-HEADER").param("iuv", "PRIVATE-SHADOW"))
        .andExpect(status().isOk());
    assertArgs(summary("getReceiptByIUV", 200, "OK"),
        "{\"iuv\":\"000123\",\"segregationCodes\":\"01,02\"}");
    assertClean();
    appender.list.clear();

    mvc.perform(get("/payments/00123456789/receipts")
            .param("pageNum", "2").param("pageSize", "10").param("service", "0001")
            .param("from", "2026-10-01").param("to", "2026-10-16")
            .param("segregationCodes", "01,02").param("debtor", "PRIVATE-DEBTOR")
            .param("debtorOrIuv", "PRIVATE-SEARCH").param("unknown", "PRIVATE-QUERY")
            .header("X-Api-Key", "PRIVATE-HEADER"))
        .andExpect(status().isOk());
    assertArgs(summary("getOrganizationReceipts", 200, "OK"), """
        {"pageNum":2,"pageSize":10,"service":"0001","from":"2026-10-01",
        "to":"2026-10-16","segregationCodes":"01,02"}
        """);
    assertClean();
    appender.list.clear();

    mvc.perform(get("/payments/00123456789/receipts"))
        .andExpect(status().isOk());
    assertArgs(summary("getOrganizationReceipts", 200, "OK"), "{\"pageNum\":0,\"pageSize\":20}");
    appender.list.clear();
    mvc.perform(get("/error-messages")).andExpect(status().isOk());
    assertArgs(summary("getDeadLetters", 200, "OK"), "{\"maxMessages\":50}");
    appender.list.clear();
    mvc.perform(get("/error-messages").param("maxMessages", "3")).andExpect(status().isOk());
    assertArgs(summary("getDeadLetters", 200, "OK"), "{\"maxMessages\":3}");
    appender.list.clear();
    String filename = "2026/08/31/10/message-1/MAX_RETRY_ATTEMPTS_REACHED_1000.json";
    mvc.perform(get("/error-messages/detail").param("filename", filename)
            .param("fileName", "PRIVATE-SHADOW"))
        .andExpect(status().isOk());
    assertArgs(summary("getDeadLetter", 200, "OK"), "{\"filename\":\"" + filename + "\"}");
    appender.list.clear();
    mvc.perform(get("/info").param("service", "PRIVATE-QUERY")).andExpect(status().isOk());
    assertNull(summary("healthCheck", 200, "OK").getMDCPropertyMap().get("args"));
    appender.list.clear();
    mvc.perform(get("/").param("maxMessages", "PRIVATE-QUERY")).andExpect(status().isOk());
    assertNull(summary("home", 200, "OK").getMDCPropertyMap().get("args"));
    assertClean();
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"bad", "2147483648", " "})
  void invalidNumericArgsAreOmittedWithoutChangingBindingResponse(String invalid) throws Exception {
    mvc.perform(get("/payments/00123456789/receipts")
            .param("pageNum", invalid).param("pageSize", "7").param("service", "0001")
            .param("debtor", "PRIVATE-DEBTOR").header("Authorization", "PRIVATE-HEADER"))
        .andExpect(status().isBadRequest());
    assertArgs(summary("getOrganizationReceipts", 400, "KO"),
        "{\"pageSize\":7,\"service\":\"0001\"}");
    verifyNoInteractions(payments);
    assertClean();
    appender.list.clear();
    mvc.perform(get("/error-messages").param("maxMessages", invalid))
        .andExpect(status().isBadRequest());
    assertNull(summary("getDeadLetters", 400, "KO").getMDCPropertyMap().get("args"));
    verifyNoInteractions(deadLetters);
    assertClean();
  }

  @Test
  void emptyNumericInputsUseMvcDefaultsAndBusinessRejectionsRetainApprovedArgs() throws Exception {
    when(payments.getOrganizationReceipts(anyString(), any(), any(), any(), any(),
        anyInt(), anyInt(), any(), any())).thenReturn(new PaymentsResult<>());
    mvc.perform(get("/payments/00123456789/receipts")
            .param("pageNum", "").param("pageSize", ""))
        .andExpect(status().isOk());
    assertArgs(summary("getOrganizationReceipts", 200, "OK"), "{\"pageNum\":0,\"pageSize\":20}");
    appender.list.clear();
    // The existing generic advice maps controller ResponseStatusException to HTTP 500.
    mvc.perform(get("/error-messages").param("maxMessages", "0"))
        .andExpect(status().isInternalServerError());
    assertArgs(summary("getDeadLetters", 500, "KO"), "{\"maxMessages\":0}");
    appender.list.clear();
    mvc.perform(get("/error-messages/detail").param("filename", "invalid"))
        .andExpect(status().isInternalServerError());
    assertArgs(summary("getDeadLetter", 500, "KO"), "{\"filename\":\"invalid\"}");
    appender.list.clear();
    mvc.perform(get("/error-messages/detail")).andExpect(status().isBadRequest());
    assertNull(summary("getDeadLetter", 400, "KO").getMDCPropertyMap().get("args"));
    assertClean();
  }

  @Test
  void unrelatedControllerWithSameMethodNameDoesNotAcquireAnAllowlist() throws Exception {
    mvc.perform(get("/logging-probe/dead-letters").param("maxMessages", "27")
            .param("filename", "PRIVATE-QUERY").header("X-Api-Key", "PRIVATE-HEADER"))
        .andExpect(status().isOk());
    assertNull(summary("getDeadLetters", 200, "OK").getMDCPropertyMap().get("args"));
    assertClean();
  }

  @Test
  void configurationRegistersTheRestBoundary() {
    InterceptorRegistry registry = new InterceptorRegistry();
    new RestLoggingConfiguration().addInterceptors(registry);
    @SuppressWarnings("unchecked")
    List<Object> interceptors =
        (List<Object>) ReflectionTestUtils.invokeMethod(registry, "getInterceptors");
    assertEquals(1, interceptors.size());
    assertInstanceOf(RestLoggingInterceptor.class, interceptors.get(0));
  }

  @Test
  void receiptDetailAndListHaveDistinctMethodsFreshOperationsAndNoPayloadDumps() throws Exception {
    ReceiptEntity receipt = new ReceiptEntity("creditor", "iuv");
    receipt.setDocument("<receipt>PRIVATE-RECEIPT</receipt>");
    when(payments.getReceiptByOrganizationFCAndIUV(anyString(), anyString(), isNull()))
        .thenReturn(receipt);
    when(payments.getOrganizationReceipts(anyString(), any(), any(), any(), any(),
        anyInt(), anyInt(), any(), any())).thenReturn(new PaymentsResult<>());
    mvc.perform(get("/payments/00123456789/receipts/PRIVATE-IUV")
            .header("X-Request-Id", " caller id "))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Request-Id", " caller id "))
        .andExpect(content().string("<receipt>PRIVATE-RECEIPT</receipt>"));
    ILoggingEvent detail = summary("getReceiptByIUV", 200, "OK");
    assertEquals("00123456789", detail.getMDCPropertyMap().get("creditorFiscalCode"));
    assertNull(detail.getMDCPropertyMap().get("noticeNumber"));
    assertEquals(" caller id ", detail.getMDCPropertyMap().get("requestId"));
    String firstOperation = detail.getMDCPropertyMap().get("operationId");
    assertNotNull(UUID.fromString(firstOperation));
    assertClean();

    appender.list.clear();
    mvc.perform(get("/payments/00123456789/receipts")
            .param("debtor", "PRIVATE-DEBTOR")
            .param("debtorOrIuv", "PRIVATE-FILTER")
            .header("X-Request-Id", " caller id "))
        .andExpect(status().isOk());
    ILoggingEvent list = summary("getOrganizationReceipts", 200, "OK");
    assertNotEquals(firstOperation, list.getMDCPropertyMap().get("operationId"));
    assertEquals("00123456789", list.getMDCPropertyMap().get("creditorFiscalCode"));
    assertBusinessOnly();
    assertClean();
  }

  @ParameterizedTest
  @EnumSource(value = AppError.class, names = {
      "RECEIPT_NOT_FOUND", "UNPROCESSABLE_RECEIPT", "FORBIDDEN_SEGREGATION_CODE",
      "INVALID_DATE_RANGE", "PAGE_NUMBER_GREATER_THAN_TOTAL_PAGES"})
  void expectedBusinessFailuresOnlyHaveInfoSummary(AppError error) throws Exception {
    AppException failure = new AppException(error, "PRIVATE-INPUT", "PRIVATE-IUV", "unused");
    when(payments.getReceiptByOrganizationFCAndIUV(anyString(), anyString(), any()))
        .thenThrow(failure);
    mvc.perform(get("/payments/00123456789/receipts/PRIVATE-IUV"))
        .andExpect(status().is(error.getHttpStatus().value()))
        .andExpect(jsonPath("$.title").value(failure.getTitle()))
        .andExpect(jsonPath("$.detail").value(failure.getMessage()));
    ILoggingEvent event = summary("getReceiptByIUV", error.getHttpStatus().value(), "KO");
    assertEquals(failure.getTitle(), event.getMDCPropertyMap().get("faultCode"));
    assertBusinessOnly();
    assertClean();
  }

  @Test
  void typeBindingFailsBeforeInvocationButHasReceiptMethodAndCorrelation() throws Exception {
    mvc.perform(get("/payments/00123456789/receipts")
            .param("pageNum", "PRIVATE-INVALID-NUMBER").header("X-Request-Id", "binding"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value(ErrorHandler.BAD_REQUEST));
    verifyNoInteractions(payments);
    ILoggingEvent event = summary("getOrganizationReceipts", 400, "KO");
    assertEquals("binding", event.getMDCPropertyMap().get("requestId"));
    assertEquals("00123456789", event.getMDCPropertyMap().get("creditorFiscalCode"));
    assertBusinessOnly();
    assertClean();
  }

  @Test
  void methodConstraintValidationOnlyHasInfoSummary() throws Exception {
    mvc.perform(get("/payments/00123456789/receipts").param("pageSize", "101"))
        .andExpect(status().isBadRequest()).andExpect(content().string(""));
    verifyNoInteractions(payments);
    summary("getOrganizationReceipts", 400, "KO");
    assertBusinessOnly();
    assertClean();
  }

  @Test
  void missingParameterMalformedJsonAndBeanValidationOnlyHaveInfoSummaries() throws Exception {
    mvc.perform(get("/logging-probe/required"))
        .andExpect(status().isBadRequest());
    summary("required", 400, "KO");
    assertBusinessOnly();
    assertClean();
    appender.list.clear();
    mvc.perform(post("/logging-probe/body").contentType(MediaType.APPLICATION_JSON)
            .content("{\"value\":\"PRIVATE-PAYLOAD\""))
        .andExpect(status().isBadRequest());
    summary("body", 400, "KO");
    assertBusinessOnly();
    assertClean();
    appender.list.clear();
    mvc.perform(post("/logging-probe/body").contentType(MediaType.APPLICATION_JSON)
            .content("{\"value\":\"\"}"))
        .andExpect(status().isBadRequest());
    summary("body", 400, "KO");
    assertBusinessOnly();
    assertClean();
  }

  @Test
  void technicalFailureUsesControlledWriterAndOneFinalSummary() throws Exception {
    RuntimeException failure = new IllegalStateException("technical failure");
    when(payments.getReceiptByOrganizationFCAndIUV(anyString(), anyString(), any()))
        .thenThrow(failure);
    mvc.perform(get("/payments/00123456789/receipts/PRIVATE-IUV")
            .header("X-Request-Id", "technical"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.detail").value("An unexpected error occurred."));
    ILoggingEvent completed = summary("getReceiptByIUV", 500, "KO");
    List<ILoggingEvent> diagnostics = events("OPERATION_TECHNICAL_FAILED");
    assertEquals(1, diagnostics.size());
    ILoggingEvent diagnostic = diagnostics.get(0);
    assertEquals(Level.ERROR, diagnostic.getLevel());
    assertEquals(failure.getClass().getName(), diagnostic.getMDCPropertyMap().get("error.type"));
    assertEquals("technical failure", diagnostic.getMDCPropertyMap().get("error.message"));
    assertNotNull(diagnostic.getMDCPropertyMap().get("error.stack_trace"));
    assertEquals(completed.getMDCPropertyMap().get("operationId"),
        diagnostic.getMDCPropertyMap().get("operationId"));
    assertNull(diagnostic.getThrowableProxy());
    assertClean();
  }

  @Test
  void technicalAppExceptionPreservesResponse() throws Exception {
    AppException failure = new AppException(AppError.RETRIEVAL_RECEIPT_FAILED, "org", "iuv");
    when(payments.getReceiptByOrganizationFCAndIUV(anyString(), anyString(), any()))
        .thenThrow(failure);
    mvc.perform(get("/payments/00123456789/receipts/PRIVATE-IUV"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.title").value(failure.getTitle()))
        .andExpect(jsonPath("$.detail").value(failure.getMessage()));
    summary("getReceiptByIUV", 500, "KO");
    assertEquals(1, events("OPERATION_TECHNICAL_FAILED").size());
    assertClean();
  }

  @Test
  void previouslyDiagnosedCauseIsNotDiagnosedAgainByErrorHandler() throws Exception {
    mvc.perform(get("/logging-probe/diagnosed"))
        .andExpect(status().isInternalServerError());
    summary("diagnosed", 500, "KO");
    assertEquals(1, events("OPERATION_TECHNICAL_FAILED").size());
    assertClean();
  }

  @Test
  void summaryUsesActualResponseStatusNotDefaultBeforeResponseEntityApplied() throws Exception {
    mvc.perform(get("/logging-probe/created")).andExpect(status().isCreated());
    summary("created", 201, "OK");
    assertBusinessOnly();
    assertClean();
    appender.list.clear();
    mvc.perform(get("/logging-probe/rejected")).andExpect(status().isConflict());
    summary("rejected", 409, "KO");
    assertBusinessOnly();
    assertClean();
  }

  @Test
  void filterPreservesHeaderContractAndGeneratesIdsForAbsentAndEmptyValues() throws Exception {
    for (String supplied : List.of("", "   ", "custom-id")) {
      appender.list.clear();
      String returned = mvc.perform(get("/logging-probe/created").header("X-Request-Id", supplied))
          .andExpect(status().isCreated()).andReturn().getResponse().getHeader("X-Request-Id");
      if (supplied.isEmpty()) {
        assertNotNull(UUID.fromString(returned));
      } else {
        assertEquals(supplied, returned);
      }
      assertEquals(returned, summary("created", 201, "OK").getMDCPropertyMap().get("requestId"));
      assertClean();
    }
    appender.list.clear();
    String generated = mvc.perform(get("/logging-probe/created")).andReturn()
        .getResponse().getHeader("X-Request-Id");
    assertNotNull(UUID.fromString(generated));
    assertEquals(generated, summary("created", 201, "OK").getMDCPropertyMap().get("requestId"));
    assertClean();
  }

  @Test
  void nonApplicationHandlerAndMissingHandlerHaveNoRestOperation() throws Exception {
    mvc.perform(get("/error")).andExpect(status().isInternalServerError());
    assertTrue(events("REST_OPERATION_COMPLETED").isEmpty());
    assertTrue(events("OPERATION_TECHNICAL_FAILED").isEmpty());
    assertClean();
    mvc.perform(get("/no-such-handler")).andExpect(status().isNotFound());
    assertTrue(events("REST_OPERATION_COMPLETED").isEmpty());
    assertClean();
  }

  @Test
  void actuatorHandlerAndNonMethodHandlerDoNotAcquireAnOperation() throws Exception {
    RestLoggingInterceptor interceptor = new RestLoggingInterceptor();
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    var actuatorHandler = new org.springframework.web.method.HandlerMethod(
        mock(HealthEndpoint.class), HealthEndpoint.class.getMethod("health"));
    MDC.put("requestId", "actuator");
    Map<String, String> before = MDC.getCopyOfContextMap();
    assertTrue(interceptor.preHandle(request, response, actuatorHandler));
    assertThrows(IllegalStateException.class, OperationLogScope::current);
    interceptor.afterCompletion(request, response, actuatorHandler, null);
    assertTrue(interceptor.preHandle(request, response, new Object()));
    assertEquals(before, MDC.getCopyOfContextMap());
    assertTrue(events("REST_OPERATION_COMPLETED").isEmpty());
    MDC.clear();
  }

  @Test
  void deadLetterReadsUseActualControllerMethodWithoutPaymentIdentifiers() throws Exception {
    when(deadLetters.getDeadLetters(50)).thenReturn(List.of());
    mvc.perform(get("/error-messages")).andExpect(status().isOk())
        .andExpect(content().json("[]"));
    ILoggingEvent event = summary("getDeadLetters", 200, "OK");
    assertNull(event.getMDCPropertyMap().get("creditorFiscalCode"));
    assertNull(event.getMDCPropertyMap().get("noticeNumber"));
    assertBusinessOnly();
    assertClean();
    appender.list.clear();
    mvc.perform(get("/error-messages/detail")).andExpect(status().isBadRequest());
    summary("getDeadLetter", 400, "KO");
    assertBusinessOnly();
    assertClean();
  }

  @Test
  void directErrorHandlerTechnicalCallsWithoutScopePreserveResponsesAndContext() {
    ErrorHandler handler = new ErrorHandler();
    MDC.setContextMap(Map.of("requestId", "unit-test", "unrelated", "preserve"));
    Map<String, String> before = MDC.getCopyOfContextMap();
    assertEquals(500, handler.handleGenericException(new Exception("generic"), null)
        .getStatusCodeValue());
    assertEquals(500, handler.handleDataIntegrityViolationException(
        new DataIntegrityViolationException("persistence"), null).getStatusCodeValue());
    assertEquals(503, handler.handleAppException(new AppException(AppError.DB_ERROR), null)
        .getStatusCodeValue());
    assertEquals(3, events("OPERATION_TECHNICAL_FAILED").size());
    assertTrue(events("REST_OPERATION_COMPLETED").isEmpty());
    for (ILoggingEvent diagnostic : events("OPERATION_TECHNICAL_FAILED")) {
      assertEquals(Level.ERROR, diagnostic.getLevel());
      assertNull(diagnostic.getThrowableProxy());
      assertNull(diagnostic.getArgumentArray());
      assertNotNull(diagnostic.getMDCPropertyMap().get("error.stack_trace"));
    }
    assertEquals(before, MDC.getCopyOfContextMap());
    assertThrows(IllegalStateException.class, OperationLogScope::current);
    MDC.clear();
  }

  @Test
  void databaseConstraintBusinessFailureKeepsConflictResponseWithoutDiagnosis() throws Exception {
    when(payments.getReceiptByOrganizationFCAndIUV(anyString(), anyString(), any()))
        .thenThrow(new DataIntegrityViolationException("PRIVATE-DB",
            new ConstraintViolationException("PRIVATE-CONSTRAINT", Set.of())));
    mvc.perform(get("/payments/00123456789/receipts/PRIVATE-IUV"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").value("There is a relation with other resource."));
    summary("getReceiptByIUV", 409, "KO");
    assertBusinessOnly();
    assertClean();
  }

  @Test
  void interceptorRestoresExactPriorContextAndClosesOnlyItsOwnScope() throws Exception {
    RestLoggingInterceptor interceptor = new RestLoggingInterceptor();
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    var handler = new org.springframework.web.method.HandlerMethod(
        new ProbeController(), ProbeController.class.getMethod("created"));
    try (OperationLogScope outer = OperationLogScope.open("outer", "outerMethod")) {
      MDC.put("unrelated", "outer-value");
      Map<String, String> before = MDC.getCopyOfContextMap();
      assertTrue(interceptor.preHandle(request, response, handler));
      OperationLogScope rest = OperationLogScope.current();
      assertNotSame(outer, rest);
      assertNull(MDC.get("unrelated"));
      assertTrue(interceptor.preHandle(request, response, handler));
      assertSame(rest, OperationLogScope.current());
      response.setStatus(202);
      interceptor.afterCompletion(request, response, handler, null);
      assertSame(outer, OperationLogScope.current());
      assertEquals(before, MDC.getCopyOfContextMap());
      interceptor.afterCompletion(request, response, handler, null);
      assertEquals(before, MDC.getCopyOfContextMap());
      summary("created", 202, "OK");
    }
    assertClean();
  }

  @Test
  void unhandledFailureStillEmitsDiagnosticSummaryAndClosesScope() throws Exception {
    RestLoggingInterceptor interceptor = new RestLoggingInterceptor();
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    var handler = new org.springframework.web.method.HandlerMethod(
        new ProbeController(), ProbeController.class.getMethod("created"));
    interceptor.preHandle(request, response, handler);
    response.setStatus(503);
    interceptor.afterCompletion(request, response, handler, new IllegalStateException("unhandled"));
    summary("created", 503, "KO");
    assertEquals(1, events("OPERATION_TECHNICAL_FAILED").size());
    assertClean();
  }

  private List<ILoggingEvent> events(String code) {
    return appender.list.stream()
        .filter(event -> code.equals(event.getMDCPropertyMap().get("event.code"))).toList();
  }

  private ILoggingEvent summary(String method, int status, String outcome) {
    List<ILoggingEvent> summaries = events("REST_OPERATION_COMPLETED");
    assertEquals(1, summaries.size());
    ILoggingEvent event = summaries.get(0);
    assertEquals(Level.INFO, event.getLevel());
    assertEquals("REST operation completed", event.getFormattedMessage());
    assertEquals(method, event.getMDCPropertyMap().get("method"));
    assertEquals(Integer.toString(status), event.getMDCPropertyMap().get("httpCode"));
    assertEquals(outcome, event.getMDCPropertyMap().get("status"));
    assertTrue(Long.parseLong(event.getMDCPropertyMap().get("responseTime")) >= 0);
    assertNotNull(event.getMDCPropertyMap().get("operationId"));
    assertNull(event.getThrowableProxy());
    assertTrue(appender.list.stream().filter(logged ->
        !"REST_OPERATION_COMPLETED".equals(logged.getMDCPropertyMap().get("event.code")))
        .allMatch(logged -> logged.getMDCPropertyMap().get("args") == null));
    assertNull(event.getMDCPropertyMap().get("error.stack_trace"));
    assertTrue(appender.list.stream().noneMatch(logged ->
        logged.getFormattedMessage().equals("Successful API operation")
            || logged.getFormattedMessage().equals("Failed API operation")));
    return event;
  }

  private void assertBusinessOnly() {
    assertTrue(events("OPERATION_TECHNICAL_FAILED").isEmpty());
    for (ILoggingEvent event : appender.list) {
      if (event.getLoggerName().startsWith("it.gov.pagopa.payments.")) {
        assertFalse(event.getLevel().isGreaterOrEqual(Level.WARN));
        assertNull(event.getThrowableProxy());
        assertFalse(event.getFormattedMessage().contains("PRIVATE-"));
        assertFalse(event.getMDCPropertyMap().toString().replace("PRIVATE-IUV", "").contains("PRIVATE-"));
      }
    }
  }

  private void assertArgs(ILoggingEvent event, String expected) throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode actual = mapper.readTree(event.getMDCPropertyMap().get("args"));
    assertEquals(mapper.readTree(expected), actual);
    assertFalse(actual.has("organizationFiscalCode"));
    assertFalse(actual.has("noticeNumber"));
    assertFalse(actual.toString().contains("PRIVATE-"));
    assertNull(event.getMDCPropertyMap().get("ctx_details"));
  }

  private void assertClean() {
    assertThrows(IllegalStateException.class, OperationLogScope::current);
    assertTrue(MDC.getCopyOfContextMap() == null || MDC.getCopyOfContextMap().isEmpty());
  }

  @RestController
  public static class ProbeController {
    @GetMapping("/logging-probe/dead-letters")
    public String getDeadLetters() {
      return "ok";
    }

    @GetMapping("/logging-probe/created")
    public ResponseEntity<String> created() {
      assertNotNull(OperationLogScope.current());
      return ResponseEntity.status(201).body("created");
    }

    @GetMapping("/logging-probe/rejected")
    public ResponseEntity<String> rejected() {
      return ResponseEntity.status(409).body("conflict");
    }

    @GetMapping("/logging-probe/required")
    public String required(@RequestParam String value) {
      return value;
    }

    @PostMapping("/logging-probe/body")
    public String body(@Valid @RequestBody Input input) {
      return input.value;
    }

    @GetMapping("/logging-probe/diagnosed")
    public String diagnosed() {
      RuntimeException cause = new IllegalStateException("already diagnosed");
      it.gov.pagopa.payments.logging.FailureDiagnostics.absorbedFailure(
          new LogEventWriter(LoggerFactory.getLogger(ProbeController.class)), cause);
      throw new IllegalArgumentException("wrapper", cause);
    }
  }

  public static class Input {
    @NotBlank public String value;
  }
}
