package it.gov.pagopa.payments.service;

import static org.junit.Assert.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.azure.data.tables.TableClient;
import com.azure.data.tables.TableClientBuilder;
import com.azure.data.tables.models.TableEntity;
import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.RetryNoRetry;
import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.table.CloudTable;
import com.microsoft.azure.storage.table.CloudTableClient;
import com.microsoft.azure.storage.table.TableRequestOptions;
import it.gov.pagopa.payments.entity.ReceiptEntity;
import it.gov.pagopa.payments.entity.Status;
import it.gov.pagopa.payments.exception.AppException;
import it.gov.pagopa.payments.mock.MockUtil;
import it.gov.pagopa.payments.model.PaymentsModelResponse;
import it.gov.pagopa.payments.model.PaymentsResult;
import java.util.*;

import it.gov.pagopa.payments.model.ReceiptModelResponse;
import org.junit.ClassRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@ExtendWith(MockitoExtension.class)
class PaymentsServiceTest {

  @Mock private GpdClient gpdClient;

  public static final String DEBTOR_PROPERTY = "debtor";

  public static final String DOCUMENT_PROPERTY = "document";

  public static final String STATUS_PROPERTY = "status";

  public static final String PAYMENT_DATE_PROPERTY = "paymentDate";

  @ClassRule @Container
  public static GenericContainer<?> azurite =
      new GenericContainer<>(
              DockerImageName.parse("mcr.microsoft.com/azure-storage/azurite:latest"))
          .withExposedPorts(10001, 10002, 10000);

  String storageConnectionString =
      String.format(
          "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;TableEndpoint=http://%s:%s/devstoreaccount1;QueueEndpoint=http://%s:%s/devstoreaccount1;BlobEndpoint=http://%s:%s/devstoreaccount1",
          azurite.getContainerIpAddress(),
          azurite.getMappedPort(10002),
          azurite.getContainerIpAddress(),
          azurite.getMappedPort(10001),
          azurite.getContainerIpAddress(),
          azurite.getMappedPort(10000));

  CloudTable table = null;

  @BeforeEach
  void setUp() throws StorageException {
    try {
      CloudStorageAccount cloudStorageAccount = CloudStorageAccount.parse(storageConnectionString);
      CloudTableClient cloudTableClient = cloudStorageAccount.createCloudTableClient();
      TableRequestOptions tableRequestOptions = new TableRequestOptions();
      tableRequestOptions.setRetryPolicyFactory(RetryNoRetry.getInstance());
      cloudTableClient.setDefaultRequestOptions(tableRequestOptions);
      table = cloudTableClient.getTableReference("receiptsTable");
      table.createIfNotExists();
    } catch (Exception e) {
      e.printStackTrace();
    }
    for (int i = 0; i < 10; i++) {
      TableEntity tableEntity = new TableEntity("org123456", "0" + i + "1234567891011");
      Map<String, Object> properties = new HashMap<>();
      properties.put(DEBTOR_PROPERTY, "debtor" + i);
      properties.put(DOCUMENT_PROPERTY, "XML" + i);
      properties.put(STATUS_PROPERTY, Status.PAID.name());
      properties.put(PAYMENT_DATE_PROPERTY, "2022-10-01T17:48:22");
      tableEntity.setProperties(properties);
      tableClientConfiguration().createEntity(tableEntity);
    }
    for (int i = 10; i < 15; i++) {
      TableEntity tableEntity = new TableEntity("org123456", i + "1234567891011");
      Map<String, Object> properties = new HashMap<>();
      properties.put(DEBTOR_PROPERTY, "debtor" + i);
      properties.put(DOCUMENT_PROPERTY, "XML" + i);
      properties.put(STATUS_PROPERTY, Status.CREATED.name());
      properties.put(PAYMENT_DATE_PROPERTY, "2022-10-01T17:48:22");
      tableEntity.setProperties(properties);
      tableClientConfiguration().createEntity(tableEntity);
    }
  }

  @AfterEach
  void teardown() {
    TableClient tableClient = tableClientConfiguration();
    tableClient.deleteTable();
  }

  /** GET RECEIPT BY IUV */
  @Test
  void getReceiptByOrganizationFCAndIUV() throws Exception {

    PaymentsService paymentsService =
        spy(new PaymentsService(gpdClient, tableClientConfiguration()));

    ArrayList<String> validSegregationCodes = new ArrayList<>(Arrays.asList("00", "89", "90"));
    ReceiptEntity re =
        paymentsService.getReceiptByOrganizationFCAndIUV(
            "org123456", "001234567891011", validSegregationCodes);
    assertEquals("org123456", re.getOrganizationFiscalCode());
    assertEquals("001234567891011", re.getIuv());
    assertEquals("debtor0", re.getDebtor());
  }

  @Test
  void getReceiptByOrganizationFCAndIUV_403() throws Exception {

    PaymentsService paymentsService =
        spy(new PaymentsService(gpdClient, tableClientConfiguration()));

    try {
      ArrayList<String> validSegregationCodes = new ArrayList<>(Arrays.asList("47", "89", "90"));
      paymentsService.getReceiptByOrganizationFCAndIUV(
          "org123456", "0012345678910", validSegregationCodes);
    } catch (AppException e) {
      assertEquals(HttpStatus.FORBIDDEN, e.getHttpStatus());
    }
  }

  @Test
  void getReceiptByOrganizationFCAndIUV_404() throws Exception {

    PaymentsService paymentsService =
        spy(new PaymentsService(gpdClient, tableClientConfiguration()));

    try {
      paymentsService.getReceiptByOrganizationFCAndIUV("org123456", "3000000000000013", null);
    } catch (AppException e) {
      assertEquals(HttpStatus.NOT_FOUND, e.getHttpStatus());
    }
  }

  @Test
  void getReceiptByOrganizationFCAndIUV_500() throws Exception {

    try {
      String wrongStorageConnectionString =
          String.format(
              "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;TableEndpoint=http://%s:%s/devstoreaccount1;QueueEndpoint=http://%s:%s/devstoreaccount1;BlobEndpoint=http://%s:%s/devstoreaccount1",
              azurite.getContainerIpAddress(),
              azurite.getMappedPort(10002),
              azurite.getContainerIpAddress(),
              azurite.getMappedPort(10001),
              azurite.getContainerIpAddress(),
              azurite.getMappedPort(10000));
      TableClient tableClient =
          new TableClientBuilder()
              .connectionString(wrongStorageConnectionString)
              .tableName("receiptsTable")
              .buildClient();
      PaymentsService paymentsService = spy(new PaymentsService(gpdClient, tableClient));

      paymentsService.getReceiptByOrganizationFCAndIUV("org123456", "001234567891011", null);
    } catch (AppException e) {
      assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, e.getHttpStatus());
    }
  }

  /** GET RECEIPTS */
  @Test
  void getOrganizationReceipts_noFilter() throws Exception {
    PaymentsService paymentsService =
        spy(new PaymentsService(gpdClient, tableClientConfiguration()));

    PaymentsResult<ReceiptModelResponse> res =
        paymentsService.getOrganizationReceipts(
                "org123456", null, null, null, null, 0, 100, null, null);
    assertNotNull(res);
    assertEquals(15, res.getResults().size());
  }

  @Test
  void getOrganizationReceipts_segregationCodesFilter() throws Exception {
    PaymentsService paymentsService =
        spy(new PaymentsService(gpdClient, tableClientConfiguration()));

    ArrayList<String> validSegregationCodes = new ArrayList<>(Arrays.asList("02"));
    PaymentsResult<ReceiptModelResponse> res =
        paymentsService.getOrganizationReceipts(
            "org123456", null, null, null, null, 0, 100, validSegregationCodes, null);
    assertNotNull(res);
    assertEquals(1, res.getResults().size());
  }

  @Test
  void getOrganizationReceipts_segregationCodesFilter2() throws Exception {
    PaymentsService paymentsService =
        spy(new PaymentsService(gpdClient, tableClientConfiguration()));

    ArrayList<String> validSegregationCodes = new ArrayList<>(Arrays.asList("02", "03"));
    PaymentsResult<ReceiptModelResponse> res =
        paymentsService.getOrganizationReceipts(
            "org123456", null, null, null, null, 0, 100, validSegregationCodes, null);
    assertNotNull(res);
    assertEquals(2, res.getResults().size());
  }

  @Test
  void getOrganizationReceipts_segregationCodesFilterEmpty() throws Exception {
    PaymentsService paymentsService =
            spy(new PaymentsService(gpdClient, tableClientConfiguration()));

    ArrayList<String> validSegregationCodes = new ArrayList<>();
    PaymentsResult<ReceiptModelResponse> res =
            paymentsService.getOrganizationReceipts(
                    "org123456", null, null, null, null, 0, 100, validSegregationCodes, null);
    assertNotNull(res);
    assertEquals(15, res.getResults().size());
  }


  @Test
  void getOrganizationReceipts_PageFilter() throws Exception {
    PaymentsService paymentsService =
        spy(new PaymentsService(gpdClient, tableClientConfiguration()));

    PaymentsResult<ReceiptModelResponse> res =
        paymentsService.getOrganizationReceipts("org123456", null, null, null, null, 0, 4, null, null);
    assertNotNull(res);
    assertEquals(4, res.getResults().size());
  }

  @Test
  void getOrganizationReceipts_PageNumberTooHigh() throws Exception {
    PaymentsService paymentsService =
        spy(new PaymentsService(gpdClient, tableClientConfiguration()));

    try {
      paymentsService.getOrganizationReceipts(
              "org123456", null, null, null, null, 50, 4, null, null);
    } catch (AppException e) {
      assertEquals("The page number is too big for the filtered elements", e.getMessage());
      assertEquals(HttpStatus.BAD_REQUEST, e.getHttpStatus());
    }
  }

  @Test
  void getOrganizationReceipts_debtor_filter() throws Exception {
    PaymentsService paymentsService =
        spy(new PaymentsService(gpdClient, tableClientConfiguration()));

    PaymentsResult<ReceiptModelResponse> res =
        paymentsService.getOrganizationReceipts(
            "org123456", "debtor5", null, null, null, 0, 100, null, null);
    assertNotNull(res);
    assertEquals(1, res.getResults().size());
    assertEquals(0, res.getCurrentPageNumber());
  }

  @Test
  void getOrganizationReceipts_PAID_service_filter() throws Exception {
    PaymentsService paymentsService =
        spy(new PaymentsService(gpdClient, tableClientConfiguration()));

    PaymentsResult<ReceiptModelResponse> res =
        paymentsService.getOrganizationReceipts(
                "org123456", null, "05", null, null, 0, 100, null, null);
    assertNotNull(res);
    assertEquals(1, res.getResults().size());
    assertEquals(0, res.getCurrentPageNumber());
  }

  @Test
  void getOrganizationReceipts_CREATED_PO_PAID_service_filter() throws Exception {
    PaymentsService paymentsService =
        spy(new PaymentsService(gpdClient, tableClientConfiguration()));

    PaymentsResult<ReceiptModelResponse> res =
        paymentsService.getOrganizationReceipts(
                "org123456", null, "11", null, null, 0, 100, null, null);
    assertNotNull(res);
    assertEquals(1, res.getResults().size());
    assertEquals(0, res.getCurrentPageNumber());
  }

  @Test
  void getOrganizationReceipts_CREATED_PO_UNPAID_service_filter() throws Exception {
    PaymentsService paymentsService =
        spy(new PaymentsService(gpdClient, tableClientConfiguration()));

    // precondition
    PaymentsModelResponse paymentModel =
        MockUtil.readModelFromFile("gpd/getPaymentOption_PO_UNPAID.json", PaymentsModelResponse.class);
    when(gpdClient.getPaymentOption(anyString(), anyString())).thenReturn(paymentModel);

    PaymentsResult<ReceiptModelResponse> res =
        paymentsService.getOrganizationReceipts(
                "org123456", null, "11", null, null, 0, 100, null, null);
    assertNotNull(res);
    assertEquals(0, res.getResults().size());
    assertEquals(0, res.getCurrentPageNumber());
  }

  @Test
  void getOrganizationReceipts_CREATED_PO_PAID_debtorOrIuv_Iuv_filter() throws Exception {
    PaymentsService paymentsService =
            spy(new PaymentsService(gpdClient, tableClientConfiguration()));

    PaymentsResult<ReceiptModelResponse> res =
            paymentsService.getOrganizationReceipts(
                    "org123456", null, null, null, null, 0, 100, null, "03");
    assertNotNull(res);
    assertEquals(1, res.getResults().size());
    assertEquals(0, res.getCurrentPageNumber());
  }

  @Test
  void getOrganizationReceipts_CREATED_PO_PAID_debtorOrIuv_debtor_filter() throws Exception {
    PaymentsService paymentsService =
            spy(new PaymentsService(gpdClient, tableClientConfiguration()));

    PaymentsResult<ReceiptModelResponse> res =
            paymentsService.getOrganizationReceipts(
                    "org123456", null, null, null, null, 0, 100, null, "de");
    assertNotNull(res);
    assertEquals(15, res.getResults().size());
    assertEquals(0, res.getCurrentPageNumber());
  }

  @Test
  void getOrganizationReceipts_all_filters() throws Exception {
    PaymentsService paymentsService =
        spy(new PaymentsService(gpdClient, tableClientConfiguration()));

    PaymentsResult<ReceiptModelResponse> res =
        paymentsService.getOrganizationReceipts(
            "org123456", "debtor5", "05", "2021-09-30", "2023-10-02", 0, 100, null, null);
    assertNotNull(res);
    assertEquals(1, res.getResults().size());
    assertEquals(0, res.getCurrentPageNumber());
  }

  @Test
  void getOrganizationReceiptsByPaymentDate() throws Exception {
    PaymentsService paymentsService =
            spy(new PaymentsService(gpdClient, tableClientConfiguration()));

    PaymentsResult<ReceiptModelResponse> res =
            paymentsService.getOrganizationReceipts(
                    "org123456", "debtor5", "05", "2021-09-30", null, 0, 100, null, null);
    assertNotNull(res);
    assertEquals(1, res.getResults().size());
    assertEquals(0, res.getCurrentPageNumber());

    res = paymentsService.getOrganizationReceipts(
                    "org123456", "debtor5", "05", null, "2023-10-02", 0, 100, null, null);
    assertNotNull(res);
    assertEquals(1, res.getResults().size());
    assertEquals(0, res.getCurrentPageNumber());
  }

  @Test
  void getOrganizationReceipts_debtor_not_exist() throws Exception {
    PaymentsService paymentsService =
        spy(new PaymentsService(gpdClient, tableClientConfiguration()));

    PaymentsResult<ReceiptModelResponse> res =
        paymentsService.getOrganizationReceipts(
            "org123456", "debtor15", null, null, null, 0, 100, null, null);
    assertNotNull(res);
    assertEquals(0, res.getResults().size());
  }

  @Test
  void getOrganizationReceipts_too_many_elements() throws Exception {
    PaymentsService paymentsService =
            spy(new PaymentsService(gpdClient, tableClientConfiguration()));
    try {
      paymentsService.getOrganizationReceipts(
              "org123456", null, null, null, null, 0, 150, null, null);
    } catch (AppException e) {
      assertEquals(HttpStatus.BAD_REQUEST, e.getHttpStatus());
    }
  }

  @Test
  void getOrganizationReceipts_500() throws Exception {
    String wrongStorageConnectionString =
        String.format(
            "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;TableEndpoint=http://%s:%s/devstoreaccount1;QueueEndpoint=http://%s:%s/devstoreaccount1;BlobEndpoint=http://%s:%s/devstoreaccount1",
            azurite.getContainerIpAddress(),
            azurite.getMappedPort(10002),
            azurite.getContainerIpAddress(),
            azurite.getMappedPort(10001),
            azurite.getContainerIpAddress(),
            azurite.getMappedPort(10000));
    TableClient tableClient =
        new TableClientBuilder()
            .connectionString(wrongStorageConnectionString)
            .tableName("receiptsTable")
            .buildClient();
    PaymentsService paymentsService = spy(new PaymentsService(gpdClient, tableClient));

    try {
      paymentsService.getOrganizationReceipts(
              "org123456", null, null, null, null, 0, 100, null, null);
    } catch (AppException e) {
      assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, e.getHttpStatus());
    }
  }

  /** GPD CHECK */
  @Test
  void getGPDCheckedReceiptsList() throws Exception {
    PaymentsService paymentsService =
        spy(new PaymentsService(gpdClient, tableClientConfiguration()));

    Map<String, Object> properties = new HashMap<>();
    properties.put(STATUS_PROPERTY, Status.PAID.name());

    TableEntity te1 = new TableEntity("111", "aaa");
    te1.setProperties(properties);
    tableClientConfiguration().createEntity(te1);
    TableEntity te2 = new TableEntity("222", "bbb");
    te2.setProperties(properties);
    tableClientConfiguration().createEntity(te2);
    TableEntity te3 = new TableEntity("333", "ccc");
    te3.setProperties(properties);
    tableClientConfiguration().createEntity(te3);

    List<ReceiptModelResponse> receipts = new ArrayList<>();
    ReceiptModelResponse re1 = ReceiptModelResponse.builder()
            .organizationFiscalCode("111")
            .iuv("aaa")
            .debtor("debtor1")
            .paymentDateTime("2022-10-01T17:48:22")
            .status(Status.PAID.name())
            .build();
    re1.setStatus(Status.PAID.name());
    ReceiptModelResponse re2 = ReceiptModelResponse.builder()
            .organizationFiscalCode("222")
            .iuv("bbb")
            .debtor("debtor1")
            .paymentDateTime("2022-10-01T17:48:22")
            .status(Status.PAID.name())
            .build();
    ReceiptModelResponse re3 = ReceiptModelResponse.builder()
            .organizationFiscalCode("333")
            .iuv("ccc")
            .status(Status.PAID.name())
            .build();
    receipts.add(re1);
    receipts.add(re2);
    receipts.add(re3);
    PaymentsResult<ReceiptModelResponse> mock = new PaymentsResult<>();
    mock.setCurrentPageNumber(0);
    mock.setLength(receipts.size());
    mock.setResults(receipts);

    List<ReceiptModelResponse> result =
        paymentsService.getGPDCheckedReceiptsList(receipts, tableClientConfiguration());

    assertEquals(mock.getResults().size(), result.size());
  }

  @Test
  void getGPDCheckedReceiptsList_GPDCheckFail() throws Exception {
    PaymentsService paymentsService =
        spy(new PaymentsService(gpdClient, tableClientConfiguration()));

    Map<String, Object> properties = new HashMap<>();
    properties.put(STATUS_PROPERTY, Status.CREATED.name());

    TableEntity te1 = new TableEntity("111", "aaa");
    te1.setProperties(properties);
    tableClientConfiguration().createEntity(te1);
    TableEntity te2 = new TableEntity("222", "bbb");
    te2.setProperties(properties);
    tableClientConfiguration().createEntity(te2);
    TableEntity te3 = new TableEntity("333", "ccc");
    te3.setProperties(properties);
    tableClientConfiguration().createEntity(te3);

    List<ReceiptModelResponse> receipts = new ArrayList<>();
    ReceiptModelResponse re1 = ReceiptModelResponse.builder().organizationFiscalCode("111").iuv("aaa").build();
    ReceiptModelResponse re2 = ReceiptModelResponse.builder().organizationFiscalCode("222").iuv("bbb").build();
    ReceiptModelResponse re3 = ReceiptModelResponse.builder().organizationFiscalCode("333").iuv("ccc").build();
    receipts.add(re1);
    receipts.add(re2);
    receipts.add(re3);
    PaymentsResult<ReceiptModelResponse> mock = new PaymentsResult<>();
    mock.setCurrentPageNumber(0);
    mock.setLength(receipts.size());
    mock.setResults(receipts);

    // GPD risponde sempre UNPAID
    PaymentsModelResponse paymentModel =
        MockUtil.readModelFromFile(
            "gpd/getPaymentOption_PO_UNPAID.json", PaymentsModelResponse.class);
    when(gpdClient.getPaymentOption(anyString(), anyString())).thenReturn(paymentModel);

    List<ReceiptModelResponse> result =
        paymentsService.getGPDCheckedReceiptsList(receipts, tableClientConfiguration());

    // tutte le ricevute sono state scartate
    assertEquals(0, result.size());
  }

  private TableClient tableClientConfiguration() {
    return new TableClientBuilder()
        .connectionString(storageConnectionString)
        .tableName("receiptsTable")
        .buildClient();
  }
}
