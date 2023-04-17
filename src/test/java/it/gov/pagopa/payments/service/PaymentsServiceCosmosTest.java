package it.gov.pagopa.payments.service;

import com.azure.data.tables.TableClient;
import com.azure.data.tables.TableClientBuilder;
import com.azure.data.tables.models.TableEntity;
import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.RetryNoRetry;
import com.microsoft.azure.storage.StorageException;
import it.gov.pagopa.payments.entity.ReceiptEntity;
import it.gov.pagopa.payments.entity.ReceiptEntityCosmos;
import it.gov.pagopa.payments.entity.Status;
import it.gov.pagopa.payments.exception.AppException;
import it.gov.pagopa.payments.mock.MockUtil;
import it.gov.pagopa.payments.model.PaymentsModelResponse;
import it.gov.pagopa.payments.model.PaymentsResult;
import org.junit.ClassRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

//TODO change the tests adapting them to the cosmos table
@Testcontainers
@ExtendWith(MockitoExtension.class)
class PaymentsServiceCosmosTest {

  public final static String DEBTOR_PROPERTY = "debtor";

  public final static String DOCUMENT_PROPERTY = "document";

  public final static String STATUS_PROPERTY = "status";

  public final static String PAYMENT_DATE_PROPERTY = "paymentDate";

  @InjectMocks
  private PaymentsServiceCosmos paymentsService;

  @Mock private GpdClient gpdClient;

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

  @BeforeEach
  void setUp() throws StorageException {
    TableClient tableClient = tableClientConfiguration();
    for (int i = 0; i < 10; i++) {
      TableEntity tableEntity = new TableEntity("org123456", "iuv" + i);
      Map<String, Object> properties = new HashMap<>();
      properties.put(DEBTOR_PROPERTY, "debtor" + i);
      properties.put(DOCUMENT_PROPERTY, "XML" + i);
      properties.put(STATUS_PROPERTY, Status.PAID.name());
      properties.put(PAYMENT_DATE_PROPERTY, "2022-10-01T17:48:22");
      tableEntity.setProperties(properties);
      tableClient.createEntity(tableEntity);
    }
    for (int i = 10; i < 15; i++) {
      TableEntity tableEntity = new TableEntity("org123456", "iuv" + i);
      Map<String, Object> properties = new HashMap<>();
      properties.put(DEBTOR_PROPERTY, "debtor" + i);
      properties.put(DOCUMENT_PROPERTY, "XML" + i);
      properties.put(STATUS_PROPERTY, Status.CREATED.name());
      properties.put(PAYMENT_DATE_PROPERTY, "2022-10-01T17:48:22");
      tableEntity.setProperties(properties);
      tableClient.createEntity(tableEntity);
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

    var paymentsService =
        spy(new PaymentsService(cosmosConnectionString, "receiptsTable", gpdClient));

    ReceiptEntity re = paymentsService.getReceiptByOrganizationFCAndIUV("org123456", "iuv0");
    assertEquals("org123456", re.getPartitionKey());
    assertEquals("iuv0", re.getRowKey());
    assertEquals("debtor0", re.getDebtor());
  }

  @Test
  void getReceiptByOrganizationFCAndIUV_404() throws Exception {
    try {
      paymentsService.getReceiptByOrganizationFCAndIUV("org123456", "iuvx");
    } catch (AppException e) {
      assertEquals(HttpStatus.NOT_FOUND, e.getHttpStatus());
    }
  }

  @Test
  void getReceiptByOrganizationFCAndIUV_500() throws Exception {
    String wrongcosmosConnectionString =
        "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;TableEndpoint=http://%s:%s/devstoreaccount1;QueueEndpoint=http://%s:%s/devstoreaccount1;BlobEndpoint=http://%s:%s/devstoreaccount1";
    TableClient tableClient = new TableClientBuilder()
            .connectionString(wrongcosmosConnectionString)
            .tableName("testTable")
            .buildClient();
    var paymentsService =
        spy(new PaymentsServiceCosmos(gpdClient, tableClient));

    try {
      paymentsService.getReceiptByOrganizationFCAndIUV("org123456", "iuv0");
    } catch (AppException e) {
      assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, e.getHttpStatus());
    }
  }

  /** GET RECEIPTS */
  @Test
  void getOrganizationReceipts_noFilter() throws Exception {

    var paymentsService =
        spy(new PaymentsServiceCosmos(gpdClient, tableClientConfiguration()));

    PaymentsResult<ReceiptEntityCosmos> res =
        paymentsService.getOrganizationReceipts(null, null, "org123456", null, null);
    assertNotNull(res);
    assertEquals(15, res.getResults().size());
  }

  @Test
  void getOrganizationReceipts_page_and_limit_filters() throws Exception {

    var paymentsService =
        spy(new PaymentsServiceCosmos(gpdClient, tableClientConfiguration()));

    PaymentsResult<ReceiptEntityCosmos> res =
        paymentsService.getOrganizationReceipts("5", "0", "org123456", null, null);
    assertNotNull(res);
    assertEquals(5, res.getResults().size());
    assertEquals(0, res.getCurrentPageNumber());
    assertEquals(true, res.isHasMoreResults());
  }

  @Test
  void getOrganizationReceipts_debtor_filter() throws Exception {

    var paymentsService =
        spy(new PaymentsServiceCosmos(gpdClient, tableClientConfiguration()));

    PaymentsResult<ReceiptEntityCosmos> res =
        paymentsService.getOrganizationReceipts(null, null, "org123456", "debtor5", null);
    assertNotNull(res);
    assertEquals(1, res.getResults().size());
    assertEquals(0, res.getCurrentPageNumber());
  }

  @Test
  void getOrganizationReceipts_PAID_service_filter() throws Exception {

    var paymentsService =
        spy(new PaymentsServiceCosmos(gpdClient, tableClientConfiguration()));

    PaymentsResult<ReceiptEntityCosmos> res =
        paymentsService.getOrganizationReceipts(null, null, "org123456", null, "iuv5");
    assertNotNull(res);
    assertEquals(1, res.getResults().size());
    assertEquals(0, res.getCurrentPageNumber());
  }

  @Test
  void getOrganizationReceipts_CREATED_PO_PAID_service_filter() throws Exception {

    var paymentsService =
        spy(new PaymentsServiceCosmos(gpdClient, tableClientConfiguration()));

    // precondition
    PaymentsModelResponse paymentModel =
        MockUtil.readModelFromFile("gpd/getPaymentOption.json", PaymentsModelResponse.class);
    when(gpdClient.getPaymentOption(anyString(), anyString())).thenReturn(paymentModel);

    PaymentsResult<ReceiptEntityCosmos> res =
        paymentsService.getOrganizationReceipts(null, null, "org123456", null, "iuv11");
    assertNotNull(res);
    assertEquals(1, res.getResults().size());
    assertEquals(0, res.getCurrentPageNumber());
  }

  @Test
  void getOrganizationReceipts_CREATED_PO_UNPAID_service_filter() throws Exception {

    var paymentsService =
        spy(new PaymentsServiceCosmos(gpdClient, tableClientConfiguration()));

    // precondition
    PaymentsModelResponse paymentModel =
        MockUtil.readModelFromFile(
            "gpd/getPaymentOption_PO_UNPAID.json", PaymentsModelResponse.class);
    when(gpdClient.getPaymentOption(anyString(), anyString())).thenReturn(paymentModel);

    PaymentsResult<ReceiptEntityCosmos> res =
        paymentsService.getOrganizationReceipts(null, null, "org123456", null, "iuv13");
    assertNotNull(res);
    assertEquals(0, res.getResults().size());
    assertEquals(0, res.getCurrentPageNumber());
  }

  @Test
  void getOrganizationReceipts_all_filters() throws Exception {

    var paymentsService =
        spy(new PaymentsServiceCosmos(gpdClient, tableClientConfiguration()));

    PaymentsResult<ReceiptEntityCosmos> res =
        paymentsService.getOrganizationReceipts("5", "0", "org123456", "debtor5", "iuv5");
    assertNotNull(res);
    assertEquals(1, res.getResults().size());
    assertEquals(0, res.getCurrentPageNumber());
  }

  @Test
  void getOrganizationReceipts_404_page_not_exist() throws Exception {

    var paymentsService =
        spy(new PaymentsServiceCosmos(gpdClient, tableClientConfiguration()));

    try {
      paymentsService.getOrganizationReceipts("5", "3", "org123456", null, null);
    } catch (AppException e) {
      assertEquals(HttpStatus.NOT_FOUND, e.getHttpStatus());
    }
  }

  @Test
  void getOrganizationReceipts_debtor_not_exist() throws Exception {

    var paymentsService =
        spy(new PaymentsServiceCosmos(gpdClient, tableClientConfiguration()));

    PaymentsResult<ReceiptEntityCosmos> res =
        paymentsService.getOrganizationReceipts(null, null, "org123456", "debtor15", null);
    assertNotNull(res);
    assertEquals(0, res.getResults().size());
  }

  @Test
  void getOrganizationReceipts_500() throws Exception {

    String wrongcosmosConnectionString =
            "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;TableEndpoint=http://%s:%s/devstoreaccount1;QueueEndpoint=http://%s:%s/devstoreaccount1;BlobEndpoint=http://%s:%s/devstoreaccount1";
    TableClient tableClient = new TableClientBuilder()
            .connectionString(wrongcosmosConnectionString)
            .tableName("testTable")
            .buildClient();
    var paymentsService =
        spy(new PaymentsServiceCosmos(gpdClient, tableClient));

    try {
      paymentsService.getOrganizationReceipts(null, null, "org123456", null, null);
    } catch (AppException e) {
      assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, e.getHttpStatus());
    }
  }

  /** GPD CHECK */
  @Test
  void getGPDCheckedReceiptsList() throws Exception {

    var paymentsService =
        spy(new PaymentsServiceCosmos(gpdClient, tableClientConfiguration()));

    List<ReceiptEntityCosmos> receipts = new ArrayList<>();
    ReceiptEntityCosmos re1 = new ReceiptEntityCosmos("111", "aaa");
    re1.setStatus(Status.PAID.name());
    ReceiptEntityCosmos re2 = new ReceiptEntityCosmos("222", "bbb");
    re2.setStatus(Status.PAID.name());
    ReceiptEntityCosmos re3 = new ReceiptEntityCosmos("333", "ccc");
    re3.setStatus(Status.PAID.name());
    receipts.add(re1);
    receipts.add(re2);
    receipts.add(re3);

    List<ReceiptEntityCosmos> result = paymentsService.getGPDCheckedReceiptsList(receipts, tableClientConfiguration());

    assertEquals(receipts.size(), result.size());
  }

  @Test
  void getGPDCheckedReceiptsList_GPDCheckFail() throws Exception {

    var paymentsService =
        spy(new PaymentsServiceCosmos(gpdClient, tableClientConfiguration()));

    List<ReceiptEntityCosmos> receipts = new ArrayList<>();
    ReceiptEntityCosmos re1 = new ReceiptEntityCosmos("111", "aaa");
    ReceiptEntityCosmos re2 = new ReceiptEntityCosmos("222", "bbb");
    ReceiptEntityCosmos re3 = new ReceiptEntityCosmos("333", "ccc");
    receipts.add(re1);
    receipts.add(re2);
    receipts.add(re3);

    // GPD risponde sempre UNPAID
    PaymentsModelResponse paymentModel =
        MockUtil.readModelFromFile(
            "gpd/getPaymentOption_PO_UNPAID.json", PaymentsModelResponse.class);
    when(gpdClient.getPaymentOption(anyString(), anyString())).thenReturn(paymentModel);

    List<ReceiptEntityCosmos> result = paymentsService.getGPDCheckedReceiptsList(receipts, tableClientConfiguration());

    // tutte le ricevute sono state scartate
    assertEquals(0, result.size());
  }

  private TableClient tableClientConfiguration() {
    return new TableClientBuilder()
            .connectionString(cosmosConnectionString)
            .tableName("testTable")
            .buildClient();
  }
}
