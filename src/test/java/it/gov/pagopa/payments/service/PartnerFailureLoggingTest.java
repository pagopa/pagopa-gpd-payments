package it.gov.pagopa.payments.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.azure.data.tables.TableClient;
import com.azure.storage.queue.QueueClient;
import com.azure.data.tables.models.TableServiceException;
import com.azure.data.tables.models.TableErrorCode;
import feign.FeignException;
import it.gov.pagopa.payments.client.GpdClient;
import it.gov.pagopa.payments.endpoints.validation.exceptions.PartnerValidationException;
import it.gov.pagopa.payments.logging.ApplicationLogCapture;
import it.gov.pagopa.payments.mock.PaSendRTReqMock;
import it.gov.pagopa.payments.model.PaaErrorEnum;
import it.gov.pagopa.payments.model.PaymentOptionModel;
import it.gov.pagopa.payments.model.PaymentOptionModelResponse;
import it.gov.pagopa.payments.model.PaymentOptionStatus;
import it.gov.pagopa.payments.model.partner.ObjectFactory;
import it.gov.pagopa.payments.model.partner.StOutcome;
import java.util.List;
import java.util.Map;
import javax.xml.datatype.DatatypeConfigurationException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class PartnerFailureLoggingTest {
  private final GpdClient gpd = mock(GpdClient.class);
  private final TableClient tables = mock(TableClient.class);

  private PartnerService service(List<String> suppressed) {
    return new PartnerService(1L, List.of("77777777777_01"), suppressed,
        new ObjectFactory(), gpd, tables, mock(QueueClient.class),
        null, null, null);
  }

  @Test
  void convertedTechnicalFailureRetainsCauseAndIsNotDiagnosedBeforeTheBoundary()
      throws DatatypeConfigurationException {
    var cause = new IllegalStateException("SECRET dependency response");
    when(gpd.sendPaymentOptionReceipt(anyString(), anyString(), any(PaymentOptionModel.class)))
        .thenThrow(cause);
    PartnerService partner = service(List.of());
    var request = PaSendRTReqMock.getMockV2("001234");
    try (ApplicationLogCapture logs = new ApplicationLogCapture()) {
      var failure = assertThrows(PartnerValidationException.class,
          () -> partner.paSendRTV2(request));
      assertSame(cause, failure.getCause());
      assertEquals(PaaErrorEnum.PAA_SYSTEM_ERROR, failure.getError());
      assertTrue(logs.events.isEmpty());
    }
  }

  @Test
  void suppressedTechnicalFailureIsDiagnosedOnceButTheActualResponseRemainsOk() throws Exception {
    when(gpd.sendPaymentOptionReceipt(anyString(), anyString(), any(PaymentOptionModel.class)))
        .thenThrow(new IllegalStateException("SECRET dependency response"));
    Map<String, String> previous = MDC.getCopyOfContextMap();
    try (ApplicationLogCapture logs = new ApplicationLogCapture()) {
      MDC.put("status", "OK");
      var response = service(List.of("PAA_SYSTEM_ERROR"))
          .paSendRTV2(PaSendRTReqMock.getMockV2("001234"));
      assertEquals(StOutcome.OK, response.getOutcome());
      assertEquals("OK", MDC.get("status"));
      assertEquals(1, logs.events.stream()
          .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.ERROR).count());
      for (var event : logs.events) {
        assertFalse(logs.encodedEvent(event).toString().contains("SECRET"));
        assertFalse(logs.encodedEvent(event).has("ctx_soap_fault_code"));
      }
    } finally {
      if (previous == null) MDC.clear();
      else MDC.setContextMap(previous);
    }
  }

  @Test
  void concurrentDuplicateDuringReceiptRecoveryIsNotDiagnosedAsATechnicalFailure()
      throws DatatypeConfigurationException {
    when(gpd.sendPaymentOptionReceipt(anyString(), anyString(), any(PaymentOptionModel.class)))
        .thenThrow(mock(FeignException.Conflict.class));
    var missing = mock(TableServiceException.class, RETURNS_DEEP_STUBS);
    when(missing.getValue().getErrorCode()).thenReturn(TableErrorCode.RESOURCE_NOT_FOUND);
    when(tables.getEntity(anyString(), anyString())).thenThrow(missing);
    var duplicate = mock(TableServiceException.class, RETURNS_DEEP_STUBS);
    when(duplicate.getValue().getErrorCode()).thenReturn(TableErrorCode.ENTITY_ALREADY_EXISTS);
    doThrow(duplicate).when(tables).createEntity(any());
    PartnerService partner = service(List.of());
    var request = PaSendRTReqMock.getMockV2("001234");
    try (ApplicationLogCapture logs = new ApplicationLogCapture()) {
      var rejection = assertThrows(PartnerValidationException.class,
          () -> partner.paSendRTV2(request));
      assertEquals(PaaErrorEnum.PAA_RECEIPT_DUPLICATA, rejection.getError());
      assertTrue(logs.events.isEmpty());
      verify(tables).createEntity(any());
    }
  }

  @Test
  void suppressedBusinessRejectionDoesNotBecomeATechnicalError() throws Exception {
    var option = new PaymentOptionModelResponse();
    option.setStatus(PaymentOptionStatus.PO_UNPAID);
    when(gpd.sendPaymentOptionReceipt(anyString(), anyString(), any(PaymentOptionModel.class)))
        .thenReturn(option);
    try (ApplicationLogCapture logs = new ApplicationLogCapture()) {
      assertEquals(StOutcome.OK, service(List.of("PAA_SEMANTICA"))
          .paSendRTV2(PaSendRTReqMock.getMockV2("001234")).getOutcome());
      assertEquals(1, logs.events.size());
      assertEquals("PAA_SEMANTICA",
          logs.encodedEvent(logs.events.get(0)).path("ctx_details")
              .path("suppressed_fault_code").asText());
      assertEquals(ch.qos.logback.classic.Level.WARN, logs.events.get(0).getLevel());
    }
  }
}
