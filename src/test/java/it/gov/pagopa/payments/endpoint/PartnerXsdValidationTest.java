package it.gov.pagopa.payments.endpoint;

import static it.gov.pagopa.payments.config.RequestLogCapture.arguments;
import static it.gov.pagopa.payments.utils.SoapActions.PA_VERIFY_PAYMENT_NOTICE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import it.gov.pagopa.payments.PaymentsApplication;
import it.gov.pagopa.payments.config.RequestLogCapture;
import it.gov.pagopa.payments.config.WebServicesConfiguration;
import it.gov.pagopa.payments.logging.ApplicationLogCapture;
import it.gov.pagopa.payments.endpoints.validation.exceptions.PartnerValidationException;
import it.gov.pagopa.payments.mock.PaVerifyPaymentNoticeResMock;
import it.gov.pagopa.payments.model.partner.PaVerifyPaymentNoticeRes;
import it.gov.pagopa.payments.model.PaaErrorEnum;
import it.gov.pagopa.payments.model.partner.ObjectFactory;
import it.gov.pagopa.payments.model.partner.StOutcome;
import it.gov.pagopa.payments.service.PartnerService;
import it.gov.pagopa.payments.utils.CommonUtil;
import javax.xml.datatype.DatatypeConfigurationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.ws.transport.TransportConstants;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(classes = {WebServicesConfiguration.class, PaymentsApplication.class})
class PartnerXsdValidationTest {

  @Autowired private WebTestClient webClient;

  @MockBean private PartnerService partnerService;

  private RequestLogCapture logs;
  private ApplicationLogCapture applicationLogs;

  @BeforeEach
  void captureRequestLogs() {
    logs = new RequestLogCapture();
    applicationLogs = new ApplicationLogCapture();
  }

  @AfterEach
  void releaseRequestLogs() {
    logs.close();
    applicationLogs.close();
  }

  @Test
  void shouldGenericErrorWithPaVerifyPaymentNoticeTest() throws Exception {

    Mockito.when(partnerService.paVerifyPaymentNotice(Mockito.any(), Mockito.any()))
        .thenThrow(DatatypeConfigurationException.class);

    String request =
        "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\""
            + " xmlns:paf=\"http://pagopa-api.pagopa.gov.it/pa/paForNode.xsd\"><soapenv:Header/><soapenv:Body><paf:paVerifyPaymentNoticeReq><idPA>77777777777</idPA><idBrokerPA>77777777777</idBrokerPA><idStation>77777777777</idStation><qrCode><fiscalCode>77777777777</fiscalCode><noticeNumber>311111111112222222</noticeNumber></qrCode></paf:paVerifyPaymentNoticeReq></soapenv:Body></soapenv:Envelope>";

    this.webClient
        .post()
        .uri("/partner")
        .header(TransportConstants.HEADER_SOAP_ACTION, PA_VERIFY_PAYMENT_NOTICE)
        .contentType(MediaType.TEXT_XML)
        .bodyValue(request)
        .exchange()
        .expectStatus()
        .is5xxServerError();
    assertSummary(500, "KO");
  }

  @Test
  void shouldGenericErrorExceptionWithPaVerifyPaymentNoticeTest()
      throws Exception {

    Mockito.when(partnerService.paVerifyPaymentNotice(Mockito.any(), Mockito.any()))
        .thenThrow(new RuntimeException("SECRET downstream response"));

    String request =
        "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\""
            + " xmlns:paf=\"http://pagopa-api.pagopa.gov.it/pa/paForNode.xsd\"><soapenv:Header/><soapenv:Body><paf:paVerifyPaymentNoticeReq><idPA>77777777777</idPA><idBrokerPA>77777777777</idBrokerPA><idStation>77777777777</idStation><qrCode><fiscalCode>77777777777</fiscalCode><noticeNumber>311111111112222222</noticeNumber></qrCode></paf:paVerifyPaymentNoticeReq></soapenv:Body></soapenv:Envelope>";

    this.webClient
        .post()
        .uri("/partner")
        .header(TransportConstants.HEADER_SOAP_ACTION, PA_VERIFY_PAYMENT_NOTICE)
        .contentType(MediaType.TEXT_XML)
        .bodyValue(request)
        .exchange()
        .expectStatus()
        .is5xxServerError();
    assertSummary(500, "KO");
  }

  @Test
  void shouldReturnValidResponseWithPaVerifyPaymentNoticeTest()
      throws Exception {

    PaVerifyPaymentNoticeRes responseBody = PaVerifyPaymentNoticeResMock.getMock();
    responseBody.setOutcome(StOutcome.OK);

    Mockito.when(partnerService.paVerifyPaymentNotice(Mockito.any(), Mockito.any()))
        .thenReturn(responseBody);

    String request =
        "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\""
            + " xmlns:paf=\"http://pagopa-api.pagopa.gov.it/pa/paForNode.xsd\"><soapenv:Header/><soapenv:Body><paf:paVerifyPaymentNoticeReq><idPA>88888888888</idPA><idBrokerPA>77777777777</idBrokerPA><idStation>77777777777_1</idStation><qrCode><fiscalCode>88888888888</fiscalCode><noticeNumber>382202100000000101</noticeNumber></qrCode></paf:paVerifyPaymentNoticeReq></soapenv:Body></soapenv:Envelope>";

    this.webClient
        .post()
        .uri("/partner")
        .header(TransportConstants.HEADER_SOAP_ACTION, PA_VERIFY_PAYMENT_NOTICE)
        .contentType(MediaType.TEXT_XML)
        .bodyValue(request)
        .exchange()
        .expectStatus()
        .is2xxSuccessful();
    assertSummary(200, "OK");
  }

  @Test
  void businessExceptionKeepsHttp200ButLogsFailureOnce() throws Exception {
    Mockito.when(partnerService.paVerifyPaymentNotice(Mockito.any(), Mockito.any()))
        .thenThrow(new PartnerValidationException(PaaErrorEnum.PAA_SEMANTICA));

    webClient.post().uri("/partner")
        .header(TransportConstants.HEADER_SOAP_ACTION, PA_VERIFY_PAYMENT_NOTICE)
        .contentType(MediaType.TEXT_XML)
        .bodyValue(validRequest())
        .exchange().expectStatus().isOk()
        .expectBody(String.class).value(body -> {
          assertTrue(body.contains("<outcome>KO</outcome>"));
          assertTrue(body.contains("<faultCode>PAA_SEMANTICA</faultCode>"));
        });

    assertSummary(200, "KO");
    JsonNode logEntry = logs.singleSummary();
    assertEquals(PaaErrorEnum.PAA_SEMANTICA.getFaultCode(),
        logEntry.path("faultCode").textValue());
    assertEquals(PaaErrorEnum.PAA_SEMANTICA.getDescription(),
        logEntry.path("faultDetail").textValue());
  }

  @ParameterizedTest
  @ValueSource(strings = {"ACA", "aca", "GPD", "CUSTOM", ""})
  void summaryProjectsTheSameServiceTypeUsedByTheEndpoint(String serviceType) throws Exception {
    PaVerifyPaymentNoticeRes response = PaVerifyPaymentNoticeResMock.getMock();
    response.setOutcome(StOutcome.OK);
    Mockito.when(partnerService.paVerifyPaymentNotice(Mockito.any(), Mockito.eq(serviceType)))
        .thenReturn(response);

    webClient.post().uri("/partner")
        .header(TransportConstants.HEADER_SOAP_ACTION, PA_VERIFY_PAYMENT_NOTICE)
        .header(CommonUtil.SERVICE_TYPE_HEADER, serviceType)
        .contentType(MediaType.TEXT_XML)
        .bodyValue(validRequest())
        .exchange().expectStatus().isOk();

    JsonNode logEntry = logs.singleSummary();
    assertEquals(serviceType, arguments(logEntry).path("serviceType").textValue());
    assertEquals("OK", logEntry.path("status").asText());
    assertEquals("INFO", logEntry.path("log.level").asText());
    Mockito.verify(partnerService).paVerifyPaymentNotice(Mockito.any(), Mockito.eq(serviceType));
  }

  @Test
  void technicalCauseIsDiagnosedOnceWithoutChangingTheApplicationFault() throws Exception {
    Mockito.when(partnerService.paVerifyPaymentNotice(Mockito.any(), Mockito.any()))
        .thenThrow(new PartnerValidationException(PaaErrorEnum.PAA_SYSTEM_ERROR,
            new IllegalStateException("SECRET downstream response")));
    webClient.post().uri("/partner")
        .header(TransportConstants.HEADER_SOAP_ACTION, PA_VERIFY_PAYMENT_NOTICE)
        .contentType(MediaType.TEXT_XML)
        .bodyValue(validRequest())
        .exchange().expectStatus().isOk()
        .expectBody(String.class).value(body ->
            assertTrue(body.contains("<faultCode>PAA_SYSTEM_ERROR</faultCode>")));
    assertSummary(200, "KO", 1);
    JsonNode logEntry = logs.singleSummary();
    assertEquals(PaaErrorEnum.PAA_SYSTEM_ERROR.getFaultCode(),
        logEntry.path("faultCode").textValue());
    assertEquals(PaaErrorEnum.PAA_SYSTEM_ERROR.getDescription(),
        logEntry.path("faultDetail").textValue());
  }

  @ParameterizedTest
  @CsvSource({
    "paVerifyPaymentNotice,paVerifyPaymentNoticeReq",
    "paGetPayment,paGetPaymentReq",
    "paGetPaymentV2,paGetPaymentV2Request",
    "paDemandPaymentNotice,paDemandPaymentNoticeRequest",
    "paSendRT,paSendRTReq",
    "paSendRTV2,paSendRTV2Request"
  })
  void everySoapOperationProjectsServiceTypeEvenBeforeXsdRejection(String operation, String root)
      throws Exception {
    String request = """
        <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
          xmlns:paf="http://pagopa-api.pagopa.gov.it/pa/paForNode.xsd">
          <soapenv:Body><paf:%s/></soapenv:Body>
        </soapenv:Envelope>
        """.formatted(root);

    webClient.post().uri("/partner")
        .header(TransportConstants.HEADER_SOAP_ACTION, operation)
        .header(CommonUtil.SERVICE_TYPE_HEADER, "ACA")
        .contentType(MediaType.TEXT_XML)
        .bodyValue(request)
        .exchange().expectStatus().isOk()
        .expectBody(String.class).value(body ->
            assertTrue(body.contains("<faultCode>PAA_SINTASSI_XSD</faultCode>")));

    JsonNode logEntry = logs.singleSummary();
    assertEquals(operation, logEntry.path("method").textValue());
    assertEquals("ACA", arguments(logEntry).path("serviceType").textValue());
    assertEquals("KO", logEntry.path("status").textValue());
    Mockito.verifyNoInteractions(partnerService);
  }

  @ParameterizedTest
  @ValueSource(strings = {"PAA_SEMANTICA", "PAA_SYSTEM_ERROR"})
  void returnedApplicationKoIsNotInferredAsSuccessFromHttp200(String faultCode) throws Exception {
    PaVerifyPaymentNoticeRes response = PaVerifyPaymentNoticeResMock.getMock();
    response.setOutcome(StOutcome.KO);
    var fault = new ObjectFactory().createCtFaultBean();
    fault.setId("77777777777");
    fault.setFaultCode(faultCode);
    fault.setFaultString("Business rejection");
    response.setFault(fault);
    Mockito.when(partnerService.paVerifyPaymentNotice(Mockito.any(), Mockito.any()))
        .thenReturn(response);

    webClient.post().uri("/partner")
        .header(TransportConstants.HEADER_SOAP_ACTION, PA_VERIFY_PAYMENT_NOTICE)
        .contentType(MediaType.TEXT_XML)
        .bodyValue(validRequest())
        .exchange().expectStatus().isOk();

    assertSummary(200, "KO");
  }

  @Test
  void xsdRejectionRetainsTheResolvedActionBeforeEndpointInvocation() throws Exception {
    webClient.post().uri("/partner")
        .header(TransportConstants.HEADER_SOAP_ACTION, PA_VERIFY_PAYMENT_NOTICE)
        .contentType(MediaType.TEXT_XML)
        .bodyValue(validRequest().replace("<idPA>77777777777</idPA>", ""))
        .exchange().expectStatus().isOk()
        .expectBody(String.class).value(body ->
            assertTrue(body.contains("<faultCode>PAA_SINTASSI_XSD</faultCode>")));

    assertSummary(200, "KO");
    Mockito.verifyNoInteractions(partnerService);
  }

  @Test
  void restUsesTheSameCompletionBoundaryAndTheMvcInterceptor() throws Exception {
    logs.close();
    logs = new RequestLogCapture(ch.qos.logback.classic.Level.DEBUG);
    webClient.get().uri("/info")
        .header("X-Request-Id", "rest-request")
        .exchange().expectStatus().isOk();

    JsonNode logEntry = logs.singleSummary();
    assertEquals("DEBUG", logEntry.path("log.level").textValue());
    assertEquals("healthCheck", logEntry.path("method").asText());
    assertEquals("rest-request", logEntry.path("correlation_id").asText());
    assertEquals(200, logEntry.path("http_code").intValue());
    assertEquals("OK", logEntry.path("status").asText());
  }

  private void assertSummary(int httpStatus, String operationStatus) throws Exception {
    assertSummary(httpStatus, operationStatus, httpStatus >= 500 ? 1 : 0);
  }

  private void assertSummary(
      int httpStatus, String operationStatus, int expectedDiagnostics) throws Exception {
    JsonNode logEntry = logs.singleSummary();
    boolean systemError = "PAA_SYSTEM_ERROR".equals(logEntry.path("faultCode").asText());
    assertEquals(systemError ? "ERROR" : "INFO", logEntry.path("log.level").asText());
    var diagnostics = applicationLogs.events.stream()
        .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.ERROR)
        .filter(event -> !"HTTP request completed".equals(event.getMessage()))
        .filter(event -> logEntry.path("correlation_id").asText()
            .equals(event.getMDCPropertyMap().get("correlation_id")))
        .toList();
    assertEquals(expectedDiagnostics, diagnostics.size());
    for (var diagnostic : diagnostics) {
      JsonNode encoded = applicationLogs.encodedEvent(diagnostic);
      assertFalse(encoded.path("error.stack_trace").asText().isBlank());
      assertFalse(encoded.path("error.stack_trace").asText().contains("SECRET"));
    }
    assertEquals(PA_VERIFY_PAYMENT_NOTICE, logEntry.path("method").asText());
    assertTrue(logEntry.path("http_code").isIntegralNumber());
    assertEquals(httpStatus, logEntry.path("http_code").intValue());
    assertTrue(logEntry.path("response_time").isIntegralNumber());
    assertTrue(logEntry.path("response_time").longValue() >= 0);
    assertEquals(operationStatus, logEntry.path("status").asText());
    assertFalse(logEntry.has("event_action"));
    assertFalse(logEntry.has("event_outcome"));
    assertFalse(logEntry.path("correlation_id").asText().isEmpty());
    assertFalse(logEntry.has("event.duration"));
    assertFalse(logEntry.has("http.response.status_code"));
    assertFalse(logEntry.has("ctx_soap_fault_code"));
    assertEquals("GPD", arguments(logEntry).path("serviceType").textValue());
    assertFalse(arguments(logEntry).has("amount"));
    assertFalse(arguments(logEntry).has("debtor"));
    assertFalse(arguments(logEntry).has("noticeNumber"));
    if (httpStatus == 200) {
      assertEquals(operationStatus, logEntry.path("ctx_soap_outcome").asText());
    } else {
      assertFalse(logEntry.has("ctx_soap_outcome"));
    }
    if ("KO".equals(operationStatus)) {
      assertFalse(logEntry.path("faultCode").asText().isEmpty());
      assertTrue(logEntry.path("faultDetail").isTextual());
    } else {
      assertFalse(logEntry.has("faultCode"));
      assertFalse(logEntry.has("faultDetail"));
    }
  }

  private String validRequest() {
    return """
        <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
          xmlns:paf="http://pagopa-api.pagopa.gov.it/pa/paForNode.xsd">
          <soapenv:Header/><soapenv:Body><paf:paVerifyPaymentNoticeReq>
            <idPA>77777777777</idPA><idBrokerPA>77777777777</idBrokerPA>
            <idStation>77777777777_1</idStation>
            <qrCode><fiscalCode>77777777777</fiscalCode>
              <noticeNumber>311111111112222222</noticeNumber></qrCode>
          </paf:paVerifyPaymentNoticeReq></soapenv:Body>
        </soapenv:Envelope>
        """;
  }
}
