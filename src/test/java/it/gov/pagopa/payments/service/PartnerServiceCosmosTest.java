package it.gov.pagopa.payments.service;

import com.azure.data.tables.TableClient;
import com.azure.data.tables.TableClientBuilder;
import com.azure.data.tables.TableServiceClient;
import com.azure.data.tables.TableServiceClientBuilder;
import com.azure.data.tables.models.TableServiceException;
import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.RetryNoRetry;
import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.table.CloudTable;
import com.microsoft.azure.storage.table.CloudTableClient;
import com.microsoft.azure.storage.table.TableRequestOptions;
import feign.FeignException;
import feign.RetryableException;
import it.gov.pagopa.payments.endpoints.validation.PaymentValidator;
import it.gov.pagopa.payments.endpoints.validation.exceptions.PartnerValidationException;
import it.gov.pagopa.payments.initializer.Initializer;
import it.gov.pagopa.payments.mock.*;
import it.gov.pagopa.payments.model.*;
import it.gov.pagopa.payments.model.partner.*;
import it.gov.pagopa.payments.model.spontaneous.PaymentPositionModel;
import it.gov.pagopa.payments.utils.AzuriteStorageUtil;
import it.gov.pagopa.payments.utils.CustomizedMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.ClassRule;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.testcontainers.containers.CosmosDBEmulatorContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.xml.sax.SAXException;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.security.InvalidKeyException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

//TODO change the tests adapting them to the cosmos table
@Testcontainers
@ExtendWith(MockitoExtension.class)
@Slf4j
@SpringBootTest
class PartnerServiceCosmosTest {
  @InjectMocks private PartnerService partnerService;

  @Mock PaymentValidator paymentValidator;

  @Mock private ObjectFactory factory;

  @Mock private GpdClient gpdClient;

  @Mock private GpsClient gpsClient;

  private String genericService = "/xsd/general-service.xsd";
  ResourceLoader resourceLoader = new DefaultResourceLoader();
  Resource resource = resourceLoader.getResource(genericService);

  private final ObjectFactory factoryUtil = new ObjectFactory();

  @Autowired private CustomizedMapper customizedModelMapper;

  @ClassRule @Container
  public static GenericContainer<?> cosmos =
          new GenericContainer<>(
                  DockerImageName.parse("mcr.microsoft.com/cosmosdb/linux/azure-cosmos-emulator:latest"))
                  .withExposedPorts(8902);

  String cosmosConnectionString =
          String.format(
                  "DefaultEndpointsProtocol=http;AccountName=localhost;AccountKey=C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==;TableEndpoint=http://%s:%s/;",
                  cosmos.getContainerIpAddress(),
                  cosmos.getMappedPort(8902));

  @Test
  void paVerifyPaymentNoticeTest() throws DatatypeConfigurationException, IOException {

    // Test preconditions
    PaVerifyPaymentNoticeReq requestBody = PaVerifyPaymentNoticeReqMock.getMock();

    when(factory.createPaVerifyPaymentNoticeRes())
        .thenReturn(factoryUtil.createPaVerifyPaymentNoticeRes());
    when(factory.createCtPaymentOptionDescriptionPA())
        .thenReturn(factoryUtil.createCtPaymentOptionDescriptionPA());
    when(factory.createCtPaymentOptionsDescriptionListPA())
        .thenReturn(factoryUtil.createCtPaymentOptionsDescriptionListPA());

    PaymentsModelResponse paymentModel =
        MockUtil.readModelFromFile("gpd/getPaymentOption.json", PaymentsModelResponse.class);
    when(gpdClient.getPaymentOption(anyString(), anyString())).thenReturn(paymentModel);

    // Test execution
    PaVerifyPaymentNoticeRes responseBody = partnerService.paVerifyPaymentNotice(requestBody);

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

  @Test
  void paVerifyPaymentNoticeTestKOConfig() throws DatatypeConfigurationException {

    // Test preconditions
    PaVerifyPaymentNoticeReq requestBody = PaVerifyPaymentNoticeReqMock.getMock();

    doThrow(new PartnerValidationException(PaaErrorEnum.PAA_ID_INTERMEDIARIO_ERRATO))
        .when(paymentValidator)
        .isAuthorize(anyString(), anyString(), anyString());

    // Test execution
    try {

      partnerService.paVerifyPaymentNotice(requestBody);

    } catch (PartnerValidationException e) {
      // Test post condition
      assertThat(e.getError().getFaultCode())
          .isEqualTo(PaaErrorEnum.PAA_ID_INTERMEDIARIO_ERRATO.getFaultCode());
      assertThat(e.getError().getDescription())
          .isEqualTo(PaaErrorEnum.PAA_ID_INTERMEDIARIO_ERRATO.getDescription());
      assertThat(e.getError().getFaultString())
          .isEqualTo(PaaErrorEnum.PAA_ID_INTERMEDIARIO_ERRATO.getFaultString());
    }
  }

  @Test
  void paVerifyPaymentTestKONotFound() throws DatatypeConfigurationException, IOException {

    // Test preconditions
    PaVerifyPaymentNoticeReq requestBody = PaVerifyPaymentNoticeReqMock.getMock();

    var e = Mockito.mock(FeignException.NotFound.class);
    when(gpdClient.getPaymentOption(anyString(), anyString())).thenThrow(e);

    try {
      // Test execution
      partnerService.paVerifyPaymentNotice(requestBody);
      fail();
    } catch (PartnerValidationException ex) {
      // Test post condition
      assertEquals(PaaErrorEnum.PAA_PAGAMENTO_SCONOSCIUTO, ex.getError());
    }
  }

  @Test
  void paVerifyPaymentTestKOGeneric() throws DatatypeConfigurationException, IOException {

    // Test preconditions
    PaVerifyPaymentNoticeReq requestBody = PaVerifyPaymentNoticeReqMock.getMock();

    var e = Mockito.mock(FeignException.FeignClientException.class);
    when(gpdClient.getPaymentOption(anyString(), anyString())).thenThrow(e);

    try {
      // Test execution
      partnerService.paVerifyPaymentNotice(requestBody);
      fail();
    } catch (PartnerValidationException ex) {
      // Test post condition
      assertEquals(PaaErrorEnum.PAA_SYSTEM_ERROR, ex.getError());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"INVALID", "EXPIRED"})
  void paVerifyPaymentNoticeStatusKOTest(String status)
      throws DatatypeConfigurationException, IOException {

    // Test preconditions
    PaVerifyPaymentNoticeReq requestBody = PaVerifyPaymentNoticeReqMock.getMock();

    PaymentsModelResponse paymentModel =
        MockUtil.readModelFromFile("gpd/getPaymentOption.json", PaymentsModelResponse.class);
    paymentModel.setDebtPositionStatus(DebtPositionStatus.valueOf(status));
    when(gpdClient.getPaymentOption(anyString(), anyString())).thenReturn(paymentModel);

    // Test post condition
    try {
      // Test execution
      partnerService.paVerifyPaymentNotice(requestBody);
      fail();
    } catch (PartnerValidationException ex) {
      // Test post condition
      if (DebtPositionStatus.valueOf(status).equals(DebtPositionStatus.EXPIRED)) {
        assertEquals(PaaErrorEnum.PAA_PAGAMENTO_SCADUTO, ex.getError());
      } else if (DebtPositionStatus.valueOf(status).equals(DebtPositionStatus.INVALID)) {
        assertEquals(PaaErrorEnum.PAA_PAGAMENTO_ANNULLATO, ex.getError());
      } else {
        fail();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"DRAFT", "PUBLISHED"})
  void paVerifyPaymentNoticeStatusKOTest2(String status)
      throws DatatypeConfigurationException, IOException {

    // Test preconditions
    PaVerifyPaymentNoticeReq requestBody = PaVerifyPaymentNoticeReqMock.getMock();

    PaymentsModelResponse paymentModel =
        MockUtil.readModelFromFile("gpd/getPaymentOption.json", PaymentsModelResponse.class);
    paymentModel.setDebtPositionStatus(DebtPositionStatus.valueOf(status));
    when(gpdClient.getPaymentOption(anyString(), anyString())).thenReturn(paymentModel);

    // Test post condition
    try {
      // Test execution
      partnerService.paVerifyPaymentNotice(requestBody);
      fail();
    } catch (PartnerValidationException ex) {
      // Test post condition
      if (DebtPositionStatus.valueOf(status).equals(DebtPositionStatus.DRAFT)
          || DebtPositionStatus.valueOf(status).equals(DebtPositionStatus.PUBLISHED)) {
        assertEquals(PaaErrorEnum.PAA_PAGAMENTO_SCONOSCIUTO, ex.getError());
      } else {
        fail();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"PARTIALLY_PAID", "PAID", "REPORTED"})
  void paVerifyPaymentNoticeStatusKOTest3(String status)
      throws DatatypeConfigurationException, IOException {

    // Test preconditions
    PaVerifyPaymentNoticeReq requestBody = PaVerifyPaymentNoticeReqMock.getMock();

    PaymentsModelResponse paymentModel =
        MockUtil.readModelFromFile("gpd/getPaymentOption.json", PaymentsModelResponse.class);
    paymentModel.setDebtPositionStatus(DebtPositionStatus.valueOf(status));
    when(gpdClient.getPaymentOption(anyString(), anyString())).thenReturn(paymentModel);

    // Test post condition
    try {
      // Test execution
      partnerService.paVerifyPaymentNotice(requestBody);
      fail();
    } catch (PartnerValidationException ex) {
      // Test post condition
      if (DebtPositionStatus.valueOf(status).equals(DebtPositionStatus.PARTIALLY_PAID)
          || DebtPositionStatus.valueOf(status).equals(DebtPositionStatus.PAID)
          || DebtPositionStatus.valueOf(status).equals(DebtPositionStatus.REPORTED)) {
        assertEquals(PaaErrorEnum.PAA_PAGAMENTO_DUPLICATO, ex.getError());
      } else {
        fail();
      }
    }
  }

  @Test
  void paGetPaymentTest()
      throws PartnerValidationException, DatatypeConfigurationException, IOException {

    // Test preconditions
    PaGetPaymentReq requestBody = PaGetPaymentReqMock.getMock();

    when(factory.createPaGetPaymentRes()).thenReturn(factoryUtil.createPaGetPaymentRes());
    when(factory.createCtPaymentPA()).thenReturn(factoryUtil.createCtPaymentPA());
    when(factory.createCtSubject()).thenReturn(factoryUtil.createCtSubject());
    when(factory.createCtEntityUniqueIdentifier())
        .thenReturn(factoryUtil.createCtEntityUniqueIdentifier());
    when(factory.createCtTransferListPA()).thenReturn(factoryUtil.createCtTransferListPA());

    when(gpdClient.getPaymentOption(anyString(), anyString()))
        .thenReturn(
            MockUtil.readModelFromFile("gpd/getPaymentOption.json", PaymentsModelResponse.class));

    // Test execution
    PaGetPaymentRes responseBody = partnerService.paGetPayment(requestBody);

    // Test post condition
    assertThat(responseBody.getData().getCreditorReferenceId()).isEqualTo("11111111112222222");
    assertThat(responseBody.getData().getDescription()).isEqualTo("string");
    assertThat(responseBody.getData().getDueDate())
        .isEqualTo(
            DatatypeFactory.newInstance().newXMLGregorianCalendar("2122-02-24T17:03:59.408"));
    assertThat(responseBody.getData().getRetentionDate())
        .isEqualTo(
            DatatypeFactory.newInstance().newXMLGregorianCalendar("2022-02-25T17:03:59.408"));
    assertEquals("77777777777", requestBody.getQrCode().getFiscalCode());
    assertEquals(3, responseBody.getData().getTransferList().getTransfer().size());

    // in paGetPayment v1 the 'richiestaMarcaDaBollo' does not exist
    org.hamcrest.MatcherAssert.assertThat(
        responseBody.getData().getTransferList().getTransfer(),
        org.hamcrest.Matchers.contains(
            org.hamcrest.Matchers.allOf(
                org.hamcrest.Matchers.<CtTransferPA>hasProperty(
                    "IBAN", org.hamcrest.Matchers.is("string"))),
            org.hamcrest.Matchers.allOf(
                org.hamcrest.Matchers.<CtTransferPA>hasProperty(
                    "IBAN", org.hamcrest.Matchers.is("ABC"))),
            org.hamcrest.Matchers.allOf(
                org.hamcrest.Matchers.<CtTransferPA>hasProperty(
                    "IBAN", org.hamcrest.Matchers.nullValue()))));
  }

  @Test
  void paGetPaymentIncompleteAddressTest()
      throws PartnerValidationException, DatatypeConfigurationException, IOException {

    // Test preconditions
    PaGetPaymentReq requestBody = PaGetPaymentReqMock.getMock();

    when(factory.createPaGetPaymentRes()).thenReturn(factoryUtil.createPaGetPaymentRes());
    when(factory.createCtPaymentPA()).thenReturn(factoryUtil.createCtPaymentPA());
    when(factory.createCtSubject()).thenReturn(factoryUtil.createCtSubject());
    when(factory.createCtEntityUniqueIdentifier())
        .thenReturn(factoryUtil.createCtEntityUniqueIdentifier());
    when(factory.createCtTransferListPA()).thenReturn(factoryUtil.createCtTransferListPA());

    when(gpdClient.getPaymentOption(anyString(), anyString()))
        .thenReturn(
            MockUtil.readModelFromFile(
                "gpd/getPaymentOptionWithIncompleteAddress.json", PaymentsModelResponse.class));

    // Test execution
    PaGetPaymentRes responseBody = partnerService.paGetPayment(requestBody);

    // Test post condition
    assertThat(responseBody.getData().getCreditorReferenceId()).isEqualTo("11111111112222222");
    assertThat(responseBody.getData().getDescription())
        .isEqualTo("Canone Unico Patrimoniale - CORPORATE");
    assertThat(responseBody.getData().getDueDate())
        .isEqualTo(
            DatatypeFactory.newInstance().newXMLGregorianCalendar("2022-04-20T12:15:38.927"));
    assertThat(responseBody.getData().getRetentionDate())
        .isEqualTo(
            DatatypeFactory.newInstance().newXMLGregorianCalendar("2022-06-19T12:15:38.927"));
    assertEquals("77777777777", requestBody.getQrCode().getFiscalCode());

    assertEquals("city", responseBody.getData().getDebtor().getCity());
    assertEquals("RM", responseBody.getData().getDebtor().getStateProvinceRegion());
    assertEquals("00100", responseBody.getData().getDebtor().getPostalCode());
    assertNull(responseBody.getData().getDebtor().getStreetName());
    assertNull(responseBody.getData().getDebtor().getCivicNumber());
    assertNull(responseBody.getData().getDebtor().getCountry());
    assertNull(responseBody.getData().getDebtor().getEMail());
    assertEquals(2, responseBody.getData().getTransferList().getTransfer().size());

    // in paGetPayment v1 the 'richiestaMarcaDaBollo' does not exist
    org.hamcrest.MatcherAssert.assertThat(
        responseBody.getData().getTransferList().getTransfer(),
        org.hamcrest.Matchers.contains(
            org.hamcrest.Matchers.allOf(
                org.hamcrest.Matchers.<CtTransferPA>hasProperty(
                    "IBAN", org.hamcrest.Matchers.is("IT0000000000000000000000000"))),
            org.hamcrest.Matchers.allOf(
                org.hamcrest.Matchers.<CtTransferPA>hasProperty(
                    "IBAN", org.hamcrest.Matchers.nullValue()))));
  }

  @Test
  void paGetPaymentTestKONotFound() throws DatatypeConfigurationException, IOException {

    // Test preconditions
    PaGetPaymentReq requestBody = PaGetPaymentReqMock.getMock();

    var e = Mockito.mock(FeignException.NotFound.class);
    when(gpdClient.getPaymentOption(anyString(), anyString())).thenThrow(e);

    try {
      // Test execution
      partnerService.paGetPayment(requestBody);
      fail();
    } catch (PartnerValidationException ex) {
      // Test post condition
      assertEquals(PaaErrorEnum.PAA_PAGAMENTO_SCONOSCIUTO, ex.getError());
    }
  }

  @Test
  void paGetPaymentTestKOGeneric() throws DatatypeConfigurationException, IOException {

    // Test preconditions
    PaGetPaymentReq requestBody = PaGetPaymentReqMock.getMock();

    var e = Mockito.mock(FeignException.FeignClientException.class);
    when(gpdClient.getPaymentOption(anyString(), anyString())).thenThrow(e);

    try {
      // Test execution
      partnerService.paGetPayment(requestBody);
      fail();
    } catch (PartnerValidationException ex) {
      // Test post condition
      assertEquals(PaaErrorEnum.PAA_SYSTEM_ERROR, ex.getError());
    }
  }

  @Test
  void paSendRTTest() throws DatatypeConfigurationException, IOException {

    var pService =
            spy(new PartnerServiceCosmos(
                    resource,
                    factory,
                    gpdClient,
                    gpsClient,
                    tableClientConfiguration(),
                    paymentValidator,
                    customizedModelMapper));

    // Test preconditions
    PaSendRTReq requestBody = PaSendRTReqMock.getMock();

    doNothing()
        .doThrow(PartnerValidationException.class)
        .when(paymentValidator)
        .isAuthorize(anyString(), anyString(), anyString());

    when(factory.createPaSendRTRes()).thenReturn(factoryUtil.createPaSendRTRes());

    when(gpdClient.receiptPaymentOption(anyString(), anyString(), any(PaymentOptionModel.class)))
        .thenReturn(
            MockUtil.readModelFromFile(
                "gpd/receiptPaymentOption.json", PaymentOptionModelResponse.class));

    // Test execution
    PaSendRTRes responseBody = pService.paSendRT(requestBody);

    // Test post condition
    assertThat(responseBody.getOutcome()).isEqualTo(StOutcome.OK);
    assertThat(responseBody.getFault()).isNull();
  }

  @Test
  void paSendRTTestKOConflict() throws DatatypeConfigurationException, IOException {

    var pService =
            spy(new PartnerServiceCosmos(
                    resource,
                    factory,
                    gpdClient,
                    gpsClient,
                    tableClientConfiguration(),
                    paymentValidator,
                    customizedModelMapper));

    // Test preconditions
    PaSendRTReq requestBody = PaSendRTReqMock.getMock();

    var e = Mockito.mock(FeignException.Conflict.class);
    when(gpdClient.receiptPaymentOption(anyString(), anyString(), any(PaymentOptionModel.class)))
        .thenThrow(e);

    try {
      // Test execution
      pService.paSendRT(requestBody);
      fail();
    } catch (PartnerValidationException ex) {
      // Test post condition
      assertEquals(PaaErrorEnum.PAA_RECEIPT_DUPLICATA, ex.getError());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"PO_UNPAID", "PO_PARTIALLY_REPORTED", "PO_REPORTED"})
  void paSendRTTestKOStatus(String status) throws DatatypeConfigurationException, IOException {

    var pService =
            spy(new PartnerServiceCosmos(
                    resource,
                    factory,
                    gpdClient,
                    gpsClient,
                    tableClientConfiguration(),
                    paymentValidator,
                    customizedModelMapper));

    // Test preconditions
    PaSendRTReq requestBody = PaSendRTReqMock.getMock();

    PaymentOptionModelResponse paymentOption =
        MockUtil.readModelFromFile(
            "gpd/receiptPaymentOption.json", PaymentOptionModelResponse.class);
    paymentOption.setStatus(PaymentOptionStatus.valueOf(status));
    when(gpdClient.receiptPaymentOption(anyString(), anyString(), any(PaymentOptionModel.class)))
        .thenReturn(paymentOption);

    try {
      // Test execution
      pService.paSendRT(requestBody);
      fail();
    } catch (PartnerValidationException ex) {
      // Test post condition
      assertEquals(PaaErrorEnum.PAA_SEMANTICA, ex.getError());
    }
  }

  @Test
  void paSendRTTestKORetryableException() throws DatatypeConfigurationException, IOException {

    var pService =
            spy(new PartnerServiceCosmos(
                    resource,
                    factory,
                    gpdClient,
                    gpsClient,
                    tableClientConfiguration(),
                    paymentValidator,
                    customizedModelMapper));

    // Test preconditions
    PaSendRTReq requestBody = PaSendRTReqMock.getMock();

    var e = Mockito.mock(RetryableException.class);
    when(gpdClient.receiptPaymentOption(anyString(), anyString(), any(PaymentOptionModel.class)))
        .thenThrow(e);

    try {
      // Test execution
      pService.paSendRT(requestBody);
      fail();
    } catch (PartnerValidationException ex) {
      // Test post condition
      assertEquals(PaaErrorEnum.PAA_SYSTEM_ERROR, ex.getError());
    }
  }

  @Test
  void paSendRTTestKOFeignException() throws DatatypeConfigurationException, IOException {

    var pService =
            spy(new PartnerServiceCosmos(
                    resource,
                    factory,
                    gpdClient,
                    gpsClient,
                    tableClientConfiguration(),
                    paymentValidator,
                    customizedModelMapper));
    // Test preconditions
    PaSendRTReq requestBody = PaSendRTReqMock.getMock();

    var e = Mockito.mock(FeignException.class);
    when(gpdClient.receiptPaymentOption(anyString(), anyString(), any(PaymentOptionModel.class)))
        .thenThrow(e);

    try {
      // Test execution
      pService.paSendRT(requestBody);
      fail();
    } catch (PartnerValidationException ex) {
      // Test post condition
      assertEquals(PaaErrorEnum.PAA_SEMANTICA, ex.getError());
    }
  }

  @Test
  void paSendRTTestKO() throws DatatypeConfigurationException, IOException {

    var pService =
            spy(new PartnerServiceCosmos(
                    resource,
                    factory,
                    gpdClient,
                    gpsClient,
                    tableClientConfiguration(),
                    paymentValidator,
                    customizedModelMapper));

    // Test preconditions
    PaSendRTReq requestBody = PaSendRTReqMock.getMock();

    var e = Mockito.mock(NullPointerException.class);
    when(gpdClient.receiptPaymentOption(anyString(), anyString(), any(PaymentOptionModel.class)))
        .thenThrow(e);

    try {
      // Test execution
      pService.paSendRT(requestBody);
      fail();
    } catch (PartnerValidationException ex) {
      // Test post condition
      assertEquals(PaaErrorEnum.PAA_SYSTEM_ERROR, ex.getError());
    }
  }

  @Test
  void cosmosStorageTest() throws TableServiceException {
    TableClient tableClient = new TableClientBuilder()
            .connectionString(cosmosConnectionString)
            .tableName("testTable")
            .buildClient();
    //return tableServiceClient.getTableClient("testTable");
  }

  @Test
  void paDemandPaymentNoticeTest()
      throws DatatypeConfigurationException,
          IOException,
          XMLStreamException,
          ParserConfigurationException,
          SAXException {
    var pService =
            spy(new PartnerServiceCosmos(
                    resource,
                    factory,
                    gpdClient,
                    gpsClient,
                    tableClientConfiguration(),
                    paymentValidator,
                    customizedModelMapper));

    // Test preconditions
    var requestBody = PaDemandNoticePaymentReqMock.getMock();

    when(factory.createPaDemandPaymentNoticeResponse())
        .thenReturn(factoryUtil.createPaDemandPaymentNoticeResponse());
    when(factory.createCtQrCode()).thenReturn(factoryUtil.createCtQrCode());
    when(factory.createCtPaymentOptionsDescriptionListPA())
        .thenReturn(factoryUtil.createCtPaymentOptionsDescriptionListPA());
    when(factory.createCtPaymentOptionDescriptionPA())
        .thenReturn(factoryUtil.createCtPaymentOptionDescriptionPA());

    var paymentModel =
        MockUtil.readModelFromFile(
            "gps/createSpontaneousPayments.json", PaymentPositionModel.class);
    when(gpsClient.createSpontaneousPayments(anyString(), any())).thenReturn(paymentModel);

    // Test execution
    var responseBody = pService.paDemandPaymentNotice(requestBody);

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

  @Test
  void paGetPaymentV2Test()
      throws PartnerValidationException, DatatypeConfigurationException, IOException {

    var pService =
            spy(new PartnerServiceCosmos(
                    resource,
                    factory,
                    gpdClient,
                    gpsClient,
                    tableClientConfiguration(),
                    paymentValidator,
                    customizedModelMapper));

    // Test preconditions
    PaGetPaymentV2Request requestBody = PaGetPaymentReqMock.getMockV2();

    when(factory.createPaGetPaymentV2Response())
        .thenReturn(factoryUtil.createPaGetPaymentV2Response());
    when(factory.createCtPaymentPAV2()).thenReturn(factoryUtil.createCtPaymentPAV2());
    when(factory.createCtSubject()).thenReturn(factoryUtil.createCtSubject());
    when(factory.createCtEntityUniqueIdentifier())
        .thenReturn(factoryUtil.createCtEntityUniqueIdentifier());
    when(factory.createCtTransferListPAV2()).thenReturn(factoryUtil.createCtTransferListPAV2());

    when(gpdClient.getPaymentOption(anyString(), anyString()))
        .thenReturn(
            MockUtil.readModelFromFile("gpd/getPaymentOption.json", PaymentsModelResponse.class));

    // Test execution
    PaGetPaymentV2Response responseBody = pService.paGetPaymentV2(requestBody);

    // Test post condition
    assertThat(responseBody.getData().getCreditorReferenceId()).isEqualTo("11111111112222222");
    assertThat(responseBody.getData().getDescription()).isEqualTo("string");
    assertThat(responseBody.getData().getDueDate())
        .isEqualTo(
            DatatypeFactory.newInstance().newXMLGregorianCalendar("2122-02-24T17:03:59.408"));
    assertThat(responseBody.getData().getRetentionDate())
        .isEqualTo(
            DatatypeFactory.newInstance().newXMLGregorianCalendar("2022-02-25T17:03:59.408"));
    assertEquals("77777777777", requestBody.getQrCode().getFiscalCode());
    assertEquals(3, responseBody.getData().getTransferList().getTransfer().size());

    // in paGetPayment v2 there is the new 'richiestaMarcaDaBollo' field and it can be valued
    org.hamcrest.MatcherAssert.assertThat(
        responseBody.getData().getTransferList().getTransfer(),
        org.hamcrest.Matchers.contains(
            org.hamcrest.Matchers.allOf(
                org.hamcrest.Matchers.<CtTransferPAV2>hasProperty(
                    "richiestaMarcaDaBollo", org.hamcrest.Matchers.nullValue()),
                org.hamcrest.Matchers.<CtTransferPAV2>hasProperty(
                    "IBAN", org.hamcrest.Matchers.is("string"))),
            org.hamcrest.Matchers.allOf(
                org.hamcrest.Matchers.<CtTransferPAV2>hasProperty(
                    "richiestaMarcaDaBollo", org.hamcrest.Matchers.nullValue()),
                org.hamcrest.Matchers.<CtTransferPAV2>hasProperty(
                    "IBAN", org.hamcrest.Matchers.is("ABC"))),
            org.hamcrest.Matchers.allOf(
                org.hamcrest.Matchers.<CtTransferPAV2>hasProperty(
                    "richiestaMarcaDaBollo", org.hamcrest.Matchers.notNullValue()),
                org.hamcrest.Matchers.<CtTransferPAV2>hasProperty(
                    "IBAN", org.hamcrest.Matchers.nullValue()))));
  }

  @Test
  void paGetPaymentIncompleteAddressV2Test()
      throws PartnerValidationException, DatatypeConfigurationException, IOException {

    var pService =
            spy(new PartnerServiceCosmos(
                    resource,
                    factory,
                    gpdClient,
                    gpsClient,
                    tableClientConfiguration(),
                    paymentValidator,
                    customizedModelMapper));

    // Test preconditions
    PaGetPaymentV2Request requestBody = PaGetPaymentReqMock.getMockV2();

    when(factory.createPaGetPaymentV2Response())
        .thenReturn(factoryUtil.createPaGetPaymentV2Response());
    when(factory.createCtPaymentPAV2()).thenReturn(factoryUtil.createCtPaymentPAV2());
    when(factory.createCtSubject()).thenReturn(factoryUtil.createCtSubject());
    when(factory.createCtEntityUniqueIdentifier())
        .thenReturn(factoryUtil.createCtEntityUniqueIdentifier());
    when(factory.createCtTransferListPAV2()).thenReturn(factoryUtil.createCtTransferListPAV2());

    when(gpdClient.getPaymentOption(anyString(), anyString()))
        .thenReturn(
            MockUtil.readModelFromFile(
                "gpd/getPaymentOptionWithIncompleteAddress.json", PaymentsModelResponse.class));

    // Test execution
    PaGetPaymentV2Response responseBody = pService.paGetPaymentV2(requestBody);

    // Test post condition
    assertThat(responseBody.getData().getCreditorReferenceId()).isEqualTo("11111111112222222");
    assertThat(responseBody.getData().getDescription())
        .isEqualTo("Canone Unico Patrimoniale - CORPORATE");
    assertThat(responseBody.getData().getDueDate())
        .isEqualTo(
            DatatypeFactory.newInstance().newXMLGregorianCalendar("2022-04-20T12:15:38.927"));
    assertThat(responseBody.getData().getRetentionDate())
        .isEqualTo(
            DatatypeFactory.newInstance().newXMLGregorianCalendar("2022-06-19T12:15:38.927"));
    assertEquals("77777777777", requestBody.getQrCode().getFiscalCode());

    assertEquals("city", responseBody.getData().getDebtor().getCity());
    assertEquals("RM", responseBody.getData().getDebtor().getStateProvinceRegion());
    assertEquals("00100", responseBody.getData().getDebtor().getPostalCode());
    assertNull(responseBody.getData().getDebtor().getStreetName());
    assertNull(responseBody.getData().getDebtor().getCivicNumber());
    assertNull(responseBody.getData().getDebtor().getCountry());
    assertNull(responseBody.getData().getDebtor().getEMail());
    assertEquals(2, responseBody.getData().getTransferList().getTransfer().size());

    // in paGetPayment v2 there is the new 'richiestaMarcaDaBollo' field and it can be valued
    org.hamcrest.MatcherAssert.assertThat(
        responseBody.getData().getTransferList().getTransfer(),
        org.hamcrest.Matchers.contains(
            org.hamcrest.Matchers.allOf(
                org.hamcrest.Matchers.<CtTransferPAV2>hasProperty(
                    "richiestaMarcaDaBollo", org.hamcrest.Matchers.nullValue()),
                org.hamcrest.Matchers.<CtTransferPAV2>hasProperty(
                    "IBAN", org.hamcrest.Matchers.is("IT0000000000000000000000000"))),
            org.hamcrest.Matchers.allOf(
                org.hamcrest.Matchers.<CtTransferPAV2>hasProperty(
                    "richiestaMarcaDaBollo", org.hamcrest.Matchers.notNullValue()),
                org.hamcrest.Matchers.<CtTransferPAV2>hasProperty(
                    "IBAN", org.hamcrest.Matchers.nullValue()))));
  }

  @Test
  void paGetPaymentV2TestKONotFound() throws DatatypeConfigurationException, IOException {

    // Test preconditions
    PaGetPaymentV2Request requestBody = PaGetPaymentReqMock.getMockV2();

    var e = Mockito.mock(FeignException.NotFound.class);
    when(gpdClient.getPaymentOption(anyString(), anyString())).thenThrow(e);

    try {
      // Test execution
      partnerService.paGetPaymentV2(requestBody);
      fail();
    } catch (PartnerValidationException ex) {
      // Test post condition
      assertEquals(PaaErrorEnum.PAA_PAGAMENTO_SCONOSCIUTO, ex.getError());
    }
  }

  @Test
  void paGetPaymentV2TestKOGeneric() throws DatatypeConfigurationException, IOException {

    // Test preconditions
    PaGetPaymentV2Request requestBody = PaGetPaymentReqMock.getMockV2();

    var e = Mockito.mock(FeignException.FeignClientException.class);
    when(gpdClient.getPaymentOption(anyString(), anyString())).thenThrow(e);

    try {
      // Test execution
      partnerService.paGetPaymentV2(requestBody);
      fail();
    } catch (PartnerValidationException ex) {
      // Test post condition
      assertEquals(PaaErrorEnum.PAA_SYSTEM_ERROR, ex.getError());
    }
  }

  @Test
  void paSendRTV2Test() throws DatatypeConfigurationException, IOException {

    var pService =
            spy(new PartnerServiceCosmos(
                    resource,
                    factory,
                    gpdClient,
                    gpsClient,
                    tableClientConfiguration(),
                    paymentValidator,
                    customizedModelMapper));

    // Test preconditions
    PaSendRTV2Request requestBody = PaSendRTReqMock.getMockV2();

    doNothing()
        .doThrow(PartnerValidationException.class)
        .when(paymentValidator)
        .isAuthorize(anyString(), anyString(), anyString());

    when(factory.createPaSendRTV2Response()).thenReturn(factoryUtil.createPaSendRTV2Response());

    when(gpdClient.receiptPaymentOption(anyString(), anyString(), any(PaymentOptionModel.class)))
        .thenReturn(
            MockUtil.readModelFromFile(
                "gpd/receiptPaymentOption.json", PaymentOptionModelResponse.class));

    // Test execution
    PaSendRTV2Response responseBody = pService.paSendRTV2(requestBody);

    // Test post condition
    assertThat(responseBody.getOutcome()).isEqualTo(StOutcome.OK);
    assertThat(responseBody.getFault()).isNull();
  }

  @Test
  void paSendRTV2TestKOConflict() throws DatatypeConfigurationException, IOException {

    var pService =
            spy(new PartnerServiceCosmos(
                    resource,
                    factory,
                    gpdClient,
                    gpsClient,
                    tableClientConfiguration(),
                    paymentValidator,
                    customizedModelMapper));

    // Test preconditions
    PaSendRTV2Request requestBody = PaSendRTReqMock.getMockV2();

    var e = Mockito.mock(FeignException.Conflict.class);
    when(gpdClient.receiptPaymentOption(anyString(), anyString(), any(PaymentOptionModel.class)))
        .thenThrow(e);

    try {
      // Test execution
      pService.paSendRTV2(requestBody);
      fail();
    } catch (PartnerValidationException ex) {
      // Test post condition
      assertEquals(PaaErrorEnum.PAA_RECEIPT_DUPLICATA, ex.getError());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"PO_UNPAID", "PO_PARTIALLY_REPORTED", "PO_REPORTED"})
  void paSendRTV2TestKOStatus(String status) throws DatatypeConfigurationException, IOException {

    var pService =
            spy(new PartnerServiceCosmos(
                    resource,
                    factory,
                    gpdClient,
                    gpsClient,
                    tableClientConfiguration(),
                    paymentValidator,
                    customizedModelMapper));

    // Test preconditions
    PaSendRTV2Request requestBody = PaSendRTReqMock.getMockV2();

    PaymentOptionModelResponse paymentOption =
        MockUtil.readModelFromFile(
            "gpd/receiptPaymentOption.json", PaymentOptionModelResponse.class);
    paymentOption.setStatus(PaymentOptionStatus.valueOf(status));
    when(gpdClient.receiptPaymentOption(anyString(), anyString(), any(PaymentOptionModel.class)))
        .thenReturn(paymentOption);

    try {
      // Test execution
      pService.paSendRTV2(requestBody);
      fail();
    } catch (PartnerValidationException ex) {
      // Test post condition
      assertEquals(PaaErrorEnum.PAA_SEMANTICA, ex.getError());
    }
  }

  @Test
  void paSendRTV2TestKORetryableException() throws DatatypeConfigurationException, IOException {

    var pService =
            spy(new PartnerServiceCosmos(
                    resource,
                    factory,
                    gpdClient,
                    gpsClient,
                    tableClientConfiguration(),
                    paymentValidator,
                    customizedModelMapper));

    // Test preconditions
    PaSendRTV2Request requestBody = PaSendRTReqMock.getMockV2();

    var e = Mockito.mock(RetryableException.class);
    when(gpdClient.receiptPaymentOption(anyString(), anyString(), any(PaymentOptionModel.class)))
        .thenThrow(e);

    try {
      // Test execution
      pService.paSendRTV2(requestBody);
      fail();
    } catch (PartnerValidationException ex) {
      // Test post condition
      assertEquals(PaaErrorEnum.PAA_SYSTEM_ERROR, ex.getError());
    }
  }

  @Test
  void paSendRTV2TestKOFeignException() throws DatatypeConfigurationException, IOException {

    var pService =
            spy(new PartnerServiceCosmos(
                            resource,
                            factory,
                            gpdClient,
                            gpsClient,
                            tableClientConfiguration(),
                            paymentValidator,
                            customizedModelMapper));
    // Test preconditions
    PaSendRTV2Request requestBody = PaSendRTReqMock.getMockV2();


    var e = Mockito.mock(FeignException.class);
    when(gpdClient.receiptPaymentOption(anyString(), anyString(), any(PaymentOptionModel.class)))
        .thenThrow(e);

    try {
      // Test execution
      pService.paSendRTV2(requestBody);
      fail();
    } catch (PartnerValidationException ex) {
      // Test post condition
      assertEquals(PaaErrorEnum.PAA_SEMANTICA, ex.getError());
    }
  }

  @Test
  void paSendRTV2TestKO() throws DatatypeConfigurationException, IOException {

    var pService =
            spy(new PartnerServiceCosmos(
                            resource,
                            factory,
                            gpdClient,
                            gpsClient,
                            tableClientConfiguration(),
                            paymentValidator,
                            customizedModelMapper));

    // Test preconditions
    PaSendRTV2Request requestBody = PaSendRTReqMock.getMockV2();

    var e = Mockito.mock(NullPointerException.class);
    when(gpdClient.receiptPaymentOption(anyString(), anyString(), any(PaymentOptionModel.class)))
        .thenThrow(e);
    try {
      // Test execution
      pService.paSendRTV2(requestBody);
      fail();
    } catch (PartnerValidationException ex) {
      // Test post condition
      assertEquals(PaaErrorEnum.PAA_SYSTEM_ERROR, ex.getError());
    }
  }

  private TableClient tableClientConfiguration() {
    return new TableClientBuilder()
            .connectionString(cosmosConnectionString)
            .tableName("testTable")
            .buildClient();
  }
}
