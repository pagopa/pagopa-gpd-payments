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
import it.gov.pagopa.payments.model.enumeration.DeadLetterReason;
import it.gov.pagopa.payments.model.partner.*;
import it.gov.pagopa.payments.client.GpdClient;
import it.gov.pagopa.payments.client.GpsClient;
import it.gov.pagopa.payments.utils.CustomizedMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.ClassRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
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
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.time.Duration;
import java.util.List;

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

  @Mock private DeadLetterService deadLetterService;

  private String genericService = "/xsd/general-service.xsd";
  ResourceLoader resourceLoader = new DefaultResourceLoader();
  Resource resource = resourceLoader.getResource(genericService);

  @Value(value = "${azure.queue.send.invisibilityTime}")
  private Long queueSendInvisibilityTime;
  private final ObjectFactory factoryUtil = new ObjectFactory();

  @Autowired private CustomizedMapper customizedModelMapper;

  @ClassRule
  @Container
  public static GenericContainer<?> azurite =
      new GenericContainer<>(
              DockerImageName.parse("mcr.microsoft.com/azure-storage/azurite:latest"))
          .withCommand(
              "azurite --skipApiVersionCheck --blobHost 0.0.0.0 --queueHost 0.0.0.0 --tableHost 0.0.0.0")
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
  void paSendRTQueueReceiveTestOk()
      throws DatatypeConfigurationException,
          IOException,
          URISyntaxException,
          InvalidKeyException,
          StorageException {

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
                customizedModelMapper,
                List.of(),
                List.of()));

    var schedService =
        spy(
            new SchedulerService(
                5,
                1L,
                1L,
                queueClientConfiguration(),
                pService,
                deadLetterService));

    // Test preconditions
    PaSendRTReq requestBody = PaSendRTReqMock.getMockDebtor("11111111112222225");

    var e = mock(FeignException.class);
    lenient().when(e.getSuppressed()).thenReturn(new Throwable[0]);

    when(
            gpdClient.sendPaymentOptionReceipt(
                anyString(), anyString(), nullable(PaymentOptionModel.class)))
        .thenThrow(e);

    doReturn(
            MockUtil.readModelFromFile(
                "gpd/receiptPaymentOption.json", PaymentOptionModelResponse.class))
        .when(pService)
        .getReceiptPaymentOptionScheduler(
            anyString(),
            anyString(),
            anyString(),
            anyBoolean(),
            any(PaymentOptionModel.class),
            any(ReceiptEntity.class));

    try {
      CloudStorageAccount cloudStorageAccount =
          CloudStorageAccount.parse(storageConnectionString);

      CloudTableClient cloudTableClient =
          cloudStorageAccount.createCloudTableClient();

      TableRequestOptions tableRequestOptions =
          new TableRequestOptions();

      tableRequestOptions.setRetryPolicyFactory(
          RetryNoRetry.getInstance());

      cloudTableClient.setDefaultRequestOptions(
          tableRequestOptions);

      CloudTable table =
          cloudTableClient.getTableReference("receiptsTable");

      table.createIfNotExists();

      CloudQueueClient cloudQueueClient =
          cloudStorageAccount.createCloudQueueClient();

      CloudQueue queue =
          cloudQueueClient.getQueueReference("testqueue");

      queue.create();

    } catch (Exception ex) {
      log.info("Error during table creation", ex);
    }

    // Test execution
    queueClientConfiguration().clearMessages();

    assertEquals(
        0,
        queueClientConfiguration()
            .receiveMessages(10)
            .stream()
            .toList()
            .size());

    PartnerValidationException ex =
        assertThrows(
            PartnerValidationException.class,
            () -> pService.paSendRT(requestBody));

    // Test post condition
    assertEquals(
        1,
        queueClientConfiguration()
            .peekMessages(10, null, Context.NONE)
            .stream()
            .toList()
            .size());

    assertEquals(
        PaaErrorEnum.PAA_SEMANTICA,
        ex.getError());

    schedService.retryFailedPaSendRT();

    await()
        .pollDelay(Duration.ofSeconds(2L))
        .until(() -> true);

    assertEquals(
        0,
        queueClientConfiguration()
            .peekMessages(10, null, Context.NONE)
            .stream()
            .toList()
            .size());
  }

  @Test
  void paSendRTQueueReceiveTestKo()
      throws DatatypeConfigurationException,
          IOException,
          URISyntaxException,
          InvalidKeyException,
          StorageException {

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
                customizedModelMapper,
                List.of(),
                List.of()));

    var schedService =
        spy(
            new SchedulerService(
                5,
                1L,
                1L,
                queueClientConfiguration(),
                pService,
                deadLetterService));

    // Test preconditions
    PaSendRTReq requestBody =
        PaSendRTReqMock.getMockDebtor("11111111112222225");

    var e = mock(FeignException.class);
    lenient().when(e.getSuppressed()).thenReturn(new Throwable[0]);

    when(
            gpdClient.sendPaymentOptionReceipt(
                anyString(), anyString(), nullable(PaymentOptionModel.class)))
        .thenThrow(e);

    doThrow(FeignException.class)
        .when(pService)
        .getReceiptPaymentOptionScheduler(
            anyString(),
            anyString(),
            anyString(),
            anyBoolean(),
            any(PaymentOptionModel.class),
            any(ReceiptEntity.class));

    try {
      CloudStorageAccount cloudStorageAccount =
          CloudStorageAccount.parse(storageConnectionString);

      CloudTableClient cloudTableClient =
          cloudStorageAccount.createCloudTableClient();

      TableRequestOptions tableRequestOptions =
          new TableRequestOptions();

      tableRequestOptions.setRetryPolicyFactory(
          RetryNoRetry.getInstance());

      cloudTableClient.setDefaultRequestOptions(
          tableRequestOptions);

      CloudTable table =
          cloudTableClient.getTableReference("receiptsTable");

      table.createIfNotExists();

      CloudQueueClient cloudQueueClient =
          cloudStorageAccount.createCloudQueueClient();

      CloudQueue queue =
          cloudQueueClient.getQueueReference("testqueue");

      queue.create();

    } catch (Exception ex) {
      log.info("Error during table creation", ex);
    }

    // Test execution
    queueClientConfiguration().clearMessages();

    assertEquals(
        0,
        queueClientConfiguration()
            .receiveMessages(10)
            .stream()
            .toList()
            .size());

    PartnerValidationException ex =
        assertThrows(
            PartnerValidationException.class,
            () -> pService.paSendRT(requestBody));

    // Test post condition
    assertEquals(
        1,
        queueClientConfiguration()
            .peekMessages(10, null, Context.NONE)
            .stream()
            .toList()
            .size());

    assertEquals(
        PaaErrorEnum.PAA_SEMANTICA,
        ex.getError());

    schedService.retryFailedPaSendRT();

    await()
        .pollDelay(Duration.ofSeconds(2L))
        .until(() -> true);

    assertEquals(
        1,
        queueClientConfiguration()
            .peekMessages(10, null, Context.NONE)
            .stream()
            .toList()
            .size());
  }

  @Test
  void paSendRTQueueReceiveTestShouldMoveMessageToDeadLetterWhenDequeueLimitExceeded()
      throws DatatypeConfigurationException,
          IOException,
          URISyntaxException,
          InvalidKeyException,
          StorageException {

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
                customizedModelMapper,
                List.of(),
                List.of()));

    var schedService =
        spy(
            new SchedulerService(
                5,
                1L,
                1L,
                queueClientConfiguration(),
                pService,
                deadLetterService));

    // Test preconditions
    PaSendRTReq requestBody =
        PaSendRTReqMock.getMockDebtor("11111111112222225");

    var e = mock(FeignException.class);
    lenient().when(e.getSuppressed()).thenReturn(new Throwable[0]);

    when(
            gpdClient.sendPaymentOptionReceipt(
                anyString(), anyString(), nullable(PaymentOptionModel.class)))
        .thenThrow(e);

    try {
      CloudStorageAccount cloudStorageAccount =
          CloudStorageAccount.parse(storageConnectionString);

      CloudTableClient cloudTableClient =
          cloudStorageAccount.createCloudTableClient();

      TableRequestOptions tableRequestOptions =
          new TableRequestOptions();

      tableRequestOptions.setRetryPolicyFactory(
          RetryNoRetry.getInstance());

      cloudTableClient.setDefaultRequestOptions(
          tableRequestOptions);

      CloudTable table =
          cloudTableClient.getTableReference("receiptsTable");

      table.createIfNotExists();

      CloudQueueClient cloudQueueClient =
          cloudStorageAccount.createCloudQueueClient();

      CloudQueue queue =
          cloudQueueClient.getQueueReference("testqueue");

      queue.create();

    } catch (Exception ex) {
      log.info("Error during table creation", ex);
    }

    // Test execution
    queueClientConfiguration().clearMessages();

    assertEquals(
        0,
        queueClientConfiguration()
            .receiveMessages(10)
            .stream()
            .toList()
            .size());

    PartnerValidationException ex =
        assertThrows(
            PartnerValidationException.class,
            () -> pService.paSendRT(requestBody));

    // Test post condition
    assertEquals(
        1,
        queueClientConfiguration()
            .peekMessages(10, null, Context.NONE)
            .stream()
            .toList()
            .size());

    assertEquals(
        PaaErrorEnum.PAA_SEMANTICA,
        ex.getError());

    ArgumentCaptor<DeadLetterMessage> deadLetterCaptor =
        ArgumentCaptor.forClass(DeadLetterMessage.class);

    for (int i = 0; i <= 4; i++) {

      QueueMessageItem receptionMessage =
          queueClientConfiguration().receiveMessage();

      String originalContent =
          new String(
              receptionMessage.getBody().toBytes(),
              StandardCharsets.UTF_8);

      queueClientConfiguration()
          .updateMessage(
              receptionMessage.getMessageId(),
              receptionMessage.getPopReceipt(),
              originalContent,
              null);
    }

    schedService.retryFailedPaSendRT();

    verify(deadLetterService)
        .sendToDeadLetter(deadLetterCaptor.capture());

    DeadLetterMessage deadLetterMessage =
        deadLetterCaptor.getValue();

    assertNotNull(deadLetterMessage.getMessageId());

    assertEquals(
        6L,
        deadLetterMessage.getDequeueCount());

    assertEquals(
        DeadLetterReason.MAX_RETRY_ATTEMPTS_REACHED,
        deadLetterMessage.getReason());

    assertNotNull(deadLetterMessage.getDeadLetteredAt());

    assertFalse(
        deadLetterMessage.getOriginalMessage().isBlank());

    assertEquals(
        0,
        queueClientConfiguration()
            .peekMessages(10, null, Context.NONE)
            .stream()
            .toList()
            .size());
  }

  @Test
  void paSendRTQueueReceiveTestShouldKeepMessageWhenDeadLetterPersistenceFails()
      throws DatatypeConfigurationException,
          IOException,
          URISyntaxException,
          InvalidKeyException,
          StorageException {

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
                customizedModelMapper,
                List.of(),
                List.of()));

    var schedService =
        spy(
            new SchedulerService(
                5,
                1L,
                1L,
                queueClientConfiguration(),
                pService,
                deadLetterService));

    // Test preconditions
    PaSendRTReq requestBody =
        PaSendRTReqMock.getMockDebtor("11111111112222225");

    var e = mock(FeignException.class);
    lenient().when(e.getSuppressed()).thenReturn(new Throwable[0]);

    when(
            gpdClient.sendPaymentOptionReceipt(
                anyString(),
                anyString(),
                nullable(PaymentOptionModel.class)))
        .thenThrow(e);

    try {
      CloudStorageAccount cloudStorageAccount =
          CloudStorageAccount.parse(storageConnectionString);

      CloudTableClient cloudTableClient =
          cloudStorageAccount.createCloudTableClient();

      TableRequestOptions tableRequestOptions =
          new TableRequestOptions();

      tableRequestOptions.setRetryPolicyFactory(
          RetryNoRetry.getInstance());

      cloudTableClient.setDefaultRequestOptions(
          tableRequestOptions);

      CloudTable table =
          cloudTableClient.getTableReference("receiptsTable");

      table.createIfNotExists();

      CloudQueueClient cloudQueueClient =
          cloudStorageAccount.createCloudQueueClient();

      CloudQueue queue =
          cloudQueueClient.getQueueReference("testqueue");

      queue.create();

    } catch (Exception ex) {
      log.info("Error during table creation", ex);
    }

    // Test execution
    queueClientConfiguration().clearMessages();

    assertEquals(
        0,
        queueClientConfiguration()
            .receiveMessages(10)
            .stream()
            .toList()
            .size());

    PartnerValidationException ex =
        assertThrows(
            PartnerValidationException.class,
            () -> pService.paSendRT(requestBody));

    // The failed paSendRT must have been stored in the retry queue.
    assertEquals(
        1,
        queueClientConfiguration()
            .peekMessages(10, null, Context.NONE)
            .stream()
            .toList()
            .size());

    assertEquals(
        PaaErrorEnum.PAA_SEMANTICA,
        ex.getError());

    /*
     * Increase the dequeue count up to the retry limit while preserving
     * the original receipt payload.
     */
    for (int i = 0; i <= 4; i++) {

      QueueMessageItem receptionMessage =
          queueClientConfiguration().receiveMessage();

      String originalContent =
          new String(
              receptionMessage.getBody().toBytes(),
              StandardCharsets.UTF_8);

      queueClientConfiguration()
          .updateMessage(
              receptionMessage.getMessageId(),
              receptionMessage.getPopReceipt(),
              originalContent,
              null);
    }

    /*
     * Simulate Blob Storage unavailability. The scheduler must not delete
     * the original queue message when dead-letter persistence fails.
     */
    doThrow(new IllegalStateException("Blob Storage unavailable"))
        .when(deadLetterService)
        .sendToDeadLetter(any(DeadLetterMessage.class));

    schedService.retryFailedPaSendRT();

    verify(deadLetterService)
        .sendToDeadLetter(any(DeadLetterMessage.class));

    /*
     * retryFailedPaSendRT() receives the message with a one-second
     * visibility timeout. Since dead-letter persistence failed, the
     * message was not deleted and must become visible again.
     */
    await()
        .pollDelay(Duration.ofSeconds(2L))
        .until(() -> true);

    assertEquals(
        1,
        queueClientConfiguration()
            .peekMessages(10, null, Context.NONE)
            .stream()
            .toList()
            .size());
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