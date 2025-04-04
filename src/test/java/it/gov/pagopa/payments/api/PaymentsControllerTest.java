package it.gov.pagopa.payments.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import it.gov.pagopa.payments.PaymentsApplication;
import it.gov.pagopa.payments.controller.receipt.impl.PaymentsController;
import it.gov.pagopa.payments.entity.ReceiptEntity;
import it.gov.pagopa.payments.exception.AppError;
import it.gov.pagopa.payments.exception.AppException;
import it.gov.pagopa.payments.model.PaymentsResult;
import it.gov.pagopa.payments.model.ReceiptModelResponse;

import java.util.ArrayList;

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

@SpringBootTest(classes = PaymentsApplication.class)
@Testcontainers
@ExtendWith(MockitoExtension.class)
class PaymentsControllerTest {

  @InjectMocks private PaymentsController paymentsController;

  @Mock private PaymentsService paymentsService;

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

  /** GET RECEIPT BY IUV */
  @Test
  void getReceiptByIUV_200() throws Exception {
    // precondition
    ReceiptEntity receipt = new ReceiptEntity("mock", "mock");
    receipt.setDebtor("XML");
    when(paymentsService.getReceiptByOrganizationFCAndIUV(anyString(), anyString(), any(ArrayList.class)))
        .thenReturn(receipt);

    ResponseEntity<String> res = paymentsController.getReceiptByIUV(anyString(), anyString(), anyString());
    assertEquals(HttpStatus.OK, res.getStatusCode());
  }

  /** GET RECEIPT BY IUV with segregation codes */
  @Test
  void getReceiptByIUV_200_SegregationCodes() throws Exception {
    // precondition
    ReceiptEntity receipt = new ReceiptEntity("mock", "mock");
    receipt.setDebtor("XML");
    when(paymentsService.getReceiptByOrganizationFCAndIUV(anyString(), anyString(), any(ArrayList.class)))
            .thenReturn(receipt);

    ResponseEntity<String> res = paymentsController.getReceiptByIUV(anyString(), anyString(), anyString());
    assertEquals(HttpStatus.OK, res.getStatusCode());
  }

  @Test
  void getReceiptByIUV_404() throws Exception {
    // precondition
    doThrow(new AppException(AppError.RECEIPT_NOT_FOUND, "111", "222"))
        .when(paymentsService)
        .getReceiptByOrganizationFCAndIUV(anyString(), anyString(), any(ArrayList.class));
    try {
      paymentsController.getReceiptByIUV(anyString(), anyString(), anyString());
    } catch (AppException e) {
      assertEquals(HttpStatus.NOT_FOUND, e.getHttpStatus());
    }
  }

  /** GET RECEIPTS */
  @Test
  void getOrganizationReceipts_200() throws Exception {
    // precondition
    PaymentsResult<ReceiptModelResponse> receipts = new PaymentsResult<>();
    receipts.setResults(new ArrayList<>());
    when(paymentsService.getOrganizationReceipts(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(), any(ArrayList.class), anyString()))
        .thenReturn(receipts);

    ResponseEntity<PaymentsResult<ReceiptModelResponse>> res =
        paymentsController.getOrganizationReceipts(
            anyString(), anyInt(), anyInt(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    assertEquals(HttpStatus.OK, res.getStatusCode());
  }

  /** GET RECEIPTS with segration codes */
  @Test
  void getOrganizationReceipts_200_SegregationCodes() throws Exception {
    // precondition
    PaymentsResult<ReceiptModelResponse> receipts = new PaymentsResult<>();
    receipts.setResults(new ArrayList<>());
    when(paymentsService.getOrganizationReceipts(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(), any(ArrayList.class), anyString()))
            .thenReturn(receipts);

    ResponseEntity<PaymentsResult<ReceiptModelResponse>> res =
            paymentsController.getOrganizationReceipts(
                    anyString(), anyInt(), anyInt(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    assertEquals(HttpStatus.OK, res.getStatusCode());
  }

  @Test
  void getOrganizationReceipts_404() throws Exception {
    // precondition
//    doThrow(new AppException(AppError.RECEIPTS_NOT_FOUND, "111", 0))
//        .when(paymentsService)
//        .getOrganizationReceipts(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(), any(ArrayList.class), anyString());
    try {
      paymentsController.getOrganizationReceipts(
          anyString(), anyInt(), anyInt(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    } catch (AppException e) {
      assertEquals(HttpStatus.NOT_FOUND, e.getHttpStatus());
    }
  }
}
