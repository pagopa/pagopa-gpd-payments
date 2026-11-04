package it.gov.pagopa.payments.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.azure.data.tables.TableClient;
import com.azure.data.tables.models.TableEntity;
import com.azure.data.tables.models.TableErrorCode;
import com.azure.data.tables.models.TableServiceException;
import com.azure.storage.queue.QueueClient;
import feign.FeignException;
import feign.Request;
import feign.RetryableException;
import it.gov.pagopa.payments.client.GpdClient;
import it.gov.pagopa.payments.config.LogCapture;
import it.gov.pagopa.payments.config.VerticalServicesConfig;
import it.gov.pagopa.payments.endpoints.validation.exceptions.PartnerValidationException;
import it.gov.pagopa.payments.exception.AppException;
import it.gov.pagopa.payments.mock.PaGetPaymentReqMock;
import it.gov.pagopa.payments.mock.PaSendRTReqMock;
import it.gov.pagopa.payments.mock.PaVerifyPaymentNoticeReqMock;
import it.gov.pagopa.payments.model.PaaErrorEnum;
import it.gov.pagopa.payments.model.PaymentOptionModelResponse;
import it.gov.pagopa.payments.model.PaymentOptionStatus;
import it.gov.pagopa.payments.model.partner.ObjectFactory;
import it.gov.pagopa.payments.model.partner.PaDemandPaymentNoticeRequest;
import it.gov.pagopa.payments.model.partner.PaSendRTReq;
import it.gov.pagopa.payments.model.partner.PaSendRTV2Request;
import it.gov.pagopa.payments.model.partner.StOutcome;
import java.util.Date;
import java.util.List;
import java.util.Map;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PartnerServiceExceptionTest {
  private final GpdClient gpd = mock(GpdClient.class);
  private final TableClient tables = mock(TableClient.class);
  private final QueueClient queue = mock(QueueClient.class);
  private final VerticalServicesConfig vertical = mock(VerticalServicesConfig.class);
  private PartnerService service;

  @BeforeEach
  void setUp() {
    service = service(List.of(), List.of());
  }

  private PartnerService service(List<String> stations, List<String> suppressed) {
    return new PartnerService(120L, stations, suppressed, new ObjectFactory(), gpd, tables,
        queue, null, vertical, null);
  }

  @ParameterizedTest
  @ValueSource(strings = {"verify", "get", "get-v2"})
  void paymentLookupPreservesTechnicalCauseWithoutLocalDiagnostic(String operation) throws Exception {
    var cause = new IllegalStateException("GPD unavailable");
    when(gpd.getPaymentOption(anyString(), anyString())).thenThrow(cause);
    try (var logs = new LogCapture(PartnerService.class)) {
      var failure = assertThrows(PartnerValidationException.class, () -> {
        switch (operation) {
          case "verify" -> service.paVerifyPaymentNotice(PaVerifyPaymentNoticeReqMock.getMock(), "GPD");
          case "get" -> service.paGetPayment(PaGetPaymentReqMock.getMock(), "GPD");
          case "get-v2" -> service.paGetPaymentV2(PaGetPaymentReqMock.getMockV2(), "GPD");
          default -> throw new AssertionError(operation);
        }
      });
      assertSame(cause, failure.getCause());
      assertEquals(PaaErrorEnum.PAA_SYSTEM_ERROR, failure.getError());
      assertNoErrorLogs(logs);
    }
  }

  @Test
  void verticalFailurePreservesCauseWithoutLocalDiagnostic() {
    var cause = new IllegalStateException("vertical unavailable");
    var request = new PaDemandPaymentNoticeRequest();
    request.setIdServizio("vertical");
    when(vertical.getUrlByServiceId("vertical")).thenThrow(cause);

    try (var logs = new LogCapture(PartnerService.class)) {
      var failure = assertThrows(PartnerValidationException.class,
          () -> service.paDemandPaymentNotice(request));
      assertSame(cause, failure.getCause());
      assertEquals(PaaErrorEnum.PAA_SYSTEM_ERROR, failure.getError());
      assertNoErrorLogs(logs);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void receiptStoragePreservesCauseAndExistingQueueBehavior(boolean v2) throws Exception {
    var payment = new PaymentOptionModelResponse();
    payment.setStatus(PaymentOptionStatus.PO_PAID);
    when(gpd.sendPaymentOptionReceipt(anyString(), anyString(), any())).thenReturn(payment);
    var cause = mock(TableServiceException.class, RETURNS_DEEP_STUBS);
    when(cause.getValue().getErrorCode()).thenReturn(TableErrorCode.fromString("InternalError"));
    doThrow(cause).when(tables).createEntity(any(TableEntity.class));

    try (var logs = new LogCapture(PartnerService.class)) {
      var failure = assertThrows(PartnerValidationException.class, () -> sendReceipt(v2));
      assertEquals(PaaErrorEnum.PAA_SYSTEM_ERROR, failure.getError());
      assertEquals(AppException.class, failure.getCause().getClass());
      assertSame(cause, failure.getCause().getCause());
      assertNoErrorLogs(logs);
    }
    verifyNoInteractions(queue);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void marshallingFailurePreservesCauseWithoutLocalDiagnostic(boolean v2) throws Exception {
    var cause = new JAXBException("marshalling failed");
    try (var jaxb = mockStatic(JAXBContext.class);
        var logs = new LogCapture(PartnerService.class)) {
      jaxb.when(() -> JAXBContext.newInstance(v2 ? PaSendRTV2Request.class : PaSendRTReq.class))
          .thenThrow(cause);

      var failure = assertThrows(PartnerValidationException.class, () -> sendReceipt(v2));
      assertSame(cause, failure.getCause());
      assertEquals(PaaErrorEnum.PAA_SYSTEM_ERROR, failure.getError());
      assertNoErrorLogs(logs);
    }
    verifyNoInteractions(gpd, queue, tables);
  }

  @Test
  void recoveredStorageFailureAfterConflictKeepsOneLocalDiagnostic() throws Exception {
    when(gpd.sendPaymentOptionReceipt(anyString(), anyString(), any()))
        .thenThrow(mock(FeignException.Conflict.class));
    var cause = mock(TableServiceException.class, RETURNS_DEEP_STUBS);
    when(cause.getCause()).thenReturn(null);
    when(cause.getSuppressed()).thenReturn(new Throwable[0]);
    when(cause.getStackTrace()).thenReturn(new StackTraceElement[0]);
    when(cause.getValue().getErrorCode()).thenReturn(TableErrorCode.fromString("InternalError"));
    when(tables.getEntity(anyString(), anyString())).thenThrow(cause);

    try (var logs = new LogCapture(PartnerService.class)) {
      var failure = assertThrows(PartnerValidationException.class, () -> sendReceipt(false));
      assertEquals(PaaErrorEnum.PAA_RECEIPT_DUPLICATA, failure.getError());
      assertNull(failure.getCause());
      assertEquals(1, logs.events().stream()
          .filter(event -> "ERROR".equals(event.path("log.level").asText())).count());
    }
    verifyNoInteractions(queue);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void queuedFeignFailurePreservesCauseAndFault(boolean retryable) throws Exception {
    Request request = Request.create(Request.HttpMethod.POST, "http://localhost", Map.of(),
        null, java.nio.charset.StandardCharsets.UTF_8, null);
    FeignException cause = retryable
        ? new RetryableException(503, "unavailable", Request.HttpMethod.POST, (Date) null, request)
        : new FeignException.BadRequest("invalid request", request, null, Map.of());
    when(gpd.sendPaymentOptionReceipt(anyString(), anyString(), any())).thenThrow(cause);

    try (var logs = new LogCapture(PartnerService.class)) {
      var failure = assertThrows(PartnerValidationException.class, () -> sendReceipt(false));
      assertSame(cause, failure.getCause());
      assertEquals(retryable ? PaaErrorEnum.PAA_SYSTEM_ERROR : PaaErrorEnum.PAA_SEMANTICA,
          failure.getError());
      assertNoErrorLogs(logs);
    }
    verify(queue).sendMessageWithResponse(anyString(), any(), any(), any(), any());
    verifyNoInteractions(tables);
  }

  @Test
  void expectedBusinessFaultHasNoTechnicalCause() throws Exception {
    var cause = mock(FeignException.NotFound.class);
    when(gpd.sendPaymentOptionReceipt(anyString(), anyString(), any())).thenThrow(cause);

    try (var logs = new LogCapture(PartnerService.class)) {
      var failure = assertThrows(PartnerValidationException.class, () -> sendReceipt(false));
      assertEquals(PaaErrorEnum.PAA_PAGAMENTO_SCONOSCIUTO, failure.getError());
      assertNull(failure.getCause());
      assertNoErrorLogs(logs);
    }
    verifyNoInteractions(queue);
  }

  @Test
  void suppressedTechnicalFailureIsLoggedOnceAtItsConsumptionBoundary() throws Exception {
    var request = PaSendRTReqMock.getMockV2("123");
    service = service(List.of(request.getIdStation()), List.of("PAA_SYSTEM_ERROR"));
    when(gpd.sendPaymentOptionReceipt(anyString(), anyString(), any()))
        .thenThrow(new IllegalStateException("GPD unavailable"));

    try (var logs = new LogCapture(PartnerService.class)) {
      assertEquals(StOutcome.OK, service.paSendRTV2(request).getOutcome());
      assertEquals(1, logs.events().stream()
          .filter(event -> "ERROR".equals(event.path("log.level").asText())).count());
    }
    verify(tables, never()).createEntity(any(TableEntity.class));
  }

  private void sendReceipt(boolean v2) throws Exception {
    if (v2) {
      service.paSendRTV2(PaSendRTReqMock.getMockV2("123"));
    } else {
      service.paSendRT(PaSendRTReqMock.getMock("123"));
    }
  }

  private static void assertNoErrorLogs(LogCapture logs) {
    assertEquals(0, logs.events().stream()
        .filter(event -> "ERROR".equals(event.path("log.level").asText())).count());
  }
}
