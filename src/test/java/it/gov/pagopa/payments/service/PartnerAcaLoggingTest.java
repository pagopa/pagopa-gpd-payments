package it.gov.pagopa.payments.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import it.gov.pagopa.payments.client.GpdClient;
import it.gov.pagopa.payments.endpoints.validation.exceptions.PartnerValidationException;
import it.gov.pagopa.payments.logging.ApplicationLogCapture;
import it.gov.pagopa.payments.mock.MockUtil;
import it.gov.pagopa.payments.mock.PaVerifyPaymentNoticeReqMock;
import it.gov.pagopa.payments.model.PaaErrorEnum;
import it.gov.pagopa.payments.model.PaymentsModelResponse;
import it.gov.pagopa.payments.model.client.cache.ConfigCacheData;
import it.gov.pagopa.payments.model.client.cache.MaintenanceStation;
import it.gov.pagopa.payments.model.partner.ObjectFactory;
import it.gov.pagopa.payments.model.partner.StOutcome;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

class PartnerAcaLoggingTest {

  private static final String ACA_ENTRY =
      "[ACA] Debt position required to be handled in Stand-In mode";

  private final GpdClient gpd = mock(GpdClient.class);
  private final PartnerService service = new PartnerService(
      null, List.of(), List.of(), new ObjectFactory(), gpd, null, null, null, null, null);

  @ParameterizedTest
  @EnumSource(Rejection.class)
  void everyAcaRejectionHasADistinctWarningAndPreservesItsFault(Rejection rejection)
      throws Exception {
    PaymentsModelResponse payment = acaPayment();
    when(gpd.getPaymentOption(anyString(), anyString())).thenReturn(payment);
    try (MockedStatic<ConfigCacheData> cache = mockStatic(ConfigCacheData.class);
        ApplicationLogCapture logs = new ApplicationLogCapture()) {
      stubEnabledStation(cache);
      switch (rejection) {
        case CACHE_UNAVAILABLE -> cache.when(ConfigCacheData::isConfigDataNull).thenReturn(true);
        case MAINTENANCE_WITHOUT_STANDIN -> cache.when(
            () -> ConfigCacheData.getStationInMaintenance(anyString()))
            .thenReturn(MaintenanceStation.builder()
                .isStandin(false).startDate(OffsetDateTime.now().minusDays(1)).build());
        case STATION_MISSING -> cache.when(
            () -> ConfigCacheData.getCreditorInstitutionStation(anyString(), anyString()))
            .thenReturn(null);
        case STATION_NOT_ACA -> cache.when(
            () -> ConfigCacheData.getCreditorInstitutionStation(anyString(), anyString()))
            .thenReturn(ConfigCacheData.StationCI.builder().aca(false).standin(true).build());
        case PAYMENT_MISSING -> when(gpd.getPaymentOption(anyString(), anyString())).thenReturn(null);
        case PAYMENT_NOT_ACA -> payment.setServiceType("GPD");
        case STATION_NOT_STANDIN -> cache.when(
            () -> ConfigCacheData.getCreditorInstitutionStation(anyString(), anyString()))
            .thenReturn(ConfigCacheData.StationCI.builder().aca(true).standin(false).build());
        case PAYMENT_NOT_STANDIN -> payment.setPayStandIn(false);
      }

      PartnerValidationException exception = assertThrows(PartnerValidationException.class,
          () -> service.paVerifyPaymentNotice(PaVerifyPaymentNoticeReqMock.getMock(), "ACA"));

      assertEquals(rejection == Rejection.CACHE_UNAVAILABLE
          ? PaaErrorEnum.PAA_SYSTEM_ERROR : PaaErrorEnum.PAA_PAGAMENTO_SCONOSCIUTO,
          exception.getError());
      assertEquals(List.of(ACA_ENTRY, rejection.message),
          logs.events.stream().map(event -> event.getFormattedMessage()).toList());
      logs.events.forEach(event -> assertEquals(Level.WARN, event.getLevel()));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"ACA", "aca"})
  void successfulAcaRequestsKeepTheEntryWarningWithoutReportingRejection(String serviceType)
      throws Exception {
    when(gpd.getPaymentOption(anyString(), anyString())).thenReturn(acaPayment());
    try (MockedStatic<ConfigCacheData> cache = mockStatic(ConfigCacheData.class);
        ApplicationLogCapture logs = new ApplicationLogCapture()) {
      stubEnabledStation(cache);

      assertEquals(StOutcome.OK,
          service.paVerifyPaymentNotice(PaVerifyPaymentNoticeReqMock.getMock(), serviceType)
              .getOutcome());

      assertEquals(List.of(ACA_ENTRY),
          logs.events.stream().map(event -> event.getFormattedMessage()).toList());
      assertEquals(Level.WARN, logs.events.get(0).getLevel());
    }
  }

  @Test
  void nonAcaRequestsDoNotEmitAcaWarningsOrAccessTheConfigurationCache() throws Exception {
    when(gpd.getPaymentOption(anyString(), anyString())).thenReturn(acaPayment());
    try (MockedStatic<ConfigCacheData> cache = mockStatic(ConfigCacheData.class);
        ApplicationLogCapture logs = new ApplicationLogCapture()) {
      assertEquals(StOutcome.OK,
          service.paVerifyPaymentNotice(PaVerifyPaymentNoticeReqMock.getMock(), "GPD").getOutcome());

      assertEquals(0, logs.events.size());
      cache.verifyNoInteractions();
    }
  }

  private void stubEnabledStation(MockedStatic<ConfigCacheData> cache) {
    cache.when(() -> ConfigCacheData.getCreditorInstitutionStation(anyString(), anyString()))
        .thenReturn(ConfigCacheData.StationCI.builder().aca(true).standin(true).build());
  }

  private PaymentsModelResponse acaPayment() throws Exception {
    PaymentsModelResponse payment = MockUtil.readModelFromFile(
        "gpd/getPaymentOption_PO_UNPAID.json", PaymentsModelResponse.class);
    payment.setServiceType("ACA");
    payment.setPayStandIn(true);
    return payment;
  }

  private enum Rejection {
    CACHE_UNAVAILABLE("[ACA] Checks cannot be made: cache values were not properly set"),
    MAINTENANCE_WITHOUT_STANDIN("[ACA] Station under maintenance but Stand-In mode not enabled"),
    STATION_MISSING("[ACA] Station not enabled for ACA payments for this creditor institution"),
    STATION_NOT_ACA("[ACA] Station not enabled for ACA payments for this creditor institution"),
    PAYMENT_MISSING("[ACA] Payment not generated by ACA service"),
    PAYMENT_NOT_ACA("[ACA] Payment not generated by ACA service"),
    STATION_NOT_STANDIN(
        "[ACA] Station not enabled for Stand-In mode for this creditor institution"),
    PAYMENT_NOT_STANDIN("[ACA] Debt position cannot be paid in Stand-In mode");

    private final String message;

    Rejection(String message) {
      this.message = message;
    }
  }
}
