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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import it.gov.pagopa.payments.model.ReceiptModelResponse;
import it.gov.pagopa.payments.client.GpdClient;
import org.junit.ClassRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
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
      properties.put(PAYMENT_DATE_PROPERTY, LocalDateTime.now().minusDays(10).toString());
      tableEntity.setProperties(properties);
      tableClientConfiguration().createEntity(tableEntity);
    }
    for (int i = 10; i < 15; i++) {
      TableEntity tableEntity = new TableEntity("org123456", i + "1234567891011");
      Map<String, Object> properties = new HashMap<>();
      properties.put(DEBTOR_PROPERTY, "debtor" + i);
      properties.put(DOCUMENT_PROPERTY, "XML" + i);
      properties.put(STATUS_PROPERTY, Status.CREATED.name());
      properties.put(PAYMENT_DATE_PROPERTY, LocalDateTime.now().minusDays(10).toString());
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
  void getReceiptByOrganizationFCAndIUV() {

    PaymentsService paymentsService =
        buildPaymentsService();

    ArrayList<String> validSegregationCodes = new ArrayList<>(Arrays.asList("00", "89", "90"));
    ReceiptEntity re =
        paymentsService.getReceiptByOrganizationFCAndIUV(
            "org123456", "001234567891011", validSegregationCodes);
    assertEquals("org123456", re.getOrganizationFiscalCode());
    assertEquals("001234567891011", re.getIuv());
    assertEquals("debtor0", re.getDebtor());
  }

  @Test
  void getReceiptByOrganizationFCAndIUV_403() {

    PaymentsService paymentsService =
        buildPaymentsService();

    try {
      ArrayList<String> validSegregationCodes = new ArrayList<>(Arrays.asList("47", "89", "90"));
      paymentsService.getReceiptByOrganizationFCAndIUV(
          "org123456", "0012345678910", validSegregationCodes);
    } catch (AppException e) {
      assertEquals(HttpStatus.FORBIDDEN, e.getHttpStatus());
    }
  }

  @Test
  void getReceiptByOrganizationFCAndIUV_404() {

    PaymentsService paymentsService =
        buildPaymentsService();

    try {
      paymentsService.getReceiptByOrganizationFCAndIUV("org123456", "3000000000000013", null);
    } catch (AppException e) {
      assertEquals(HttpStatus.NOT_FOUND, e.getHttpStatus());
    }
  }

  @Test
  void getReceiptByOrganizationFCAndIUV_500() {

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
      PaymentsService paymentsService = buildPaymentsService(tableClient);

      paymentsService.getReceiptByOrganizationFCAndIUV("org123456", "001234567891011", null);
    } catch (AppException e) {
      assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, e.getHttpStatus());
    }
  }

  /** GET RECEIPTS */
  
  @ParameterizedTest
  @CsvSource({
      "NULL, 100, 15",
      "NULL, 4,   4",
      "debtor15, 100, 0"
  })
  void getOrganizationReceipts_parameterized(
      String debtor,
      int pageSize,
      int expectedSize) {

    PaymentsService paymentsService = buildPaymentsService();

    String resolvedDebtor = "NULL".equals(debtor) ? null : debtor;

    PaymentsResult<ReceiptModelResponse> res =
        paymentsService.getOrganizationReceipts(
            "org123456",
            resolvedDebtor,
            null,
            null,
            null,
            0,
            pageSize,
            null,
            null);

    assertNotNull(res);
    assertEquals(expectedSize, res.getResults().size());
  }

  @Test
  void getOrganizationReceipts_segregationCodesFilter() {
    PaymentsService paymentsService =
        buildPaymentsService();

    ArrayList<String> validSegregationCodes = new ArrayList<>(Arrays.asList("02"));
    PaymentsResult<ReceiptModelResponse> res =
        paymentsService.getOrganizationReceipts(
            "org123456", null, null, null, null, 0, 100, validSegregationCodes, null);
    assertNotNull(res);
    assertEquals(1, res.getResults().size());
  }

  @Test
  void getOrganizationReceipts_segregationCodesFilter2() {
    PaymentsService paymentsService =
        buildPaymentsService();

    ArrayList<String> validSegregationCodes = new ArrayList<>(Arrays.asList("02", "03"));
    PaymentsResult<ReceiptModelResponse> res =
        paymentsService.getOrganizationReceipts(
            "org123456", null, null, null, null, 0, 100, validSegregationCodes, null);
    assertNotNull(res);
    assertEquals(2, res.getResults().size());
  }

  @Test
  void getOrganizationReceipts_segregationCodesFilterEmpty() {
    PaymentsService paymentsService =
            buildPaymentsService();

    ArrayList<String> validSegregationCodes = new ArrayList<>();
    PaymentsResult<ReceiptModelResponse> res =
            paymentsService.getOrganizationReceipts(
                    "org123456", null, null, null, null, 0, 100, validSegregationCodes, null);
    assertNotNull(res);
    assertEquals(15, res.getResults().size());
  }

  @Test
  void getOrganizationReceipts_debtor_filter()  {
    PaymentsService paymentsService =
        buildPaymentsService();

    PaymentsResult<ReceiptModelResponse> res =
        paymentsService.getOrganizationReceipts(
            "org123456", "debtor5", null, null, null, 0, 100, null, null);
    assertNotNull(res);
    assertEquals(1, res.getResults().size());
    assertEquals(0, res.getCurrentPageNumber());
  }

  @Test
  void getOrganizationReceipts_PAID_service_filter()  {
    PaymentsService paymentsService =
        buildPaymentsService();

    PaymentsResult<ReceiptModelResponse> res =
        paymentsService.getOrganizationReceipts(
                "org123456", null, "05", null, null, 0, 100, null, null);
    assertNotNull(res);
    assertEquals(1, res.getResults().size());
    assertEquals(0, res.getCurrentPageNumber());
  }

  @Test
  void getOrganizationReceipts_CREATED_PO_PAID_service_filter()  {
    PaymentsService paymentsService =
        buildPaymentsService();

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
        buildPaymentsService();

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
  void getOrganizationReceipts_CREATED_PO_PAID_debtorOrIuv_Iuv_filter()  {
    PaymentsService paymentsService =
            buildPaymentsService();

    PaymentsResult<ReceiptModelResponse> res =
            paymentsService.getOrganizationReceipts(
                    "org123456", null, null, null, null, 0, 100, null, "03");
    assertNotNull(res);
    assertEquals(1, res.getResults().size());
    assertEquals(0, res.getCurrentPageNumber());
  }

  @Test
  void getOrganizationReceipts_CREATED_PO_PAID_debtorOrIuv_debtor_filter() {
    PaymentsService paymentsService =
            buildPaymentsService();

    PaymentsResult<ReceiptModelResponse> res =
            paymentsService.getOrganizationReceipts(
                    "org123456", null, null, null, null, 0, 100, null, "de");
    assertNotNull(res);
    assertEquals(15, res.getResults().size());
    assertEquals(0, res.getCurrentPageNumber());
  }


  @Test
  void getOrganizationReceipts_all_filters() {
    PaymentsService paymentsService =
        buildPaymentsService();

    ReflectionTestUtils.setField(paymentsService, "receiptsMonthsWindow", 3);

    String from = LocalDate.now().minusMonths(1).toString();
    String to = LocalDate.now().toString();

    PaymentsResult<ReceiptModelResponse> res =
        paymentsService.getOrganizationReceipts(
            "org123456", "debtor5", "05", from, to, 0, 100, null, null);

    assertNotNull(res);
    assertEquals(1, res.getResults().size());
    assertEquals(0, res.getCurrentPageNumber());
  }

  @Test
  void getOrganizationReceiptsByPaymentDate() {
    PaymentsService paymentsService =
        buildPaymentsService();

    ReflectionTestUtils.setField(paymentsService, "receiptsMonthsWindow", 3);

    String from = LocalDate.now().minusMonths(1).toString();
    String to = LocalDate.now().toString();

    PaymentsResult<ReceiptModelResponse> res =
        paymentsService.getOrganizationReceipts(
            "org123456", "debtor5", "05", from, to, 0, 100, null, null);

    assertNotNull(res);
    assertEquals(1, res.getResults().size());
    assertEquals(0, res.getCurrentPageNumber());

    res =
        paymentsService.getOrganizationReceipts(
            "org123456", "debtor5", "05", from, null, 0, 100, null, null);

    assertNotNull(res);
    assertEquals(1, res.getResults().size());
    assertEquals(0, res.getCurrentPageNumber());

    res =
        paymentsService.getOrganizationReceipts(
            "org123456", "debtor5", "05", null, to, 0, 100, null, null);

    assertNotNull(res);
    assertEquals(1, res.getResults().size());
    assertEquals(0, res.getCurrentPageNumber());
  }

  @Test
  void getOrganizationReceipts_500() {
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
    PaymentsService paymentsService = buildPaymentsService(tableClient);

    try {
      paymentsService.getOrganizationReceipts(
              "org123456", null, null, null, null, 0, 100, null, null);
    } catch (AppException e) {
      assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, e.getHttpStatus());
    }
  }

  /** GPD CHECK */
  @Test
  void getGPDCheckedReceiptsList() {
    PaymentsService paymentsService =
        buildPaymentsService();

    Map<String, Object> properties = new HashMap<>();
    properties.put(STATUS_PROPERTY, Status.PAID.name());

    TableEntity te1 = new TableEntity("111", "aaa");
    Map<String, Object> properties1 = new HashMap<>();
    properties1.put(DEBTOR_PROPERTY, "debtor1");
    properties1.put(DOCUMENT_PROPERTY, "XML1");
    properties1.put(STATUS_PROPERTY, Status.PAID.name());
    properties1.put(PAYMENT_DATE_PROPERTY, LocalDateTime.now().minusDays(10).toString());
    te1.setProperties(properties1);

    TableEntity te2 = new TableEntity("222", "bbb");
    Map<String, Object> properties2 = new HashMap<>();
    properties2.put(DEBTOR_PROPERTY, "debtor2");
    properties2.put(DOCUMENT_PROPERTY, "XML2");
    properties2.put(STATUS_PROPERTY, Status.PAID.name());
    properties2.put(PAYMENT_DATE_PROPERTY, LocalDateTime.now().minusDays(10).toString());
    te2.setProperties(properties2);

    TableEntity te3 = new TableEntity("333", "ccc");
    Map<String, Object> properties3 = new HashMap<>();
    properties3.put(DEBTOR_PROPERTY, "debtor3");
    properties3.put(DOCUMENT_PROPERTY, "XML3");
    properties3.put(STATUS_PROPERTY, Status.PAID.name());
    properties3.put(PAYMENT_DATE_PROPERTY, LocalDateTime.now().minusDays(10).toString());
    te3.setProperties(properties3);

    List<TableEntity> receipts = new ArrayList<>();
    receipts.add(te1);
    receipts.add(te2);
    receipts.add(te3);

    List<ReceiptModelResponse> result =
        paymentsService.getGPDCheckedReceiptsList(receipts);

    assertEquals(receipts.size(), result.size());
  }

  @Test
  void getGPDCheckedReceiptsList_GPDCheckFail() throws Exception {
    PaymentsService paymentsService =
        buildPaymentsService();

    Map<String, Object> properties = new HashMap<>();
    properties.put(STATUS_PROPERTY, Status.CREATED.name());

    TableEntity te1 = new TableEntity("111", "aaa");
    Map<String, Object> properties1 = new HashMap<>();
    properties1.put(DEBTOR_PROPERTY, "debtor1");
    properties1.put(DOCUMENT_PROPERTY, "XML1");
    properties1.put(STATUS_PROPERTY, Status.CREATED.name());
    properties1.put(PAYMENT_DATE_PROPERTY, "2022-10-01T17:48:22");
    te1.setProperties(properties1);

    TableEntity te2 = new TableEntity("222", "bbb");
    Map<String, Object> properties2 = new HashMap<>();
    properties2.put(DEBTOR_PROPERTY, "debtor2");
    properties2.put(DOCUMENT_PROPERTY, "XML2");
    properties2.put(STATUS_PROPERTY, Status.CREATED.name());
    properties2.put(PAYMENT_DATE_PROPERTY, "2022-10-01T17:48:22");
    te2.setProperties(properties2);

    TableEntity te3 = new TableEntity("333", "ccc");
    Map<String, Object> properties3 = new HashMap<>();
    properties3.put(DEBTOR_PROPERTY, "debtor3");
    properties3.put(DOCUMENT_PROPERTY, "XML3");
    properties3.put(STATUS_PROPERTY, Status.CREATED.name());
    properties3.put(PAYMENT_DATE_PROPERTY, "2022-10-01T17:48:22");
    te3.setProperties(properties3);

    List<TableEntity> receipts = new ArrayList<>();
    receipts.add(te1);
    receipts.add(te2);
    receipts.add(te3);

    // GPD risponde sempre UNPAID
    PaymentsModelResponse paymentModel =
        MockUtil.readModelFromFile(
            "gpd/getPaymentOption_PO_UNPAID.json", PaymentsModelResponse.class);
    when(gpdClient.getPaymentOption(anyString(), anyString())).thenReturn(paymentModel);

    List<ReceiptModelResponse> result =
        paymentsService.getGPDCheckedReceiptsList(receipts);

    // tutte le ricevute sono state scartate
    assertEquals(0, result.size());
  }
  
  @Test
  void getOrganizationReceipts_defaultLastMonthsWindow_fromAndToAreNull() {
    PaymentsService paymentsService =
        buildPaymentsService();

    ReflectionTestUtils.setField(paymentsService, "receiptsMonthsWindow", 3);

    PaymentsResult<ReceiptModelResponse> res =
        paymentsService.getOrganizationReceipts(
            "org123456", null, null, null, null, 0, 100, null, null);

    assertNotNull(res);
    assertEquals(15, res.getResults().size());
    assertEquals(0, res.getCurrentPageNumber());
  }

  private TableClient tableClientConfiguration() {
    return new TableClientBuilder()
        .connectionString(storageConnectionString)
        .tableName("receiptsTable")
        .buildClient();
  }
  
  @Test
  void getOrganizationReceipts_withExplicitValidRangeWithinConfiguredWindow(){
    PaymentsService paymentsService =
        buildPaymentsService();

    ReflectionTestUtils.setField(paymentsService, "receiptsMonthsWindow", 3);

    String from = LocalDate.now().minusMonths(1).toString();
    String to = LocalDate.now().toString();

    PaymentsResult<ReceiptModelResponse> res =
        paymentsService.getOrganizationReceipts(
            "org123456",
            null,
            null,
            from,
            to,
            0,
            100,
            null,
            null);

    assertNotNull(res);
    assertEquals(15, res.getResults().size());
    assertEquals(0, res.getCurrentPageNumber());
  }
  
  @Test
  void getOrganizationReceipts_dateRangeTooWide_badRequest() {
    PaymentsService paymentsService =
        buildPaymentsService();

    ReflectionTestUtils.setField(paymentsService, "receiptsMonthsWindow", 3);

    try {
      paymentsService.getOrganizationReceipts(
          "org123456",
          null,
          null,
          "2022-01-01",
          "2022-10-01",
          0,
          100,
          null,
          null);
    } catch (AppException e) {
      assertEquals(HttpStatus.BAD_REQUEST, e.getHttpStatus());
      assertEquals("The requested date range is invalid or exceeds the maximum configured number of months", e.getMessage());
    }
  }
  
  @Test
  void getOrganizationReceipts_fromAfterTo_badRequest(){
    PaymentsService paymentsService =
        buildPaymentsService();

    ReflectionTestUtils.setField(paymentsService, "receiptsMonthsWindow", 3);

    try {
      paymentsService.getOrganizationReceipts(
          "org123456",
          null,
          null,
          "2022-12-01",
          "2022-10-01",
          0,
          100,
          null,
          null);
    } catch (AppException e) {
      assertEquals(HttpStatus.BAD_REQUEST, e.getHttpStatus());
    }
  }
  
  @Test
  void getOrganizationReceipts_onlyFromProvided_resolvesCorrectly() {
    PaymentsService paymentsService =
        buildPaymentsService();

    ReflectionTestUtils.setField(paymentsService, "receiptsMonthsWindow", 3);

    String from = LocalDate.now().minusMonths(1).toString();

    PaymentsResult<ReceiptModelResponse> res =
        paymentsService.getOrganizationReceipts(
            "org123456",
            null,
            null,
            from,
            null,
            0,
            100,
            null,
            null);

    assertNotNull(res);
    assertEquals(15, res.getResults().size());
    assertEquals(0, res.getCurrentPageNumber());
  }
  
  @Test
  void getOrganizationReceipts_onlyToProvided_resolvesCorrectly() {
    PaymentsService paymentsService =
        buildPaymentsService();

    ReflectionTestUtils.setField(paymentsService, "receiptsMonthsWindow", 3);

    String to = LocalDate.now().toString();

    PaymentsResult<ReceiptModelResponse> res =
        paymentsService.getOrganizationReceipts(
            "org123456",
            null,
            null,
            null,
            to,
            0,
            100,
            null,
            null);

    assertNotNull(res);
    assertEquals(15, res.getResults().size());
    assertEquals(0, res.getCurrentPageNumber());
  }
  
  
  // HELPER METHODS
  
  private PaymentsService buildPaymentsService() {
	  PaymentsService paymentsService =
			  spy(new PaymentsService(gpdClient, tableClientConfiguration()));
	  ReflectionTestUtils.setField(paymentsService, "receiptsMonthsWindow", 3);
	  return paymentsService;
  }
  
  private PaymentsService buildPaymentsService(TableClient tableClient) {
	  PaymentsService paymentsService =
			  spy(new PaymentsService(gpdClient, tableClient));
	  ReflectionTestUtils.setField(paymentsService, "receiptsMonthsWindow", 3);
	  return paymentsService;
  }
}
