package it.gov.pagopa.payments.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.azure.core.http.HttpResponse;
import com.azure.core.http.rest.PagedIterable;
import com.azure.core.http.rest.PagedResponse;
import com.azure.data.tables.TableClient;
import com.azure.data.tables.models.ListEntitiesOptions;
import com.azure.data.tables.models.TableEntity;
import com.azure.data.tables.models.TableServiceError;
import com.azure.data.tables.models.TableServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import feign.Request;
import it.gov.pagopa.payments.client.GpdClient;
import it.gov.pagopa.payments.config.RestLoggingInterceptor;
import it.gov.pagopa.payments.controller.receipt.impl.PaymentsController;
import it.gov.pagopa.payments.exception.AppException;
import it.gov.pagopa.payments.exception.ErrorHandler;
import it.gov.pagopa.payments.logging.EventDetails;
import it.gov.pagopa.payments.logging.LogEventWriter;
import it.gov.pagopa.payments.logging.FailureDiagnostics;
import it.gov.pagopa.payments.logging.OperationLogScope;
import it.gov.pagopa.payments.model.PaymentOptionStatus;
import it.gov.pagopa.payments.model.PaymentsModelResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.retry.interceptor.RetryInterceptorBuilder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

class PaymentsServiceLoggingTest {
  private static final String FISCAL_CODE = "creditor-fiscal-code";
  private static final String IUV = "001234567891011";
  private final ObjectMapper json = new ObjectMapper();
  private final Map<Logger, Level> levels = new HashMap<>();
  private Map<String, String> previousMdc;
  private ListAppender<ILoggingEvent> appender;
  private TableClient tables;
  private GpdClient gpd;
  private PaymentsService service;
  private LogEventWriter writer;

  @BeforeEach
  void setUp() {
    previousMdc = MDC.getCopyOfContextMap();
    MDC.clear();
    tables = mock(TableClient.class);
    gpd = mock(GpdClient.class);
    service = new PaymentsService(gpd, tables);
    appender = new ListAppender<>() {
      @Override
      protected void append(ILoggingEvent event) {
        event.prepareForDeferredProcessing();
        super.append(event);
      }
    };
    appender.start();
    for (Class<?> type : List.of(
        PaymentsService.class, ErrorHandler.class, RestLoggingInterceptor.class)) {
      Logger logger = (Logger) LoggerFactory.getLogger(type);
      levels.put(logger, logger.getLevel());
      logger.setLevel(Level.INFO);
      logger.addAppender(appender);
    }
    writer = new LogEventWriter(LoggerFactory.getLogger(PaymentsService.class));
  }

  @AfterEach
  void tearDown() {
    levels.forEach((logger, level) -> {
      logger.detachAppender(appender);
      logger.setLevel(level);
    });
    appender.stop();
    if (previousMdc == null) {
      MDC.clear();
    } else {
      MDC.setContextMap(previousMdc);
    }
  }

  @Test
  void tableReadConversionIsDiagnosedOnceThroughActualRestBoundary() throws Exception {
    TableServiceException original = tableFailure(503);
    when(tables.getEntity(FISCAL_CODE, IUV)).thenThrow(original);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
        Map.of("organizationFiscalCode", FISCAL_CODE, "iuv", IUV));
    MockHttpServletResponse response = new MockHttpServletResponse();
    HandlerMethod handler = new HandlerMethod(new PaymentsController(),
        PaymentsController.class.getMethod(
            "getReceiptByIUV", String.class, String.class, String.class));
    RestLoggingInterceptor boundary = new RestLoggingInterceptor();
    assertTrue(boundary.preHandle(request, response, handler));
    try {
      AppException converted = assertThrows(AppException.class,
          () -> service.getReceiptByOrganizationFCAndIUV(FISCAL_CODE, IUV, null));
      assertSame(original, converted.getCause());
      assertTrue(appender.list.isEmpty(), "Propagation must not emit before ErrorHandler");
      assertEquals(HttpStatus.SERVICE_UNAVAILABLE, converted.getHttpStatus());
      assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
          new ErrorHandler().handleAppException(converted, null).getStatusCode());
      response.setStatus(503);
    } finally {
      boundary.afterCompletion(request, response, handler, null);
    }
    assertEquals(2, appender.list.size());
    assertDependency(0, "azure-table-storage", "getReceipt", 503, original);
    assertEquals(ErrorHandler.class.getName(), appender.list.get(0).getLoggerName());
    assertEquals(FISCAL_CODE, fields(0).get("creditorFiscalCode"));
    assertFalse(fields(0).containsKey("noticeNumber"), "REST IUV is not a NAV");
    assertEquals("REST_OPERATION_COMPLETED", fields(1).get("event.code"));
    assertEquals("503", fields(1).get("httpCode"));
    assertFalse(fields(1).containsKey("ctx_details"));
    assertEquals(fields(0).get("operationId"), fields(1).get("operationId"));
    assertNull(OperationLogScope.currentOrNull());
    assertNull(MDC.get("operationId"));
    verifyNoInteractions(gpd);
  }

  @Test
  void finalGpdProxyFailureEmitsOnlyOnceAfterRetries() throws Exception {
    FeignException original = feignFailure(503);
    when(gpd.getPaymentOption(FISCAL_CODE, IUV)).thenThrow(original);
    ProxyFactory proxy = new ProxyFactory(gpd);
    proxy.addAdvice(RetryInterceptorBuilder.stateless()
        .maxAttempts(3).backOffOptions(1, 1, 1).build());
    PaymentsService retrying = new PaymentsService((GpdClient) proxy.getProxy(), tables);
    try (OperationLogScope scope = OperationLogScope.open("request", "read")) {
      scope.setPaymentLogContext("genuinely-known-nav", FISCAL_CODE);
      Map<String, String> before = MDC.getCopyOfContextMap();
      assertSame(original, assertThrows(FeignException.class,
          () -> retrying.checkGPDDebtPosStatus(receipt("CREATED"))));
      assertTrue(appender.list.isEmpty(), "GPD propagation must wait for ErrorHandler");
      new ErrorHandler().handleGenericException(original, null);
      assertEquals(1, appender.list.size());
      assertDependency(0, "gpd-core", "getPaymentOption", 503, original);
      assertEquals(ErrorHandler.class.getName(), appender.list.get(0).getLoggerName());
      assertEquals("genuinely-known-nav", fields(0).get("noticeNumber"));
      assertEquals(FISCAL_CODE, fields(0).get("creditorFiscalCode"));
      before.forEach((key, value) -> assertEquals(value, MDC.get(key)));
    }
    verify(gpd, times(3)).getPaymentOption(FISCAL_CODE, IUV);
    verifyNoInteractions(tables);
  }

  @Test
  void recoveredGpdRetryHasNoTerminalDiagnostic() {
    FeignException transientFailure = feignFailure(503);
    when(gpd.getPaymentOption(FISCAL_CODE, IUV))
        .thenThrow(transientFailure).thenReturn(payment(PaymentOptionStatus.PO_PAID));
    ProxyFactory proxy = new ProxyFactory(gpd);
    proxy.addAdvice(RetryInterceptorBuilder.stateless()
        .maxAttempts(3).backOffOptions(1, 1, 1).build());
    PaymentsService retrying = new PaymentsService((GpdClient) proxy.getProxy(), tables);
    TableEntity entity = receipt("CREATED");
    retrying.checkGPDDebtPosStatus(entity);
    verify(gpd, times(2)).getPaymentOption(FISCAL_CODE, IUV);
    verify(tables).updateEntity(entity);
    assertTrue(appender.list.isEmpty());
    assertNull(OperationLogScope.currentOrNull());
  }

  @Test
  void updateConversionKeepsTablesAttributionAndBoundaryDoesNotRediagnose() throws Exception {
    TableEntity entity = receipt("CREATED");
    when(tables.getEntity(FISCAL_CODE, IUV)).thenReturn(entity);
    when(gpd.getPaymentOption(FISCAL_CODE, IUV)).thenReturn(payment(PaymentOptionStatus.PO_PAID));
    TableServiceException original = tableFailure(500);
    doThrow(original).when(tables).updateEntity(entity);
    try (OperationLogScope scope = OperationLogScope.open("request")) {
      AppException converted = assertThrows(AppException.class,
          () -> service.getReceiptByOrganizationFCAndIUV(FISCAL_CODE, IUV, null));
      assertSame(original, converted.getCause());
      assertTrue(appender.list.isEmpty());
      new ErrorHandler().handleAppException(converted, null);
      assertEquals(1, appender.list.size());
      assertDependency(0, "azure-table-storage", "updateReceiptStatus", 500, original);
      assertEquals("PAID", entity.getProperty("status"));
    }
  }

  @Test
  void swallowedListUpdateFailureRemainsObservableAndListContinues() throws Exception {
    TableEntity updating = receipt("CREATED");
    TableEntity paid = receipt("PAID");
    stubPages(List.of(updating, paid));
    when(gpd.getPaymentOption(FISCAL_CODE, IUV)).thenReturn(payment(PaymentOptionStatus.PO_PAID));
    TableServiceException original = tableFailure(503);
    doThrow(original).when(tables).updateEntity(updating);
    try (OperationLogScope scope = OperationLogScope.open("request")) {
      scope.setPaymentLogContext(null, FISCAL_CODE);
      var result = service.getOrganizationReceipts(
          FISCAL_CODE, "private-debtor", null, null, null, 0, 10, null, null);
      assertEquals(1, result.getLength());
      assertEquals(1, result.getTotalPages());
      assertEquals(IUV, result.getResults().get(0).getIuv());
      assertEquals(1, appender.list.size());
      assertDependency(0, "azure-table-storage", "updateReceiptStatus", 503, original);
      assertEquals(PaymentsService.class.getName(), appender.list.get(0).getLoggerName());
      assertFalse(fields(0).containsKey("noticeNumber"));
      assertFalse(fields(0).toString().contains("private-debtor"));
      assertFalse(fields(0).toString().contains(IUV));
    }
    verify(gpd).getPaymentOption(FISCAL_CODE, IUV);
  }

  @Test
  void updateNotFoundIsStillTechnicalAndSeparateSwallowedFailuresAreNotSuppressed()
      throws Exception {
    TableEntity first = receipt("CREATED");
    TableEntity second = receipt("CREATED");
    when(gpd.getPaymentOption(FISCAL_CODE, IUV)).thenReturn(payment(PaymentOptionStatus.PO_PAID));
    TableServiceException missingOnUpdate = tableFailure(404);
    TableServiceException unavailable = tableFailure(503);
    doThrow(missingOnUpdate).doThrow(unavailable).when(tables).updateEntity(any());
    try (OperationLogScope scope = OperationLogScope.open("request")) {
      assertTrue(service.getGPDCheckedReceiptsList(List.of(first, second)).isEmpty());
      assertEquals(2, appender.list.size());
      assertDependency(0, "azure-table-storage", "updateReceiptStatus", 404, missingOnUpdate);
      assertDependency(1, "azure-table-storage", "updateReceiptStatus", 503, unavailable);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"listEntities", "iterableByPage", "iterator", "hasNext", "next"})
  void lazyListFailuresAreDiagnosedBeforeConversion(String phase) throws Exception {
    TableServiceException original = tableFailure(503);
    stubLazyFailure(phase, original);
    try (OperationLogScope scope = OperationLogScope.open("request")) {
      AppException converted = assertThrows(AppException.class,
          () -> service.getOrganizationReceipts(
              FISCAL_CODE, null, null, null, null, 0, 10, null, null));
      assertSame(original, converted.getCause());
      assertTrue(appender.list.isEmpty());
      assertEquals(HttpStatus.SERVICE_UNAVAILABLE, converted.getHttpStatus());
      new ErrorHandler().handleAppException(converted, null);
      assertEquals(1, appender.list.size());
      assertDependency(0, "azure-table-storage", "listReceipts", 503, original);
    }
    verifyNoInteractions(gpd);
  }

  @Test
  void unexpectedLazyTransportFailureIsRethrownUnchanged() throws Exception {
    RuntimeException original = new IllegalStateException("transport unavailable");
    stubLazyFailure("hasNext", original);
    assertSame(original, assertThrows(IllegalStateException.class,
        () -> service.retrieveEntitiesByFilter(
            tables, FISCAL_CODE, null, null, null, null, 0, 10, null, null)));
    assertTrue(appender.list.isEmpty(), "Unscoped propagation belongs to the caller");
  }

  @Test
  void readNotFoundIsExpectedAndHasNoError() {
    TableServiceException original = tableFailure(404);
    when(tables.getEntity(FISCAL_CODE, IUV)).thenThrow(original);
    try (OperationLogScope scope = OperationLogScope.open("request")) {
      AppException converted = assertThrows(AppException.class,
          () -> service.getReceiptByOrganizationFCAndIUV(FISCAL_CODE, IUV, null));
      assertEquals(HttpStatus.NOT_FOUND, converted.getHttpStatus());
      new ErrorHandler().handleAppException(converted, null);
      assertTrue(appender.list.isEmpty());
    }
    verifyNoInteractions(gpd);
  }

  @Test
  void gpdNotFoundIsUnchangedForDetailAndSwallowedForListWithoutErrors() {
    FeignException original = feignFailure(404);
    when(tables.getEntity(FISCAL_CODE, IUV)).thenReturn(receipt("CREATED"));
    when(gpd.getPaymentOption(FISCAL_CODE, IUV)).thenThrow(original);
    try (OperationLogScope scope = OperationLogScope.open("request")) {
      assertSame(original, assertThrows(FeignException.NotFound.class,
          () -> service.getReceiptByOrganizationFCAndIUV(FISCAL_CODE, IUV, null)));
      assertTrue(service.getGPDCheckedReceiptsList(List.of(receipt("CREATED"))).isEmpty());
      assertTrue(appender.list.isEmpty());
    }
    verify(tables, never()).updateEntity(any());
  }

  @Test
  void unpaidIsExpectedAndStillOmittedFromLists() {
    when(gpd.getPaymentOption(FISCAL_CODE, IUV)).thenReturn(payment(PaymentOptionStatus.PO_UNPAID));
    try (OperationLogScope scope = OperationLogScope.open("request")) {
      AppException failure = assertThrows(AppException.class,
          () -> service.checkGPDDebtPosStatus(receipt("CREATED")));
      assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, failure.getHttpStatus());
      new ErrorHandler().handleAppException(failure, null);
      assertTrue(service.getGPDCheckedReceiptsList(List.of(receipt("CREATED"))).isEmpty());
      assertTrue(appender.list.isEmpty());
    }
    verifyNoInteractions(tables);
  }

  @Test
  void validationAndPaginationErrorsAreNotDependencies() {
    try (OperationLogScope scope = OperationLogScope.open("request")) {
      AppException forbidden = assertThrows(AppException.class,
          () -> service.getReceiptByOrganizationFCAndIUV(
              FISCAL_CODE, IUV, List.of("99")));
      assertEquals(HttpStatus.FORBIDDEN, forbidden.getHttpStatus());
      new ErrorHandler().handleAppException(forbidden, null);
      AppException invalidDate = assertThrows(AppException.class,
          () -> service.getOrganizationReceipts(
              FISCAL_CODE, null, null, "invalid-date", null, 0, 10, null, null));
      new ErrorHandler().handleAppException(invalidDate, null);
      verifyNoInteractions(tables, gpd);
      stubPages(List.of());
      AppException invalidPage = assertThrows(AppException.class,
          () -> service.retrieveEntitiesByFilter(
              tables, FISCAL_CODE, null, null, null, null, 1, 10, null, null));
      new ErrorHandler().handleAppException(invalidPage, null);
      assertTrue(appender.list.isEmpty());
    }
  }

  @Test
  void successfulDirectCallsNeedNoScopeAndCreateNoOperationIds() {
    TableEntity entity = receipt("CREATED");
    when(tables.getEntity(FISCAL_CODE, IUV)).thenReturn(entity);
    when(gpd.getPaymentOption(FISCAL_CODE, IUV)).thenReturn(payment(PaymentOptionStatus.PO_PAID));
    var result = service.getReceiptByOrganizationFCAndIUV(FISCAL_CODE, IUV, null);
    assertEquals(IUV, result.getIuv());
    assertEquals("PAID", result.getStatus());
    verify(tables).updateEntity(entity);
    assertTrue(appender.list.isEmpty());
    assertNull(OperationLogScope.currentOrNull());
    assertNull(MDC.get("operationId"));
  }

  @Test
  void directFailureAndConversionWorkWithoutScope() throws Exception {
    TableServiceException original = tableFailure(500);
    when(tables.getEntity(FISCAL_CODE, IUV)).thenThrow(original);
    AppException converted = assertThrows(AppException.class,
        () -> service.getReceiptByOrganizationFCAndIUV(FISCAL_CODE, IUV, null));
    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, converted.getHttpStatus());
    assertTrue(appender.list.isEmpty());
    assertNull(OperationLogScope.currentOrNull());
    assertNull(MDC.get("operationId"));
  }

  @Test
  void localMappingFailureRemainsLocalAtRestBoundary() {
    TableEntity malformed = new TableEntity(FISCAL_CODE, IUV).addProperty("status", "PAID");
    when(tables.getEntity(FISCAL_CODE, IUV)).thenReturn(malformed);
    try (OperationLogScope scope = OperationLogScope.open("request")) {
      NullPointerException failure = assertThrows(NullPointerException.class,
          () -> service.getReceiptByOrganizationFCAndIUV(FISCAL_CODE, IUV, null));
      assertTrue(appender.list.isEmpty());
      new ErrorHandler().handleGenericException(failure, null);
      assertEquals(1, appender.list.size());
      assertEquals("OPERATION_TECHNICAL_FAILED", fields(0).get("event.code"));
      assertFalse(fields(0).containsKey("ctx_details"));
    }
    verifyNoInteractions(gpd);
  }

  @Test
  void nullGpdStatusIsLocalAndNotFalselyAttributedToGpd() {
    when(gpd.getPaymentOption(FISCAL_CODE, IUV)).thenReturn(new PaymentsModelResponse());
    try (OperationLogScope scope = OperationLogScope.open("request")) {
      NullPointerException failure = assertThrows(NullPointerException.class,
          () -> service.checkGPDDebtPosStatus(receipt("CREATED")));
      assertTrue(appender.list.isEmpty());
      FailureDiagnostics.technicalFailure(writer, failure);
      assertEquals("OPERATION_TECHNICAL_FAILED", fields(0).get("event.code"));
      assertFalse(fields(0).containsKey("ctx_details"));
    }
    verifyNoInteractions(tables);
  }

  @Test
  void sharedApiExtractsWrappedStatusesThroughPreservedCauses() throws Exception {
    RuntimeException wrapped = new RuntimeException("wrapper", feignFailure(502));
    RuntimeException converted = new RuntimeException("converted", wrapped);
    try (OperationLogScope scope = OperationLogScope.open("request")) {
      FailureDiagnostics.dependencyFailure(EventDetails.Dependency.GPD_CORE,
          EventDetails.DependencyOperation.GET_PAYMENT_OPTION, System.nanoTime(), wrapped);
      assertTrue(appender.list.isEmpty());
      FailureDiagnostics.technicalFailure(writer, converted);
      assertEquals(1, appender.list.size());
      assertDependency(0, "gpd-core", "getPaymentOption", 502, wrapped);
    }
    appender.list.clear();
    RuntimeException azureWrapped = new RuntimeException("azure wrapper", tableFailure(429));
    try (OperationLogScope scope = OperationLogScope.open("azure")) {
      FailureDiagnostics.dependencyFailure(EventDetails.Dependency.AZURE_TABLE_STORAGE,
          EventDetails.DependencyOperation.GET_RECEIPT, System.nanoTime(), azureWrapped);
      assertTrue(appender.list.isEmpty());
      FailureDiagnostics.technicalFailure(writer, azureWrapped);
      assertDependency(0, "azure-table-storage", "getReceipt", 429, azureWrapped);
    }
  }

  @Test
  void sharedApiHandlesCyclicCausesWithoutStatus() throws Exception {
    RuntimeException first = new RuntimeException("first");
    RuntimeException second = new RuntimeException("second", first);
    first.initCause(second);
    try (OperationLogScope scope = OperationLogScope.open("cyclic")) {
      FailureDiagnostics.dependencyFailure(EventDetails.Dependency.GPD_CORE,
          EventDetails.DependencyOperation.GET_PAYMENT_OPTION, System.nanoTime(), first);
      FailureDiagnostics.technicalFailure(writer, first);
      assertDependency(0, "gpd-core", "getPaymentOption", null, first);
    }
  }

  private Map<String, String> fields(int index) {
    return appender.list.get(index).getMDCPropertyMap();
  }

  private void assertDependency(
      int index, String dependency, String operation, Integer status, Throwable failure)
      throws Exception {
    ILoggingEvent event = appender.list.get(index);
    assertEquals(Level.ERROR, event.getLevel());
    assertEquals("DEPENDENCY_CALL_FAILED", fields(index).get("event.code"));
    assertNull(event.getThrowableProxy());
    assertEquals(failure.getClass().getName(), fields(index).get("error.type"));
    assertEquals(failure.getMessage(), fields(index).get("error.message"));
    assertNotNull(fields(index).get("error.stack_trace"));
    JsonNode details = json.readTree(fields(index).get("ctx_details"));
    assertEquals(dependency, details.get("dependency").asText());
    assertEquals(operation, details.get("dependencyOperation").asText());
    assertEquals("KO", details.get("dependencyOutcome").asText());
    if (status == null) {
      assertFalse(details.has("dependencyHttpCode"));
    } else {
      assertEquals(status.intValue(), details.get("dependencyHttpCode").asInt());
    }
    assertTrue(details.get("durationMs").asLong() >= 0);
    assertEquals(status == null ? 4 : 5, details.size());
    assertFalse(fields(index).containsKey("args"));
  }

  private TableEntity receipt(String status) {
    return new TableEntity(FISCAL_CODE, IUV)
        .addProperty("status", status)
        .addProperty("debtor", "private-debtor")
        .addProperty("paymentDate", "2026-10-01T12:00:00")
        .addProperty("document", "<private-receipt/>");
  }

  private PaymentsModelResponse payment(PaymentOptionStatus status) {
    PaymentsModelResponse payment = new PaymentsModelResponse();
    payment.setStatus(status);
    return payment;
  }

  private TableServiceException tableFailure(int status) {
    HttpResponse response = mock(HttpResponse.class);
    when(response.getStatusCode()).thenReturn(status);
    return new TableServiceException("storage failure", response,
        new TableServiceError(status == 404 ? "ResourceNotFound" : "InternalError",
            "storage failure"));
  }

  private FeignException feignFailure(int status) {
    Request request = Request.create(Request.HttpMethod.GET, "https://unused.invalid",
        Map.of(), null, StandardCharsets.UTF_8, null);
    return FeignException.errorStatus("getPaymentOption", feign.Response.builder()
        .request(request).status(status).headers(Map.of()).build());
  }

  @SuppressWarnings("unchecked")
  private void stubPages(List<TableEntity> entities) {
    PagedIterable<TableEntity> paged = mock(PagedIterable.class);
    PagedResponse<TableEntity> page = mock(PagedResponse.class);
    when(tables.listEntities(any(ListEntitiesOptions.class), isNull(), isNull())).thenReturn(paged);
    when(paged.iterableByPage()).thenReturn(List.of(page));
    when(page.getValue()).thenReturn(new ArrayList<>(entities));
  }

  @SuppressWarnings("unchecked")
  private void stubLazyFailure(String phase, RuntimeException failure) {
    if (phase.equals("listEntities")) {
      when(tables.listEntities(any(ListEntitiesOptions.class), isNull(), isNull()))
          .thenThrow(failure);
      return;
    }
    PagedIterable<TableEntity> paged = mock(PagedIterable.class);
    when(tables.listEntities(any(ListEntitiesOptions.class), isNull(), isNull())).thenReturn(paged);
    if (phase.equals("iterableByPage")) {
      when(paged.iterableByPage()).thenThrow(failure);
      return;
    }
    Iterable<PagedResponse<TableEntity>> pages = mock(Iterable.class);
    when(paged.iterableByPage()).thenReturn(pages);
    if (phase.equals("iterator")) {
      when(pages.iterator()).thenThrow(failure);
      return;
    }
    Iterator<PagedResponse<TableEntity>> iterator = mock(Iterator.class);
    when(pages.iterator()).thenReturn(iterator);
    if (phase.equals("hasNext")) {
      when(iterator.hasNext()).thenThrow(failure);
    } else {
      when(iterator.hasNext()).thenReturn(true);
      when(iterator.next()).thenThrow(failure);
    }
  }
}
