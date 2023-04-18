package it.gov.pagopa.payments.api;

import com.azure.data.tables.TableClient;
import com.azure.data.tables.TableClientBuilder;
import it.gov.pagopa.payments.PaymentsApplication;
import it.gov.pagopa.payments.controller.receipt.impl.PaymentsController;
import it.gov.pagopa.payments.entity.ReceiptEntity;
import it.gov.pagopa.payments.exception.AppError;
import it.gov.pagopa.payments.exception.AppException;
import it.gov.pagopa.payments.model.PaymentsResult;
import it.gov.pagopa.payments.model.ReceiptsInfo;
import it.gov.pagopa.payments.service.GpdClient;
import it.gov.pagopa.payments.service.PaymentsService;
import org.junit.ClassRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = PaymentsApplication.class)
@Testcontainers
@ExtendWith(MockitoExtension.class)
class PaymentsControllerCosmosTest {

  @InjectMocks private PaymentsController paymentsController;

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

  /** GET RECEIPT BY IUV */
  @Test
  void getReceiptByIUV_200() throws Exception {
    // precondition
    ReceiptEntity receipt = new ReceiptEntity("mock", "mock");
    receipt.setDebtor("XML");
    when(getPaymentsService().getReceiptByOrganizationFCAndIUV(anyString(), anyString()))
        .thenReturn(receipt);

    ResponseEntity<String> res = paymentsController.getReceiptByIUV(anyString(), anyString());
    assertEquals(HttpStatus.OK, res.getStatusCode());
  }

  @Test
  void getReceiptByIUV_404() throws Exception {
    // precondition
    doThrow(new AppException(AppError.RECEIPT_NOT_FOUND, "111", "222"))
        .when(getPaymentsService())
        .getReceiptByOrganizationFCAndIUV(anyString(), anyString());
    try {
      paymentsController.getReceiptByIUV(anyString(), anyString());
    } catch (AppException e) {
      assertEquals(HttpStatus.NOT_FOUND, e.getHttpStatus());
    }
  }

  /** GET RECEIPTS */
  @Test
  void getOrganizationReceipts_200() throws Exception {
    // precondition
    PaymentsResult<ReceiptEntity> receipts = new PaymentsResult<ReceiptEntity>();
    receipts.setResults(new ArrayList<ReceiptEntity>());
    when(getPaymentsService().getOrganizationReceipts(
            anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(receipts);

    ResponseEntity<ReceiptsInfo> res =
        paymentsController.getOrganizationReceipts(
            anyString(), anyInt(), anyInt(), anyString(), anyString(), anyString(), anyString());
    assertEquals(HttpStatus.OK, res.getStatusCode());
  }

  @Test
  void getOrganizationReceipts_404() throws Exception {
    // precondition
    doThrow(new AppException(AppError.RECEIPTS_NOT_FOUND, "111", 0))
        .when(getPaymentsService())
        .getOrganizationReceipts(anyString(), anyString(), anyString(), anyString(), anyString());
    try {
      paymentsController.getOrganizationReceipts(
          anyString(), anyInt(), anyInt(), anyString(), anyString(), anyString(), anyString());
    } catch (AppException e) {
      assertEquals(HttpStatus.NOT_FOUND, e.getHttpStatus());
    }
  }

  private PaymentsService getPaymentsService(){
    TableClient tableClient = new TableClientBuilder()
            .connectionString(cosmosConnectionString)
            .tableName("testTable")
            .buildClient();
    return new PaymentsService(gpdClient, tableClient);
  }
}
