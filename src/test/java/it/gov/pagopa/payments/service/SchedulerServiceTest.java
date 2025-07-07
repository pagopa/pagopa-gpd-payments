package it.gov.pagopa.payments.service;

import com.azure.core.util.Context;
import com.azure.data.tables.TableClient;
import com.azure.data.tables.TableClientBuilder;
import com.azure.storage.queue.QueueClient;
import com.azure.storage.queue.QueueClientBuilder;
import com.azure.storage.queue.models.QueueMessageItem;
import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.RetryNoRetry;
import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.queue.CloudQueue;
import com.microsoft.azure.storage.queue.CloudQueueClient;
import com.microsoft.azure.storage.table.CloudTable;
import com.microsoft.azure.storage.table.CloudTableClient;
import com.microsoft.azure.storage.table.TableRequestOptions;
import feign.FeignException;
import it.gov.pagopa.payments.endpoints.validation.exceptions.PartnerValidationException;
import it.gov.pagopa.payments.entity.ReceiptEntity;
import it.gov.pagopa.payments.mock.*;
import it.gov.pagopa.payments.model.*;
import it.gov.pagopa.payments.model.partner.*;
import it.gov.pagopa.payments.client.GpdClient;
import it.gov.pagopa.payments.client.GpsClient;
import it.gov.pagopa.payments.utils.CustomizedMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.ClassRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.xml.datatype.*;
import java.io.IOException;
import java.net.URISyntaxException;
import java.security.InvalidKeyException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.testcontainers.shaded.org.awaitility.Awaitility.await;

@Testcontainers
@ExtendWith(MockitoExtension.class)
@Slf4j
@SpringBootTest
class SchedulerServiceTest {

  @Autowired private SchedulerService schedulerService;

  @InjectMocks private PartnerService partnerService;

  @Mock private ObjectFactory factory;

  @Mock private GpdClient gpdClient;

  @Mock private GpsClient gpsClient;

  private String genericService = "/xsd/general-service.xsd";
  ResourceLoader resourceLoader = new DefaultResourceLoader();
  Resource resource = resourceLoader.getResource(genericService);

  @Value(value = "${azure.queue.send.invisibilityTime}")
  private Long queueSendInvisibilityTime;
  private final ObjectFactory factoryUtil = new ObjectFactory();

  @Autowired private CustomizedMapper customizedModelMapper;

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

  @Test
  void paSendRTQueueReceiveTestOk() throws DatatypeConfigurationException, IOException, URISyntaxException, InvalidKeyException, StorageException {

    var pService =
            spy(
                    new PartnerService(
                            resource,
                            queueSendInvisibilityTime,
                            factory,
                            gpdClient,
                            gpsClient,
                            tableClientConfiguration(),
                            queueClientConfiguration(),
                            customizedModelMapper));

    var schedService =
            spy(
                    new SchedulerService(
                            5,
                            1L,
                            1L,
                            queueClientConfiguration(),
                            pService));

    // Test preconditions
    PaSendRTReq requestBody = PaSendRTReqMock.getMockDebtor("11111111112222225");

    var e = Mockito.mock(FeignException.class);
    lenient().when(e.getSuppressed()).thenReturn(new Throwable[0]);
    when(gpdClient.receiptPaymentOption(anyString(), anyString(), nullable(PaymentOptionModel.class)))
            .thenThrow(e);
    doReturn(MockUtil.readModelFromFile("gpd/receiptPaymentOption.json", PaymentOptionModelResponse.class))
            .when(pService)
            .getReceiptPaymentOptionScheduler(anyString(), anyString(), anyString(), any(PaymentOptionModel.class), any(ReceiptEntity.class));

    try {
      CloudStorageAccount cloudStorageAccount = CloudStorageAccount.parse(storageConnectionString);
      CloudTableClient cloudTableClient = cloudStorageAccount.createCloudTableClient();
      TableRequestOptions tableRequestOptions = new TableRequestOptions();
      tableRequestOptions.setRetryPolicyFactory(RetryNoRetry.getInstance());
      cloudTableClient.setDefaultRequestOptions(tableRequestOptions);
      CloudTable table = cloudTableClient.getTableReference("receiptsTable");
      table.createIfNotExists();
      CloudQueueClient cloudQueueClient = cloudStorageAccount.createCloudQueueClient();
      CloudQueue queue = cloudQueueClient.getQueueReference("testqueue");
      queue.create();
    } catch (Exception ex) {
      log.info("Error during table creation", e);
    }

    try {
      // Test execution
      queueClientConfiguration().clearMessages();
      assertEquals(0, queueClientConfiguration().receiveMessages(10).stream().toList().size());
      pService.paSendRT(requestBody);
      fail();
    } catch (PartnerValidationException ex) {
      // Test post condition
      assertEquals(1, queueClientConfiguration().peekMessages(10, null, Context.NONE).stream().toList().size());
      assertEquals(PaaErrorEnum.PAA_SEMANTICA, ex.getError());
      schedService.retryFailedPaSendRT();
      await().pollDelay(Duration.ofSeconds(2L)).until(() -> true);
      assertEquals(0, queueClientConfiguration().peekMessages(10, null, Context.NONE).stream().toList().size());
    }
  }

  @Test
  void paSendRTQueueReceiveTestKo() throws DatatypeConfigurationException, IOException, URISyntaxException, InvalidKeyException, StorageException {

    var pService =
            spy(
                    new PartnerService(
                            resource,
                            queueSendInvisibilityTime,
                            factory,
                            gpdClient,
                            gpsClient,
                            tableClientConfiguration(),
                            queueClientConfiguration(),
                            customizedModelMapper));

    var schedService =
            spy(
                    new SchedulerService(
                            5,
                            1L,
                            1L,
                            queueClientConfiguration(),
                            pService));

    // Test preconditions
    PaSendRTReq requestBody = PaSendRTReqMock.getMockDebtor("11111111112222225");

    var e = Mockito.mock(FeignException.class);
    lenient().when(e.getSuppressed()).thenReturn(new Throwable[0]);
    when(gpdClient.receiptPaymentOption(anyString(), anyString(), nullable(PaymentOptionModel.class)))
            .thenThrow(e);
    doThrow(FeignException.class)
            .when(pService)
            .getReceiptPaymentOptionScheduler(anyString(), anyString(), anyString(), any(PaymentOptionModel.class), any(ReceiptEntity.class));

    try {
      CloudStorageAccount cloudStorageAccount = CloudStorageAccount.parse(storageConnectionString);
      CloudTableClient cloudTableClient = cloudStorageAccount.createCloudTableClient();
      TableRequestOptions tableRequestOptions = new TableRequestOptions();
      tableRequestOptions.setRetryPolicyFactory(RetryNoRetry.getInstance());
      cloudTableClient.setDefaultRequestOptions(tableRequestOptions);
      CloudTable table = cloudTableClient.getTableReference("receiptsTable");
      table.createIfNotExists();
      CloudQueueClient cloudQueueClient = cloudStorageAccount.createCloudQueueClient();
      CloudQueue queue = cloudQueueClient.getQueueReference("testqueue");
      queue.create();
    } catch (Exception ex) {
      log.info("Error during table creation", e);
    }

    try {
      // Test execution
      queueClientConfiguration().clearMessages();
      assertEquals(0, queueClientConfiguration().receiveMessages(10).stream().toList().size());
      pService.paSendRT(requestBody);
      fail();
    } catch (PartnerValidationException ex) {
      // Test post condition
      assertEquals(1, queueClientConfiguration().peekMessages(10, null, Context.NONE).stream().toList().size());
      assertEquals(PaaErrorEnum.PAA_SEMANTICA, ex.getError());
      schedService.retryFailedPaSendRT();
      await().pollDelay(Duration.ofSeconds(2L)).until(() -> true);
      assertEquals(1, queueClientConfiguration().peekMessages(10, null, Context.NONE).stream().toList().size());
    }
  }

  @Test
  void paSendRTQueueReceiveTestDequeueCountKo() throws DatatypeConfigurationException, IOException, URISyntaxException, InvalidKeyException, StorageException {

    var pService =
            spy(
                    new PartnerService(
                            resource,
                            queueSendInvisibilityTime,
                            factory,
                            gpdClient,
                            gpsClient,
                            tableClientConfiguration(),
                            queueClientConfiguration(),
                            customizedModelMapper));

    var schedService =
            spy(
                    new SchedulerService(
                            5,
                            1L,
                            1L,
                            queueClientConfiguration(),
                            pService));

    // Test preconditions
    PaSendRTReq requestBody = PaSendRTReqMock.getMockDebtor("11111111112222225");

    var e = Mockito.mock(FeignException.class);
    lenient().when(e.getSuppressed()).thenReturn(new Throwable[0]);
    when(gpdClient.receiptPaymentOption(anyString(), anyString(), nullable(PaymentOptionModel.class)))
            .thenThrow(e);

    try {
      CloudStorageAccount cloudStorageAccount = CloudStorageAccount.parse(storageConnectionString);
      CloudTableClient cloudTableClient = cloudStorageAccount.createCloudTableClient();
      TableRequestOptions tableRequestOptions = new TableRequestOptions();
      tableRequestOptions.setRetryPolicyFactory(RetryNoRetry.getInstance());
      cloudTableClient.setDefaultRequestOptions(tableRequestOptions);
      CloudTable table = cloudTableClient.getTableReference("receiptsTable");
      table.createIfNotExists();
      CloudQueueClient cloudQueueClient = cloudStorageAccount.createCloudQueueClient();
      CloudQueue queue = cloudQueueClient.getQueueReference("testqueue");
      queue.create();
    } catch (Exception ex) {
      log.info("Error during table creation", e);
    }

    try {
      // Test execution
      queueClientConfiguration().clearMessages();
      assertEquals(0, queueClientConfiguration().receiveMessages(10).stream().toList().size());
      pService.paSendRT(requestBody);
      fail();
    } catch (PartnerValidationException ex) {
      // Test post condition
      assertEquals(1, queueClientConfiguration().peekMessages(10, null, Context.NONE).stream().toList().size());
      assertEquals(PaaErrorEnum.PAA_SEMANTICA, ex.getError());
      for(int i = 0; i <= 4; i++) {
        QueueMessageItem receptionMessage = queueClientConfiguration().receiveMessage();
        queueClientConfiguration().updateMessage(
                receptionMessage.getMessageId(),
                receptionMessage.getPopReceipt(),
                "text",
                null
        );
      }
      schedService.retryFailedPaSendRT();
      assertEquals(0, queueClientConfiguration().peekMessages(10, null, Context.NONE).stream().toList().size());
    }
  }

  private TableClient tableClientConfiguration() {
      return new TableClientBuilder()
              .connectionString(storageConnectionString)
              .tableName("receiptsTable")
              .buildClient();
  }

  private QueueClient queueClientConfiguration() {
    return new QueueClientBuilder()
            .connectionString(storageConnectionString)
            .queueName("testqueue")
            .buildClient();
  }
}
