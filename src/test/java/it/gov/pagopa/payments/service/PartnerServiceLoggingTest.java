package it.gov.pagopa.payments.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.azure.core.http.HttpResponse;
import com.azure.core.util.Context;
import com.azure.data.tables.TableClient;
import com.azure.data.tables.models.TableErrorCode;
import com.azure.data.tables.models.TableServiceError;
import com.azure.data.tables.models.TableServiceException;
import com.azure.storage.queue.QueueClient;
import com.azure.storage.queue.models.QueueStorageException;
import com.microsoft.azure.storage.StorageException;
import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.RetryableException;
import it.gov.pagopa.payments.client.GpdClient;
import it.gov.pagopa.payments.config.VerticalServicesConfig;
import it.gov.pagopa.payments.config.RestTemplateConfig;
import it.gov.pagopa.payments.endpoints.validation.SoapMessageDispatcher;
import it.gov.pagopa.payments.endpoints.validation.exceptions.PartnerValidationException;
import it.gov.pagopa.payments.entity.ReceiptEntity;
import it.gov.pagopa.payments.exception.AppException;
import it.gov.pagopa.payments.logging.LogEventWriter;
import it.gov.pagopa.payments.logging.FailureDiagnostics;
import it.gov.pagopa.payments.logging.OperationLogScope;
import it.gov.pagopa.payments.mock.PaDemandNoticePaymentReqMock;
import it.gov.pagopa.payments.mock.PaSendRTReqMock;
import it.gov.pagopa.payments.mock.PaVerifyPaymentNoticeReqMock;
import it.gov.pagopa.payments.model.*;
import it.gov.pagopa.payments.model.client.cache.ConfigCacheData;
import it.gov.pagopa.payments.model.client.cache.MaintenanceStation;
import it.gov.pagopa.payments.model.partner.ObjectFactory;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import javax.xml.bind.JAXBElement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.mockito.MockedStatic;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.retry.interceptor.RetryInterceptorBuilder;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

class PartnerServiceLoggingTest {
  private final GpdClient gpd = mock(GpdClient.class);
  private final TableClient table = mock(TableClient.class);
  private final QueueClient queue = mock(QueueClient.class);
  private final VerticalServicesConfig vertical = mock(VerticalServicesConfig.class);
  private final RestTemplate rest = mock(RestTemplate.class);
  private final Logger logger = (Logger) LoggerFactory.getLogger(PartnerService.class);
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>() {
    @Override protected void append(ILoggingEvent event) {
      event.prepareForDeferredProcessing();
      super.append(event);
    }
  };
  private Level previousLevel;
  private PartnerService service;

  @BeforeEach
  void setUp() {
    previousLevel = logger.getLevel();
    logger.setLevel(Level.DEBUG);
    logs.start();
    logger.addAppender(logs);
    service = new PartnerService(30L, List.of(), List.of(), new ObjectFactory(), gpd,
        table, queue, null, vertical, rest);
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(logs);
    logger.setLevel(previousLevel);
    logs.stop();
    MDC.clear();
  }

  @Test
  void gpdGetFailureTravelsToBoundaryThroughOriginalCause() throws Exception {
    FeignException original = feign(503);
    when(gpd.getPaymentOption(anyString(), anyString())).thenThrow(original);
    try (OperationLogScope scope = OperationLogScope.open("request")) {
      PartnerValidationException converted = assertThrows(PartnerValidationException.class,
          () -> service.paVerifyPaymentNotice(PaVerifyPaymentNoticeReqMock.getMock(), null));
      assertEquals(PaaErrorEnum.PAA_SYSTEM_ERROR, converted.getError());
      assertSame(original, converted.getCause());
      assertTrue(errors().isEmpty());
      complete(converted);
      assertDependency("gpd-core", "getPaymentOption", 503);
      assertEquals("77777777777", errors().get(0).getMDCPropertyMap().get("creditorFiscalCode"));
      assertNotNull(errors().get(0).getMDCPropertyMap().get("noticeNumber"));
      FailureDiagnostics.technicalFailure(new LogEventWriter(logger), new IllegalStateException("distinct"));
      assertEquals(2, errors().size());
    }
  }

  @Test
  void gpdGetNotFoundRemainsBusinessOnlyWithoutImplicitScope() throws Exception {
    when(gpd.getPaymentOption(anyString(), anyString())).thenThrow(feign(404));
    PartnerValidationException converted = assertThrows(PartnerValidationException.class,
        () -> service.paVerifyPaymentNotice(PaVerifyPaymentNoticeReqMock.getMock(), null));
    assertEquals(PaaErrorEnum.PAA_PAGAMENTO_SCONOSCIUTO, converted.getError());
    assertTrue(errors().isEmpty());
    assertNull(OperationLogScope.currentOrNull());
    assertNull(MDC.get("operationId"));
  }

  @Test
  void gpdPostFailureRetainsQueueAndFaultRouteWithoutDuplicateDiagnosis() throws Exception {
    when(gpd.sendPaymentOptionReceipt(anyString(), anyString(), any())).thenThrow(feign(500));
    try (OperationLogScope scope = OperationLogScope.open("request")) {
      PartnerValidationException converted = assertThrows(PartnerValidationException.class,
          () -> service.paSendRT(PaSendRTReqMock.getMock("123")));
      assertEquals(PaaErrorEnum.PAA_SEMANTICA, converted.getError());
      assertTrue(scope.isHandled(converted));
      complete(converted);
      assertTrue(errors().isEmpty());
      assertEquals(1, events("RECEIPT_RETRY_SCHEDULED").size());
      verify(queue).sendMessageWithResponse(anyString(), any(), isNull(), isNull(), any());
      verifyNoInteractions(table);
    }
  }

  @ParameterizedTest
  @CsvSource({"feign,false", "retryable,false", "storage,false",
      "feign,true", "retryable,true", "storage,true"})
  void initialRetryIsReportedOnlyAfterEnqueueWithoutChangingXmlOrFault(
      String failureType, boolean v2) throws Exception {
    Exception original = receiptFailure(failureType);
    doAnswer(invocation -> { throw original; })
        .when(gpd).sendPaymentOptionReceipt(anyString(), anyString(), any());
    Object request = v2 ? PaSendRTReqMock.getMockV2("123") : PaSendRTReqMock.getMock("123");
    String xml = ReflectionTestUtils.invokeMethod(service, v2 ? "marshalV2" : "marshal", request);
    doAnswer(invocation -> {
      assertTrue(events("RECEIPT_RETRY_SCHEDULED").isEmpty());
      assertEquals("storage".equals(failureType), OperationLogScope.current().hasDiagnosis());
      assertEquals(xml, invocation.getArgument(0));
      return null;
    }).when(queue).sendMessageWithResponse(anyString(), any(), isNull(), isNull(), any());

    try (OperationLogScope scope = OperationLogScope.open("request")) {
      PartnerValidationException converted = assertThrows(PartnerValidationException.class,
          () -> sendReceipt(request, v2));
      assertEquals("feign".equals(failureType) ? PaaErrorEnum.PAA_SEMANTICA
          : PaaErrorEnum.PAA_SYSTEM_ERROR, converted.getError());
      assertSame(original, converted.getCause());
      assertTrue(scope.isHandled(converted));
      complete(converted);
      if ("storage".equals(failureType)) {
        assertDependency("gpd-core", "sendPaymentOptionReceipt", null);
      } else {
        assertTrue(errors().isEmpty());
      }
      verify(queue).sendMessageWithResponse(eq(xml), eq(Duration.ofSeconds(30)),
          isNull(), isNull(), same(Context.NONE));
      verifyNoInteractions(table);
      assertEquals(1, events("RECEIPT_RETRY_SCHEDULED").size());
      ILoggingEvent scheduled = events("RECEIPT_RETRY_SCHEDULED").get(0);
      assertEquals(Level.INFO, scheduled.getLevel());
      assertEquals("Receipt retry scheduled", scheduled.getFormattedMessage());
      String details = scheduled.getMDCPropertyMap().get("ctx_details");
      assertTrue(details.contains("\"retryDelayMs\":30000"), details);
      assertTrue(details.contains("\"reason\":\"RETRYABLE_FAILURE\""), details);
      if (!"storage".equals(failureType)) {
        assertTrue(details.contains("\"dependency\":\"gpd-core\""), details);
      }
      assertFalse(details.contains("\"attempt\""), details);
      assertFalse(details.contains("\"messageId\""), details);
      assertNull(scheduled.getMDCPropertyMap().get("error.type"));
      assertTrue(logs.list.stream().noneMatch(event -> event.getLevel() == Level.WARN));
      assertTrue(logs.list.stream().allMatch(event -> event.getThrowableProxy() == null));
      assertNull(MDC.get("event.code"));
    }
  }

  @ParameterizedTest
  @CsvSource({"feign,false", "retryable,false", "storage,false",
      "feign,true", "retryable,true", "storage,true"})
  void initialQueueFailureIsDiagnosedSeparatelyAndPropagatesWithoutScheduledEvent(
      String failureType, boolean v2) throws Exception {
    Exception original = receiptFailure(failureType);
    doAnswer(invocation -> { throw original; })
        .when(gpd).sendPaymentOptionReceipt(anyString(), anyString(), any());
    HttpResponse response = mock(HttpResponse.class);
    when(response.getStatusCode()).thenReturn(503);
    QueueStorageException queueFailure = new QueueStorageException("queue unavailable", response, null);
    doAnswer(invocation -> {
      Thread.sleep(25);
      throw queueFailure;
    }).when(queue).sendMessageWithResponse(anyString(), any(), isNull(), isNull(), any());
    Object request = v2 ? PaSendRTReqMock.getMockV2("123") : PaSendRTReqMock.getMock("123");

    try (OperationLogScope scope = OperationLogScope.open("request")) {
      assertSame(queueFailure, assertThrows(QueueStorageException.class,
          () -> sendReceipt(request, v2)));
      assertTrue(events("RECEIPT_RETRY_SCHEDULED").isEmpty());
      boolean storageFailure = "storage".equals(failureType);
      assertEquals(storageFailure ? 1 : 0, errors().size());
      complete(queueFailure);
      assertEquals(storageFailure ? 2 : 1, errors().size());
      ILoggingEvent failure = errors().get(errors().size() - 1);
      assertEquals("DEPENDENCY_CALL_FAILED", failure.getMDCPropertyMap().get("event.code"));
      var details = new com.fasterxml.jackson.databind.ObjectMapper()
          .readTree(failure.getMDCPropertyMap().get("ctx_details"));
      assertEquals("azure-queue-storage", details.get("dependency").asText());
      assertEquals("enqueueReceiptRetry", details.get("dependencyOperation").asText());
      assertEquals("KO", details.get("dependencyOutcome").asText());
      assertEquals(503, details.get("dependencyHttpCode").asInt());
      assertTrue(details.get("durationMs").asLong() >= 20);
      assertEquals(queueFailure.getClass().getName(), failure.getMDCPropertyMap().get("error.type"));
      assertEquals(queueFailure.getMessage(), failure.getMDCPropertyMap().get("error.message"));
      assertFalse(scope.isHandled(queueFailure));
      assertEquals(storageFailure, scope.isHandled(original),
          "Failed enqueue must not claim recovery of an undiagnosed GPD failure");
      assertTrue(logs.list.stream().allMatch(event -> event.getThrowableProxy() == null));
      verify(queue).sendMessageWithResponse(anyString(), eq(Duration.ofSeconds(30)),
          isNull(), isNull(), same(Context.NONE));
      verifyNoInteractions(table);
      assertNull(MDC.get("error.type"));
    }
  }

  @ParameterizedTest
  @CsvSource({"feign,false", "retryable,false", "feign,true", "retryable,true"})
  void recoveredGpdFailureHasOnlyRetryEventAndSoapCompletion(String failureType, boolean v2)
      throws Exception {
    Exception original = receiptFailure(failureType);
    doAnswer(invocation -> { throw original; })
        .when(gpd).sendPaymentOptionReceipt(anyString(), anyString(), any());
    Object request = v2 ? PaSendRTReqMock.getMockV2("123") : PaSendRTReqMock.getMock("123");

    MockHttpServletResponse response = sendThroughSoap(request, v2);

    assertEquals(200, response.getStatus());
    PaaErrorEnum fault = "retryable".equals(failureType)
        ? PaaErrorEnum.PAA_SYSTEM_ERROR : PaaErrorEnum.PAA_SEMANTICA;
    assertTrue(response.getContentAsString().contains(fault.getFaultCode()));
    List<ILoggingEvent> catalogEvents = logs.list.stream()
        .filter(event -> event.getMDCPropertyMap().containsKey("event.code")).toList();
    assertEquals(List.of("RECEIPT_RETRY_SCHEDULED", "SOAP_OPERATION_COMPLETED"),
        catalogEvents.stream().map(event -> event.getMDCPropertyMap().get("event.code")).toList());
    assertTrue(catalogEvents.stream().allMatch(event -> event.getLevel() == Level.INFO));
    assertTrue(errors().isEmpty());
    assertEquals("KO", catalogEvents.get(1).getMDCPropertyMap().get("status"));
    assertEquals("200", catalogEvents.get(1).getMDCPropertyMap().get("httpCode"));
    assertNull(OperationLogScope.currentOrNull());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void nonFeignGpdRuntimeFailureIsDiagnosedOnlyBySoapDispatcher(boolean v2) throws Exception {
    RuntimeException original = new IllegalStateException("GPD client failed");
    when(gpd.sendPaymentOptionReceipt(anyString(), anyString(), any())).thenThrow(original);
    Object request = v2 ? PaSendRTReqMock.getMockV2("123") : PaSendRTReqMock.getMock("123");

    MockHttpServletResponse response = sendThroughSoap(request, v2);

    assertEquals(200, response.getStatus());
    assertTrue(response.getContentAsString().contains(PaaErrorEnum.PAA_SYSTEM_ERROR.getFaultCode()));
    assertDependency("gpd-core", "sendPaymentOptionReceipt", null);
    assertEquals(SoapMessageDispatcher.class.getName(), errors().get(0).getLoggerName());
    assertEquals(original.getClass().getName(), errors().get(0).getMDCPropertyMap().get("error.type"));
    assertTrue(events("RECEIPT_RETRY_SCHEDULED").isEmpty());
    assertEquals(1, events("SOAP_OPERATION_COMPLETED").size());
    verifyNoInteractions(queue, table);
  }

  @ParameterizedTest
  @ValueSource(strings = {"gpd", "tables"})
  void propagatedDependencyIsSilentUntilDispatcherAndRetainsCapturedCallDuration(String dependency)
      throws Exception {
    Exception original = dependency.equals("gpd") ? feign(503)
        : tableFailure(TableErrorCode.fromString("InternalError"));
    if (dependency.equals("gpd")) {
      when(gpd.getPaymentOption(anyString(), anyString())).thenAnswer(invocation -> {
        Thread.sleep(25);
        throw original;
      });
    } else {
      when(gpd.sendPaymentOptionReceipt(anyString(), anyString(), any())).thenReturn(paid());
      doAnswer(invocation -> {
        Thread.sleep(25);
        throw original;
      }).when(table).createEntity(any());
    }
    java.util.concurrent.atomic.AtomicLong capturedDuration = new java.util.concurrent.atomic.AtomicLong();
    MockHttpServletResponse response = runThroughSoap(
        dependency.equals("gpd") ? "paVerifyPaymentNotice" : "paSendRT", () -> {
          try {
            if (dependency.equals("gpd")) {
              service.paVerifyPaymentNotice(PaVerifyPaymentNoticeReqMock.getMock(), null);
            } else {
              service.paSendRT(PaSendRTReqMock.getMock("123"));
            }
          } catch (PartnerValidationException converted) {
            assertTrue(errors().isEmpty(), "Intermediate propagation must not log");
            var context = OperationLogScope.current().dependencyFailure(converted);
            assertSame(original, context.failure());
            capturedDuration.set(context.details().durationMs());
            Thread.sleep(40);
            throw converted;
          }
        });
    assertEquals(200, response.getStatus());
    assertTrue(response.getContentAsString().contains("PAA_SYSTEM_ERROR"));
    assertDependency(dependency.equals("gpd") ? "gpd-core" : "azure-table-storage",
        dependency.equals("gpd") ? "getPaymentOption" : "createReceipt", 503);
    ILoggingEvent diagnostic = errors().get(0);
    assertEquals(SoapMessageDispatcher.class.getName(), diagnostic.getLoggerName());
    assertEquals(original.getMessage(), diagnostic.getMDCPropertyMap().get("error.message"));
    assertTrue(capturedDuration.get() >= 20);
    assertEquals(capturedDuration.get(), new com.fasterxml.jackson.databind.ObjectMapper()
        .readTree(diagnostic.getMDCPropertyMap().get("ctx_details")).get("durationMs").asLong());
  }

  @Test
  void directSchedulerFeignFailureWithoutCoordinatorRemainsUnchangedAndCreatesNoScope()
      throws Exception {
    FeignException original = feign(503);
    when(gpd.sendPaymentOptionReceipt(anyString(), anyString(), any())).thenThrow(original);

    assertSame(original, assertThrows(FeignException.class, this::sendDirect));
    assertTrue(errors().isEmpty());
    assertTrue(events("RECEIPT_RETRY_SCHEDULED").isEmpty());
    assertNull(OperationLogScope.currentOrNull());
    verifyNoInteractions(queue, table);
  }

  @Test
  void exhaustedRetryProxyIsDiagnosedOnlyAfterItsFinalAttempt() throws Exception {
    when(gpd.getPaymentOption(anyString(), anyString())).thenThrow(feign(503));
    ProxyFactory proxy = new ProxyFactory(gpd);
    proxy.addAdvice(RetryInterceptorBuilder.stateless().maxAttempts(3)
        .backOffOptions(1, 1, 1).build());
    PartnerService retryService = new PartnerService(30L, List.of(), List.of(),
        new ObjectFactory(), (GpdClient) proxy.getProxy(), table, queue, null, vertical, rest);
    try (OperationLogScope ignored = OperationLogScope.open("request")) {
      var converted = assertThrows(PartnerValidationException.class,
          () -> retryService.paVerifyPaymentNotice(PaVerifyPaymentNoticeReqMock.getMock(), null));
      verify(gpd, times(3)).getPaymentOption(anyString(), anyString());
      assertTrue(errors().isEmpty());
      complete(converted);
      assertDependency("gpd-core", "getPaymentOption", 503);
    }
  }

  @Test
  void postNotFoundRemainsBusinessOnlyAndDoesNotEnqueue() throws Exception {
    when(gpd.sendPaymentOptionReceipt(anyString(), anyString(), any())).thenThrow(feign(404));
    try (OperationLogScope ignored = OperationLogScope.open("request")) {
      assertEquals(PaaErrorEnum.PAA_PAGAMENTO_SCONOSCIUTO,
          assertThrows(PartnerValidationException.class,
              () -> service.paSendRT(PaSendRTReqMock.getMock("123"))).getError());
      assertTrue(errors().isEmpty());
      verifyNoInteractions(table, queue);
    }
  }

  @Test
  void tableCreateFailureKeepsOriginalCauseAndTablesContextUntilBoundary() throws Exception {
    when(gpd.sendPaymentOptionReceipt(anyString(), anyString(), any())).thenReturn(paid());
    TableServiceException original = tableFailure(TableErrorCode.fromString("InternalError"));
    doThrow(original).when(table).createEntity(any());
    try (OperationLogScope scope = OperationLogScope.open("request")) {
      PartnerValidationException converted = assertThrows(PartnerValidationException.class,
          () -> service.paSendRT(PaSendRTReqMock.getMock("123")));
      assertEquals(PaaErrorEnum.PAA_SYSTEM_ERROR, converted.getError());
      assertSame(original, converted.getCause().getCause());
      assertTrue(errors().isEmpty());
      complete(converted);
      assertDependency("azure-table-storage", "createReceipt", 503);
      verifyNoInteractions(queue);
    }
  }

  @Test
  void directTableConversionPreservesCauseAndWaitsForBoundary() throws Exception {
    when(gpd.sendPaymentOptionReceipt(anyString(), anyString(), any())).thenReturn(paid());
    doThrow(tableFailure(TableErrorCode.fromString("InternalError"))).when(table).createEntity(any());
    try (OperationLogScope scope = OperationLogScope.open("request")) {
      AppException converted = assertThrows(AppException.class, this::sendDirect);
      assertInstanceOf(TableServiceException.class, converted.getCause());
      assertTrue(errors().isEmpty());
      complete(converted);
      assertDependency("azure-table-storage", "createReceipt", 503);
    }
  }

  @Test
  void reconciliationReadFailureIsDiagnosedAndSwallowedBeforeDuplicateFault() throws Exception {
    when(gpd.sendPaymentOptionReceipt(anyString(), anyString(), any())).thenThrow(feign(409));
    doThrow(tableFailure(TableErrorCode.fromString("InternalError")))
        .when(table).getEntity(anyString(), anyString());
    try (OperationLogScope ignored = OperationLogScope.open("request")) {
      PartnerValidationException converted = assertThrows(PartnerValidationException.class, this::sendDirect);
      assertEquals(PaaErrorEnum.PAA_RECEIPT_DUPLICATA, converted.getError());
      assertDependency("azure-table-storage", "getReceipt", 503);
      verify(table, never()).createEntity(any());
      verifyNoInteractions(queue);
    }
  }

  @Test
  void reconciliationCreateFailureIsDiagnosedAndSwallowedBeforeDuplicateFault() throws Exception {
    when(gpd.sendPaymentOptionReceipt(anyString(), anyString(), any())).thenThrow(feign(409));
    doThrow(tableFailure(TableErrorCode.RESOURCE_NOT_FOUND))
        .when(table).getEntity(anyString(), anyString());
    doThrow(tableFailure(TableErrorCode.fromString("InternalError"))).when(table).createEntity(any());
    try (OperationLogScope ignored = OperationLogScope.open("request")) {
      PartnerValidationException converted = assertThrows(PartnerValidationException.class, this::sendDirect);
      assertEquals(PaaErrorEnum.PAA_RECEIPT_DUPLICATA, converted.getError());
      assertDependency("azure-table-storage", "createReceipt", 503);
    }
  }

  @Test
  void expectedTableDuplicateHasNoError() throws Exception {
    when(gpd.sendPaymentOptionReceipt(anyString(), anyString(), any())).thenReturn(paid());
    doThrow(tableFailure(TableErrorCode.ENTITY_ALREADY_EXISTS)).when(table).createEntity(any());
    try (OperationLogScope ignored = OperationLogScope.open("request")) {
      assertEquals(PaaErrorEnum.PAA_RECEIPT_DUPLICATA,
          assertThrows(PartnerValidationException.class, this::sendDirect).getError());
      assertTrue(errors().isEmpty());
    }
  }

  @Test
  void expectedTableAbsenceReconcilesAndStillReturnsDuplicateWithoutError() throws Exception {
    when(gpd.sendPaymentOptionReceipt(anyString(), anyString(), any())).thenThrow(feign(409));
    doThrow(tableFailure(TableErrorCode.RESOURCE_NOT_FOUND))
        .when(table).getEntity(anyString(), anyString());
    try (OperationLogScope ignored = OperationLogScope.open("request")) {
      assertEquals(PaaErrorEnum.PAA_RECEIPT_DUPLICATA,
          assertThrows(PartnerValidationException.class, this::sendDirect).getError());
      verify(table).createEntity(any());
      assertTrue(errors().isEmpty());
    }
  }

  @Test
  void acaNonStandInReceiptIsNotPersisted() throws Exception {
    PaymentOptionModelResponse response = paid();
    response.setServiceType("ACA");
    when(gpd.sendPaymentOptionReceipt(anyString(), anyString(), any())).thenReturn(response);
    assertSame(response, sendDirect());
    verifyNoInteractions(table);
    assertTrue(errors().isEmpty());
    assertNull(MDC.get("operationId"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"STATION_MAINTENANCE_WITHOUT_STAND_IN", "STATION_ACA_DISABLED",
      "PAYMENT_NOT_ACA", "STATION_STAND_IN_DISABLED", "PAYMENT_STAND_IN_DISABLED"})
  void eligibilityRejectionsUseTypedInfoReasons(String reason) throws Exception {
    try (MockedStatic<ConfigCacheData> cache = mockStatic(ConfigCacheData.class);
         OperationLogScope ignored = OperationLogScope.open("request")) {
      ConfigCacheData.StationCI station = ConfigCacheData.StationCI.builder()
          .aca(!reason.equals("STATION_ACA_DISABLED"))
          .standin(!reason.equals("STATION_STAND_IN_DISABLED")).build();
      cache.when(() -> ConfigCacheData.getCreditorInstitutionStation(anyString(), anyString()))
          .thenReturn(station);
      if (reason.equals("STATION_MAINTENANCE_WITHOUT_STAND_IN")) {
        cache.when(() -> ConfigCacheData.getStationInMaintenance(anyString()))
            .thenReturn(MaintenanceStation.builder().isStandin(false)
                .startDate(OffsetDateTime.now().minusHours(1)).build());
      }
      PaymentsModelResponse response = new PaymentsModelResponse();
      response.setServiceType(reason.equals("PAYMENT_NOT_ACA") ? "GPD" : "ACA");
      response.setPayStandIn(!reason.equals("PAYMENT_STAND_IN_DISABLED"));
      lenient().when(gpd.getPaymentOption(anyString(), anyString())).thenReturn(response);
      assertEquals(PaaErrorEnum.PAA_PAGAMENTO_SCONOSCIUTO,
          assertThrows(PartnerValidationException.class,
              () -> service.paVerifyPaymentNotice(PaVerifyPaymentNoticeReqMock.getMock(), "ACA")).getError());
      List<ILoggingEvent> rejections = logs.list.stream()
          .filter(e -> "PAYMENT_ELIGIBILITY_REJECTED".equals(e.getMDCPropertyMap().get("event.code")))
          .toList();
      assertEquals(1, rejections.size());
      assertEquals(Level.INFO, rejections.get(0).getLevel());
      assertTrue(rejections.get(0).getMDCPropertyMap().get("ctx_details").contains(reason));
      assertTrue(errors().isEmpty());
    }
  }

  @Test
  void unavailableCacheIsLocalTechnicalFailureNotHttp() throws Exception {
    try (MockedStatic<ConfigCacheData> cache = mockStatic(ConfigCacheData.class);
         OperationLogScope scope = OperationLogScope.open("request")) {
      cache.when(ConfigCacheData::isConfigDataNull).thenReturn(true);
      PartnerValidationException converted = assertThrows(PartnerValidationException.class,
          () -> service.paVerifyPaymentNotice(PaVerifyPaymentNoticeReqMock.getMock(), "ACA"));
      assertEquals(PaaErrorEnum.PAA_SYSTEM_ERROR, converted.getError());
      assertTrue(errors().isEmpty());
      complete(converted);
      assertLocalFailure();
      verifyNoInteractions(gpd);
    }
  }

  @Test
  void actualRestTemplate404RemainsSystemFaultWithVerticalAttribution() throws Exception {
    when(vertical.getUrlByServiceId(anyString())).thenReturn("https://vertical.invalid");
    when(rest.exchange(anyString(), eq(HttpMethod.POST), any(), eq(JAXBElement.class)))
        .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));
    try (OperationLogScope scope = OperationLogScope.open("request")) {
      PartnerValidationException converted = assertThrows(PartnerValidationException.class,
          () -> service.paDemandPaymentNotice(PaDemandNoticePaymentReqMock.getMock()));
      assertEquals(PaaErrorEnum.PAA_SYSTEM_ERROR, converted.getError());
      assertTrue(errors().isEmpty());
      complete(converted);
      assertDependency("vertical-service", "demandPaymentNotice", 404);
      ArgumentCaptor<HttpEntity> sent = ArgumentCaptor.forClass(HttpEntity.class);
      verify(rest).exchange(anyString(), eq(HttpMethod.POST), sent.capture(), eq(JAXBElement.class));
      assertNull(sent.getValue().getHeaders().getFirst("X-Request-Id"),
          "Only the configured HTTP interceptor propagates correlation");
    }
  }

  @Test
  void verticalFeign404RetainsLegacyBusinessFault() throws Exception {
    when(vertical.getUrlByServiceId(anyString())).thenReturn("https://vertical.invalid");
    when(rest.exchange(anyString(), eq(HttpMethod.POST), any(), eq(JAXBElement.class)))
        .thenThrow(feign(404));
    try (OperationLogScope ignored = OperationLogScope.open("request")) {
      assertEquals(PaaErrorEnum.PAA_PAGAMENTO_SCONOSCIUTO,
          assertThrows(PartnerValidationException.class,
              () -> service.paDemandPaymentNotice(PaDemandNoticePaymentReqMock.getMock())).getError());
      assertTrue(errors().isEmpty());
    }
  }

  @Test
  void configuredVerticalCallPropagatesRequestIdAndRetainsActualHttp404Fault() {
    when(vertical.getUrlByServiceId(anyString())).thenReturn("https://vertical.invalid");
    RestTemplate configured = new RestTemplateConfig().restTemplate();
    MockRestServiceServer server = MockRestServiceServer.bindTo(configured).build();
    server.expect(requestTo("https://vertical.invalid"))
        .andExpect(header("X-Request-Id", "request"))
        .andRespond(withStatus(HttpStatus.NOT_FOUND));
    PartnerService actual = new PartnerService(30L, List.of(), List.of(), new ObjectFactory(),
        gpd, table, queue, null, vertical, configured);
    try (OperationLogScope scope = OperationLogScope.open("request")) {
      PartnerValidationException converted = assertThrows(PartnerValidationException.class,
          () -> actual.paDemandPaymentNotice(PaDemandNoticePaymentReqMock.getMock()));
      assertEquals(PaaErrorEnum.PAA_SYSTEM_ERROR, converted.getError());
      assertTrue(errors().isEmpty());
      complete(converted);
      assertDependency("vertical-service", "demandPaymentNotice", 404);
      server.verify();
    }
  }

  @Test
  void verticalPreparationFailureIsLocalNotDependencyFailure() throws Exception {
    when(vertical.getUrlByServiceId(anyString())).thenThrow(new IllegalStateException("configuration"));
    try (OperationLogScope ignored = OperationLogScope.open("request")) {
      var converted = assertThrows(PartnerValidationException.class,
          () -> service.paDemandPaymentNotice(PaDemandNoticePaymentReqMock.getMock()));
      assertTrue(errors().isEmpty());
      complete(converted);
      assertLocalFailure();
      verifyNoInteractions(rest);
    }
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"http://["})
  void invalidVerticalUrlIsLocalWithRealRestTemplate(String url) {
    when(vertical.getUrlByServiceId(anyString())).thenReturn(url);
    PartnerService actual = new PartnerService(30L, List.of(), List.of(), new ObjectFactory(),
        gpd, table, queue, null, vertical, new RestTemplate());
    try (OperationLogScope scope = OperationLogScope.open("request")) {
      PartnerValidationException converted = assertThrows(PartnerValidationException.class,
          () -> actual.paDemandPaymentNotice(PaDemandNoticePaymentReqMock.getMock()));
      assertEquals(PaaErrorEnum.PAA_SYSTEM_ERROR, converted.getError());
      assertTrue(errors().isEmpty());
      complete(converted);
      assertLocalFailure();
    }
  }

  @Test
  void verticalResponseMappingFailureIsLocalAndUnscopedCallDoesNotCreateRequestId() {
    when(vertical.getUrlByServiceId(anyString())).thenReturn("https://vertical.invalid");
    when(rest.exchange(anyString(), eq(HttpMethod.POST), any(), eq(JAXBElement.class)))
        .thenReturn(ResponseEntity.ok().build());
    var converted = assertThrows(PartnerValidationException.class,
        () -> service.paDemandPaymentNotice(PaDemandNoticePaymentReqMock.getMock()));
    assertEquals(PaaErrorEnum.PAA_SYSTEM_ERROR, converted.getError());
    assertTrue(errors().isEmpty());
    complete(converted);
    assertLocalFailure();
    ArgumentCaptor<HttpEntity> sent = ArgumentCaptor.forClass(HttpEntity.class);
    verify(rest).exchange(anyString(), eq(HttpMethod.POST), sent.capture(), eq(JAXBElement.class));
    assertNull(sent.getValue().getHeaders().getFirst("X-Request-Id"));
    assertNull(MDC.get("requestId"));
    assertNull(MDC.get("operationId"));
  }

  private PaymentOptionModelResponse sendDirect() throws Exception {
    return service.getReceiptPaymentOptionScheduler("3123", "77777777777", "123", false,
        new PaymentOptionModel(), new ReceiptEntity("77777777777", "123"));
  }

  private void complete(Throwable failure) {
    Logger boundary = (Logger) LoggerFactory.getLogger(SoapMessageDispatcher.class);
    boundary.addAppender(logs);
    try {
      FailureDiagnostics.technicalFailure(new LogEventWriter(boundary), failure);
    } finally {
      boundary.detachAppender(logs);
    }
  }

  private MockHttpServletResponse sendThroughSoap(Object receiptRequest, boolean v2) {
    return runThroughSoap(v2 ? "paSendRTV2" : "paSendRT", () -> sendReceipt(receiptRequest, v2));
  }

  @FunctionalInterface
  private interface SoapInvocation {
    void run() throws Exception;
  }

  private MockHttpServletResponse runThroughSoap(String action, SoapInvocation invocation) {
    class ReceiptDispatcher extends SoapMessageDispatcher {
      ReceiptDispatcher() {
        super(new ObjectFactory(), "intermediario");
      }

      @Override
      protected void callService(javax.servlet.http.HttpServletRequest request,
          javax.servlet.http.HttpServletResponse response) throws Exception {
        invocation.run();
      }

      void dispatch(MockHttpServletRequest request, MockHttpServletResponse response) {
        doService(request, response);
      }
    }
    Logger soapLogger = (Logger) LoggerFactory.getLogger(SoapMessageDispatcher.class);
    Level previous = soapLogger.getLevel();
    soapLogger.setLevel(Level.INFO);
    soapLogger.addAppender(logs);
    try {
      MockHttpServletRequest request = new MockHttpServletRequest();
      request.addHeader("SOAPAction", action);
      MockHttpServletResponse response = new MockHttpServletResponse();
      new ReceiptDispatcher().dispatch(request, response);
      return response;
    } finally {
      soapLogger.detachAppender(logs);
      soapLogger.setLevel(previous);
    }
  }

  private void sendReceipt(Object request, boolean v2) {
    if (v2) {
      service.paSendRTV2((it.gov.pagopa.payments.model.partner.PaSendRTV2Request) request);
    } else {
      service.paSendRT((it.gov.pagopa.payments.model.partner.PaSendRTReq) request);
    }
  }

  private Exception receiptFailure(String type) {
    return switch (type) {
      case "feign" -> feign(500);
      case "retryable" -> new RetryableException(-1, "connection failed",
          Request.HttpMethod.POST, (java.util.Date) null,
          Request.create(Request.HttpMethod.POST, "https://gpd.invalid",
              Map.of(), null, StandardCharsets.UTF_8, null));
      case "storage" -> new StorageException("InternalError", "legacy storage failure",
          503, null, null);
      default -> throw new IllegalArgumentException(type);
    };
  }

  private List<ILoggingEvent> events(String code) {
    return logs.list.stream()
        .filter(event -> code.equals(event.getMDCPropertyMap().get("event.code"))).toList();
  }

  private PaymentOptionModelResponse paid() {
    PaymentOptionModelResponse response = new PaymentOptionModelResponse();
    response.setStatus(PaymentOptionStatus.PO_PAID);
    return response;
  }

  private TableServiceException tableFailure(TableErrorCode code) {
    HttpResponse response = mock(HttpResponse.class);
    when(response.getStatusCode()).thenReturn(503);
    return new TableServiceException("storage failure", response,
        new TableServiceError(code.toString(), "storage failure"));
  }

  private FeignException feign(int status) {
    Request request = Request.create(Request.HttpMethod.POST, "https://gpd.invalid",
        Map.of(), null, StandardCharsets.UTF_8, null);
    return FeignException.errorStatus("gpd", Response.builder()
        .request(request).status(status).headers(Map.of()).build());
  }

  private List<ILoggingEvent> errors() {
    return logs.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
  }

  private void assertDependency(String dependency, String operation, Integer httpCode) {
    assertEquals(1, errors().size());
    ILoggingEvent event = errors().get(0);
    assertEquals("DEPENDENCY_CALL_FAILED", event.getMDCPropertyMap().get("event.code"));
    String details = event.getMDCPropertyMap().get("ctx_details");
    assertTrue(details.contains("\"dependency\":\"" + dependency + "\""), details);
    assertTrue(details.contains("\"dependencyOperation\":\"" + operation + "\""), details);
    assertTrue(details.contains("\"dependencyOutcome\":\"KO\""), details);
    assertTrue(details.contains("\"durationMs\":"), details);
    if (httpCode != null) {
      assertTrue(details.contains("\"dependencyHttpCode\":" + httpCode), details);
    }
    assertNotNull(event.getMDCPropertyMap().get("error.type"));
    assertNull(event.getThrowableProxy());
  }

  private void assertLocalFailure() {
    assertEquals(1, errors().size());
    assertEquals("OPERATION_TECHNICAL_FAILED", errors().get(0).getMDCPropertyMap().get("event.code"));
    assertNull(errors().get(0).getMDCPropertyMap().get("ctx_details"));
    assertNull(errors().get(0).getThrowableProxy());
  }
}
