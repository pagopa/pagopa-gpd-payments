package it.gov.pagopa.payments.service;

import feign.FeignException;
import it.gov.pagopa.payments.client.GpdClient;
import it.gov.pagopa.payments.endpoints.validation.exceptions.PartnerValidationException;
import it.gov.pagopa.payments.mock.MockUtil;
import it.gov.pagopa.payments.mock.PaVerifyPaymentNoticeReqMock;
import it.gov.pagopa.payments.model.PaaErrorEnum;
import it.gov.pagopa.payments.model.PaymentsModelResponse;
import it.gov.pagopa.payments.model.client.cache.ConfigCacheData;
import it.gov.pagopa.payments.model.client.cache.MaintenanceStation;
import it.gov.pagopa.payments.model.partner.*;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.SpringBootTest;

import javax.xml.datatype.DatatypeConfigurationException;
import java.io.IOException;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Slf4j
@SpringBootTest
class PartnerServiceAcaTest {

    @InjectMocks
    private PartnerService partnerService;

    @Mock
    private ObjectFactory factory;

    @Mock private GpdClient gpdClient;

    private final ObjectFactory factoryUtil = new ObjectFactory();

    @Test
    void paVerifyPaymentNoticeTestACA() throws DatatypeConfigurationException, IOException {

        // Test preconditions
        PaVerifyPaymentNoticeReq requestBody = PaVerifyPaymentNoticeReqMock.getMock();

        when(factory.createPaVerifyPaymentNoticeRes())
                .thenReturn(factoryUtil.createPaVerifyPaymentNoticeRes());
        when(factory.createCtPaymentOptionDescriptionPA())
                .thenReturn(factoryUtil.createCtPaymentOptionDescriptionPA());
        when(factory.createCtPaymentOptionsDescriptionListPA())
                .thenReturn(factoryUtil.createCtPaymentOptionsDescriptionListPA());

        PaymentsModelResponse paymentModel =
                MockUtil.readModelFromFile(
                        "gpd/getPaymentOption_PO_UNPAID.json", PaymentsModelResponse.class);
        paymentModel.setServiceType("ACA");
        paymentModel.setPayStandIn(true);
        when(gpdClient.getPaymentOption(anyString(), anyString())).thenReturn(paymentModel);

        try (MockedStatic<ConfigCacheData> mockedConfigData = mockStatic(ConfigCacheData.class)) {

            MaintenanceStation stationMaintenance = MaintenanceStation.builder().isStandin(true).build();
            mockedConfigData.when(() -> ConfigCacheData.getStationInMaintenance(anyString())).thenReturn(stationMaintenance);
            ConfigCacheData.StationCI stationCI = ConfigCacheData.StationCI.builder().aca(true).standin(true).build();
            mockedConfigData.when(() -> ConfigCacheData.getCreditorInstitutionStation(anyString(), anyString())).thenReturn(stationCI);

            // Test execution
            PaVerifyPaymentNoticeRes responseBody = partnerService.paVerifyPaymentNotice(requestBody, "ACA");

            // Test post condition
            assertThat(responseBody.getOutcome()).isEqualTo(StOutcome.OK);
            assertThat(responseBody.getPaymentList().getPaymentOptionDescription().isAllCCP()).isFalse();
            assertThat(responseBody.getPaymentList().getPaymentOptionDescription().getAmount())
                    .isEqualTo(new BigDecimal(1055));
            assertThat(responseBody.getPaymentList().getPaymentOptionDescription().getOptions())
                    .isEqualTo(StAmountOption.EQ); // de-scoping
            assertThat(responseBody.getFiscalCodePA()).isEqualTo("77777777777");
            assertThat(responseBody.getPaymentDescription()).isEqualTo("string");
        }
    }

    @Test
    void paVerifyPaymentTestACAKONotFound() throws DatatypeConfigurationException {

        // Test preconditions
        PaVerifyPaymentNoticeReq requestBody = PaVerifyPaymentNoticeReqMock.getMock();

        var e = Mockito.mock(FeignException.NotFound.class);
        lenient().when(e.getSuppressed()).thenReturn(new Throwable[0]);
        when(gpdClient.getPaymentOption(anyString(), anyString())).thenThrow(e);

        try (MockedStatic<ConfigCacheData> mockedConfigData = mockStatic(ConfigCacheData.class)) {

            MaintenanceStation stationMaintenance = MaintenanceStation.builder().isStandin(true).build();
            mockedConfigData.when(() -> ConfigCacheData.getStationInMaintenance(anyString())).thenReturn(stationMaintenance);
            ConfigCacheData.StationCI stationCI = ConfigCacheData.StationCI.builder().aca(true).standin(true).build();
            mockedConfigData.when(() -> ConfigCacheData.getCreditorInstitutionStation(anyString(), anyString())).thenReturn(stationCI);

            try {
                // Test execution
                partnerService.paVerifyPaymentNotice(requestBody, "ACA");
                fail();
            } catch (PartnerValidationException ex) {
                // Test post condition
                assertEquals(PaaErrorEnum.PAA_PAGAMENTO_SCONOSCIUTO, ex.getError());
            }
        }
    }

    @Test
    void paVerifyPaymentTestACAKOMaintenanceStationNotInStandin() throws DatatypeConfigurationException {

        // Test preconditions
        PaVerifyPaymentNoticeReq requestBody = PaVerifyPaymentNoticeReqMock.getMock();

        try (MockedStatic<ConfigCacheData> mockedConfigData = mockStatic(ConfigCacheData.class)) {

            MaintenanceStation stationMaintenance = MaintenanceStation.builder().isStandin(false).build();
            mockedConfigData.when(() -> ConfigCacheData.getStationInMaintenance(anyString())).thenReturn(stationMaintenance);

            try {
                // Test execution
                partnerService.paVerifyPaymentNotice(requestBody, "ACA");
                fail();
            } catch (PartnerValidationException ex) {
                // Test post condition
                assertEquals(PaaErrorEnum.PAA_PAGAMENTO_SCONOSCIUTO, ex.getError());
            }
        }
    }

    @Test
    void paVerifyPaymentTestACAKOStationNotInACA() throws DatatypeConfigurationException {

        // Test preconditions
        PaVerifyPaymentNoticeReq requestBody = PaVerifyPaymentNoticeReqMock.getMock();

        try (MockedStatic<ConfigCacheData> mockedConfigData = mockStatic(ConfigCacheData.class)) {

            ConfigCacheData.StationCI stationCI = ConfigCacheData.StationCI.builder().aca(false).build();
            mockedConfigData.when(() -> ConfigCacheData.getCreditorInstitutionStation(anyString(), anyString())).thenReturn(stationCI);

            try {
                // Test execution
                partnerService.paVerifyPaymentNotice(requestBody, "ACA");
                fail();
            } catch (PartnerValidationException ex) {
                // Test post condition
                assertEquals(PaaErrorEnum.PAA_PAGAMENTO_SCONOSCIUTO, ex.getError());
            }
        }
    }

    @Test
    void paVerifyPaymentTestACAKOPaymentNotACA() throws DatatypeConfigurationException, IOException {

        // Test preconditions
        PaVerifyPaymentNoticeReq requestBody = PaVerifyPaymentNoticeReqMock.getMock();


        PaymentsModelResponse paymentModel =
                MockUtil.readModelFromFile(
                        "gpd/getPaymentOption_PO_UNPAID.json", PaymentsModelResponse.class);
        paymentModel.setServiceType("GPD");

        try {
            // Test execution
            partnerService.paVerifyPaymentNotice(requestBody, "ACA");
            fail();
        } catch (PartnerValidationException ex) {
            // Test post condition
            assertEquals(PaaErrorEnum.PAA_PAGAMENTO_SCONOSCIUTO, ex.getError());
        }
    }

    @Test
    void paVerifyPaymentTestACAKOStationNotInStandin() throws DatatypeConfigurationException, IOException {

        // Test preconditions
        PaVerifyPaymentNoticeReq requestBody = PaVerifyPaymentNoticeReqMock.getMock();

        try (MockedStatic<ConfigCacheData> mockedConfigData = mockStatic(ConfigCacheData.class)) {

            ConfigCacheData.StationCI stationCI = ConfigCacheData.StationCI.builder().aca(true).standin(false).build();
            mockedConfigData.when(() -> ConfigCacheData.getCreditorInstitutionStation(anyString(), anyString())).thenReturn(stationCI);

            PaymentsModelResponse paymentModel =
                    MockUtil.readModelFromFile(
                            "gpd/getPaymentOption_PO_UNPAID.json", PaymentsModelResponse.class);
            paymentModel.setServiceType("ACA");
            when(gpdClient.getPaymentOption(anyString(), anyString())).thenReturn(paymentModel);

            try {
                // Test execution
                partnerService.paVerifyPaymentNotice(requestBody, "ACA");
                fail();
            } catch (PartnerValidationException ex) {
                // Test post condition
                assertEquals(PaaErrorEnum.PAA_PAGAMENTO_SCONOSCIUTO, ex.getError());
            }
        }
    }

    @Test
    void paVerifyPaymentTestACAKODebtPositionNotPayableInStandin() throws DatatypeConfigurationException, IOException {

        // Test preconditions
        PaVerifyPaymentNoticeReq requestBody = PaVerifyPaymentNoticeReqMock.getMock();

        PaymentsModelResponse paymentModel =
                MockUtil.readModelFromFile(
                        "gpd/getPaymentOption_PO_UNPAID.json", PaymentsModelResponse.class);
        paymentModel.setServiceType("ACA");
        paymentModel.setPayStandIn(false);
        when(gpdClient.getPaymentOption(anyString(), anyString())).thenReturn(paymentModel);

        try (MockedStatic<ConfigCacheData> mockedConfigData = mockStatic(ConfigCacheData.class)) {

            MaintenanceStation stationMaintenance = MaintenanceStation.builder().isStandin(true).build();
            mockedConfigData.when(() -> ConfigCacheData.getStationInMaintenance(anyString())).thenReturn(stationMaintenance);
            ConfigCacheData.StationCI stationCI = ConfigCacheData.StationCI.builder().aca(true).standin(true).build();
            mockedConfigData.when(() -> ConfigCacheData.getCreditorInstitutionStation(anyString(), anyString())).thenReturn(stationCI);

            try {
                // Test execution
                partnerService.paVerifyPaymentNotice(requestBody, "ACA");
                fail();
            } catch (PartnerValidationException ex) {
                // Test post condition
                assertEquals(PaaErrorEnum.PAA_PAGAMENTO_SCONOSCIUTO, ex.getError());
            }
        }
    }

}
