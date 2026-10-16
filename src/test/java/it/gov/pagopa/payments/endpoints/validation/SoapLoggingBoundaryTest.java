package it.gov.pagopa.payments.endpoints.validation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.elastic.logging.logback.EcsEncoder;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.gov.pagopa.payments.endpoints.PartnerEndpoint;
import it.gov.pagopa.payments.endpoints.validation.exceptions.PartnerValidationException;
import it.gov.pagopa.payments.logging.OperationLogScope;
import it.gov.pagopa.payments.model.PaaErrorEnum;
import it.gov.pagopa.payments.model.partner.*;
import it.gov.pagopa.payments.service.PartnerService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import javax.xml.soap.MessageFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ws.context.DefaultMessageContext;
import org.springframework.ws.soap.saaj.SaajSoapMessage;
import org.springframework.ws.soap.saaj.SaajSoapMessageFactory;
import org.xml.sax.SAXParseException;

class SoapLoggingBoundaryTest {
  private final ObjectFactory factory = new ObjectFactory();
  private final PartnerService partner = mock(PartnerService.class);
  private final PartnerEndpoint endpoint = new PartnerEndpoint(partner, factory);
  private final Logger logger = (Logger) LoggerFactory.getLogger(
      "it.gov.pagopa.payments.endpoints.validation");
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>() {
    @Override protected void append(ILoggingEvent event) {
      event.prepareForDeferredProcessing();
      super.append(event);
    }
  };

  @BeforeEach void setUp() {
    MDC.clear();
    logs.setContext(logger.getLoggerContext());
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach void tearDown() {
    logger.detachAppender(logs);
    logs.stop();
    MDC.clear();
    assertNull(OperationLogScope.currentOrNull());
  }

  @ParameterizedTest
  @ValueSource(strings = {"OK", "KO"})
  void finalResponseDeterminesSummaryWithActualHttpStatus(String outcome) throws Exception {
    MockHttpServletResponse response = run("paSendRT", () -> capture(outcome, null));
    assertEquals(200, response.getStatus());
    var summary = summaries().get(0);
    assertEquals(1, summaries().size());
    assertEquals(outcome, summary.getMDCPropertyMap().get("status"));
    assertEquals(outcome, summary.getMDCPropertyMap().get("soapOutcome"));
    assertEquals("200", summary.getMDCPropertyMap().get("httpCode"));
    assertEquals("paSendRT", summary.getMDCPropertyMap().get("method"));
    EcsEncoder encoder = new EcsEncoder();
    encoder.setContext(logger.getLoggerContext());
    encoder.start();
    var json = new ObjectMapper().readTree(new String(encoder.encode(summary), StandardCharsets.UTF_8));
    assertEquals("SOAP_OPERATION_COMPLETED", json.get("event.code").asText());
    assertTrue(json.get("responseTime").isTextual());
    assertEquals(outcome, json.get("status").asText());
    encoder.stop();
  }

  @ParameterizedTest
  @ValueSource(strings = {"paVerifyPaymentNotice", "paGetPayment", "paGetPaymentV2",
      "paDemandPaymentNotice", "paSendRT", "paSendRTV2"})
  void businessFaultXmlAndHttpContractRemainUnchanged(String action) throws Exception {
    MockHttpServletResponse response = run(action, () -> {
      throw new PartnerValidationException(PaaErrorEnum.PAA_SEMANTICA);
    });
    assertEquals(200, response.getStatus());
    assertTrue(response.getContentAsString().contains("<outcome>KO</outcome>"));
    assertTrue(response.getContentAsString().contains(PaaErrorEnum.PAA_SEMANTICA.getFaultCode()));
    assertEquals(1, summaries().size());
    assertEquals("KO", summaries().get(0).getMDCPropertyMap().get("status"));
    assertEquals(PaaErrorEnum.PAA_SEMANTICA.getFaultCode(),
        summaries().get(0).getMDCPropertyMap().get("error.code"));
    assertEquals(0, diagnostics().size());
  }

  @Test void unsupportedFaultActionStillFailsWithHttp500() throws Exception {
    var response = run("unknown", () -> {
      throw new PartnerValidationException(PaaErrorEnum.PAA_SEMANTICA);
    });
    assertEquals(500, response.getStatus());
    assertEquals("", response.getContentAsString());
    assertEquals(1, summaries().size());
    assertEquals(1, diagnostics().size());
  }

  @Test void onlyDispatcherDiagnosesTechnicalConversionWithOriginalCause() throws Exception {
    IOException original = new IOException("original sensitive diagnostic retained fully");
    var response = run("paSendRT", () -> {
      new SoapFaultDefinitionExceptionResolver().resolveException(null, null, original);
      assertTrue(diagnostics().isEmpty(), "Resolver only routes; it must not emit");
      var converted = new PartnerValidationException(PaaErrorEnum.PAA_SYSTEM_ERROR, original);
      throw converted;
    });
    assertEquals(200, response.getStatus());
    assertEquals(1, diagnostics().size());
    assertEquals(SoapMessageDispatcher.class.getName(), diagnostics().get(0).getLoggerName());
    assertEquals(original.getMessage(), diagnostics().get(0).getMDCPropertyMap().get("error.message"));
    assertNull(diagnostics().get(0).getThrowableProxy());
    assertEquals(1, summaries().size());
    assertFalse(summaries().get(0).getMDCPropertyMap().containsKey("error.stack_trace"));
  }

  @Test void unexpectedFailureStillReturnsHttp500AndOneSummary() throws Exception {
    var response = run("paSendRT", () -> { throw new IOException("broken"); });
    assertEquals(500, response.getStatus());
    assertEquals(1, summaries().size());
    assertEquals(1, diagnostics().size());
    assertEquals("KO", summaries().get(0).getMDCPropertyMap().get("status"));
  }

  @Test void returnedSystemFaultHasErrorWithoutArtificialStack() throws Exception {
    run("paSendRT", () -> capture("KO", "PAA_SYSTEM_ERROR"));
    assertEquals(1, diagnostics().size());
    var diagnostic = diagnostics().get(0);
    assertEquals(ch.qos.logback.classic.Level.ERROR, diagnostic.getLevel());
    assertFalse(diagnostic.getMDCPropertyMap().containsKey("error.type"));
    assertFalse(diagnostic.getMDCPropertyMap().containsKey("error.stack_trace"));
    assertNull(diagnostic.getThrowableProxy());
    assertEquals(1, summaries().size());
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"false,OK", "true,OK", "false,KO", "true,KO"})
  void typedReceiptArgsAppearOnlyOnFinalSummaryAndPaymentContextSurvives(
      boolean v2, String outcome) throws Exception {
    var response = run("wrong-header", () -> {
      if (v2) {
        var request = new PaSendRTV2Request();
        request.setIdBrokerPA("0001");
        request.setIdStation("0002");
        var receipt = new CtReceiptV2();
        receipt.setNoticeNumber("000123");
        receipt.setFiscalCode("creditor");
        receipt.setCreditorReferenceId("IUV-not-NAV");
        receipt.setReceiptId("receipt-id");
        receipt.setIdBundle("bundle");
        request.setReceipt(receipt);
        when(partner.paSendRTV2(request)).thenAnswer(invocation -> {
          assertEquals("000123", MDC.get("noticeNumber"));
          assertNull(MDC.get("args"));
          new it.gov.pagopa.payments.logging.LogEventWriter(logger).technicalFailure();
          return factory.createPaSendRTV2Response();
        });
        endpoint.paSendRTV2(factory.createPaSendRTV2Request(request));
      } else {
        var request = new PaSendRTReq();
        request.setIdBrokerPA("0001");
        request.setIdStation("0002");
        var receipt = new CtReceipt();
        receipt.setNoticeNumber("000123");
        receipt.setFiscalCode("creditor");
        receipt.setCreditorReferenceId("IUV-not-NAV");
        receipt.setReceiptId("receipt-id");
        request.setReceipt(receipt);
        when(partner.paSendRT(request)).thenAnswer(invocation -> {
          assertEquals("000123", MDC.get("noticeNumber"));
          assertNull(MDC.get("args"));
          new it.gov.pagopa.payments.logging.LogEventWriter(logger).technicalFailure();
          return factory.createPaSendRTRes();
        });
        endpoint.paSendRT(factory.createPaSendRTReq(request));
      }
      capture(outcome, outcome.equals("KO") ? "PAA_PAGAMENTO_DUPLICATO" : null);
    });
    assertEquals(200, response.getStatus());
    var summary = summaries().get(0).getMDCPropertyMap();
    assertEquals(v2 ? "paSendRTV2" : "paSendRT", summary.get("method"));
    assertEquals("000123", summary.get("noticeNumber"));
    assertEquals("creditor", summary.get("creditorFiscalCode"));
    assertTrue(summary.get("args").contains("receipt-id"));
    var args = new com.fasterxml.jackson.databind.ObjectMapper().readTree(summary.get("args"));
    assertEquals("0001", args.get("idBrokerPA").textValue());
    assertEquals("0002", args.get("idStation").textValue());
    assertFalse(args.has("idPA"));
    assertFalse(args.has("noticeNumber"));
    assertFalse(args.has("fiscalCode"));
    assertEquals(outcome, summary.get("status"));
    assertEquals(v2, summary.get("args").contains("idBundle"));
    assertFalse(diagnostics().get(0).getMDCPropertyMap().containsKey("args"));
    assertEquals(summary.get("operationId"), diagnostics().get(0).getMDCPropertyMap().get("operationId"));
    assertNull(MDC.get("noticeNumber"));
    assertNull(MDC.get("args"));
  }

  @Test void validationWarningIsTypedSanitizedAndFollowedByOneSummary() throws Exception {
    var response = run("paSendRT", () ->
        new SoapValidatingInterceptor().handleRequestValidationErrors(null,
            new SAXParseException[] {
              new SAXParseException("cvc-pattern-valid: rejected debtor private", null, null, 12, 34),
              new SAXParseException("raw debtor private", null, null, 1, 2)}));
    assertEquals(200, response.getStatus());
    var validation = logs.list.stream().filter(event ->
        "SOAP_REQUEST_VALIDATION_FAILED".equals(event.getMDCPropertyMap().get("event.code")))
        .findFirst().orElseThrow();
    assertEquals(ch.qos.logback.classic.Level.WARN, validation.getLevel());
    String details = validation.getMDCPropertyMap().get("ctx_details");
    assertTrue(details.contains("cvc-pattern-valid"));
    assertTrue(details.contains("invalid-xml"));
    assertFalse(details.contains("private"));
    assertEquals(1, summaries().size());
    assertFalse(summaries().get(0).getMDCPropertyMap().containsKey("ctx_details"));
  }

  @Test void executionRestoresExactPriorMdc() throws Exception {
    Map<String, String> previous = Map.of("requestId", "caller-id", "args", "stale",
        "noticeNumber", "stale-nav", "error.message", "stale-error");
    MDC.setContextMap(previous);
    run("paSendRT", () -> {
      assertNull(MDC.get("noticeNumber"));
      assertNull(MDC.get("args"));
      assertNull(MDC.get("error.message"));
      capture("OK", null);
    });
    assertEquals(previous, MDC.getCopyOfContextMap());
    assertEquals("caller-id", summaries().get(0).getMDCPropertyMap().get("requestId"));
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
      "paVerifyPaymentNotice,OK", "paGetPayment,OK", "paGetPaymentV2,OK", "paDemandPaymentNotice,OK",
      "paVerifyPaymentNotice,KO", "paGetPayment,KO", "paGetPaymentV2,KO", "paDemandPaymentNotice,KO"})
  void typedEndpointsKeepCorrectIdentifiersThroughSummary(String method, String outcome) throws Exception {
    try (var common = mockStatic(it.gov.pagopa.payments.utils.CommonUtil.class)) {
      common.when(it.gov.pagopa.payments.utils.CommonUtil::getServiceType).thenReturn("GPD");
      var qr = new CtQrCode();
      qr.setNoticeNumber("000123");
      qr.setFiscalCode("qr-creditor");
      run("incorrect-action", () -> {
        switch (method) {
          case "paVerifyPaymentNotice" -> {
            var request = new PaVerifyPaymentNoticeReq();
            request.setIdBrokerPA("0001");
            request.setIdStation("0002");
            request.setIdPA("creditor");
            request.setQrCode(qr);
            endpoint.paVerifyPaymentNotice(factory.createPaVerifyPaymentNoticeReq(request));
          }
          case "paGetPayment" -> {
            var request = new PaGetPaymentReq();
            request.setIdBrokerPA("0001");
            request.setIdStation("0002");
            request.setTransferType(it.gov.pagopa.payments.model.partner.StTransferType.PAGOPA);
            request.setDueDate(javax.xml.datatype.DatatypeFactory.newInstance()
                .newXMLGregorianCalendar("2026-10-16Z"));
            request.setIdPA("creditor");
            request.setQrCode(qr);
            endpoint.paGetPayment(factory.createPaGetPaymentReq(request));
          }
          case "paGetPaymentV2" -> {
            var request = new PaGetPaymentV2Request();
            request.setIdBrokerPA("0001");
            request.setIdStation("0002");
            request.setTransferType(it.gov.pagopa.payments.model.partner.StTransferType.PAGOPA);
            request.setDueDate(javax.xml.datatype.DatatypeFactory.newInstance()
                .newXMLGregorianCalendar("2026-10-16Z"));
            request.setIdPA("creditor");
            request.setQrCode(qr);
            endpoint.paGetPaymentV2(factory.createPaGetPaymentV2Request(request));
          }
          case "paDemandPaymentNotice" -> {
            var request = new PaDemandPaymentNoticeRequest();
            request.setIdBrokerPA("0001");
            request.setIdStation("0002");
            request.setIdServizio("0003");
            request.setIdSoggettoServizio("PRIVATE-SUBJECT");
            request.setIdPA("creditor");
            endpoint.paDemandPaymentNotice(factory.createPaDemandPaymentNoticeRequest(request));
          }
          default -> fail("Unexpected test method");
        }
        assertEquals("creditor", MDC.get("creditorFiscalCode"));
        assertNull(MDC.get("args"));
        logger.info("Intermediate operation");
        capture(outcome, outcome.equals("KO") ? "PAA_PAGAMENTO_DUPLICATO" : null);
      });
      var fields = summaries().get(0).getMDCPropertyMap();
      assertEquals(method, fields.get("method"));
      assertEquals("creditor", fields.get("creditorFiscalCode"));
      assertEquals(method.equals("paDemandPaymentNotice") ? null : "000123", fields.get("noticeNumber"));
      var args = new com.fasterxml.jackson.databind.ObjectMapper().readTree(fields.get("args"));
      assertEquals("0001", args.get("idBrokerPA").textValue());
      assertEquals("0002", args.get("idStation").textValue());
      if (method.startsWith("paGetPayment")) {
        assertEquals("PAGOPA", args.get("transferType").textValue());
        assertEquals("2026-10-16Z", args.get("dueDate").textValue());
        assertEquals(4, args.size());
      } else if (method.equals("paDemandPaymentNotice")) {
        assertEquals("0003", args.get("idServizio").textValue());
        assertEquals(3, args.size());
      } else {
        assertEquals(2, args.size());
      }
      assertEquals(outcome, fields.get("status"));
      assertTrue(logs.list.stream().filter(event -> !"SOAP_OPERATION_COMPLETED".equals(
          event.getMDCPropertyMap().get("event.code")))
          .allMatch(event -> !event.getMDCPropertyMap().containsKey("args")));
    }
  }

  @Test void httpFailureOverridesSuccessfulSoapOutcome() throws Exception {
    var dispatcher = new SoapMessageDispatcher(factory, "intermediario") {
      @Override protected void callService(javax.servlet.http.HttpServletRequest request,
          javax.servlet.http.HttpServletResponse response) throws Exception {
        capture("OK", null);
        response.setStatus(500);
      }
    };
    dispatcher.doService(new MockHttpServletRequest(), new MockHttpServletResponse());
    var fields = summaries().get(0).getMDCPropertyMap();
    assertEquals("OK", fields.get("soapOutcome"));
    assertEquals("KO", fields.get("status"));
    assertEquals("500", fields.get("httpCode"));
    assertFalse(fields.containsKey("error.code"));
  }

  @Test void rejectedTypedReceiptRetainsArgsOnlyForItsSummaryAndDoesNotLeakToNextRequest() {
    var request = new PaSendRTReq();
    var receipt = new CtReceipt();
    receipt.setReceiptId("receipt-id");
    receipt.setNoticeNumber("000123");
    receipt.setFiscalCode("creditor");
    request.setReceipt(receipt);
    when(partner.paSendRT(request)).thenThrow(new PartnerValidationException(PaaErrorEnum.PAA_SEMANTICA));
    run("paSendRT", () -> endpoint.paSendRT(factory.createPaSendRTReq(request)));
    var first = summaries().get(0).getMDCPropertyMap();
    assertEquals("KO", first.get("status"));
    assertTrue(first.get("args").contains("receipt-id"));
    assertEquals("000123", first.get("noticeNumber"));
    assertNull(MDC.get("args"));
    assertNull(MDC.get("noticeNumber"));
    run("paDemandPaymentNotice", () -> capture("OK", null));
    var second = summaries().get(1).getMDCPropertyMap();
    assertFalse(second.containsKey("args"));
    assertFalse(second.containsKey("noticeNumber"));
    assertFalse(second.containsKey("creditorFiscalCode"));
    assertNotEquals(first.get("operationId"), second.get("operationId"));
  }

  private MockHttpServletResponse run(String action, Work work) {
    var dispatcher = new SoapMessageDispatcher(factory, "intermediario") {
      @Override protected void callService(javax.servlet.http.HttpServletRequest request,
          javax.servlet.http.HttpServletResponse response) throws Exception {
        work.run();
      }
    };
    var request = new MockHttpServletRequest();
    request.addHeader("SOAPAction", "\"" + action + "\"");
    var response = new MockHttpServletResponse();
    dispatcher.doService(request, response);
    return response;
  }

  private void capture(String outcome, String fault) throws Exception {
    var message = MessageFactory.newInstance().createMessage();
    var root = message.getSOAPBody().addChildElement("response");
    root.addChildElement("outcome").addTextNode(outcome);
    if (fault != null) {
      root.addChildElement("fault").addChildElement("faultCode").addTextNode(fault);
    }
    var context = new DefaultMessageContext(new SaajSoapMessageFactory(MessageFactory.newInstance()));
    context.setResponse(new SaajSoapMessage(message));
    new SoapValidatingInterceptor().afterCompletion(context, null, null);
  }

  private List<ILoggingEvent> summaries() {
    return logs.list.stream().filter(event -> "SOAP_OPERATION_COMPLETED".equals(
        event.getMDCPropertyMap().get("event.code"))).toList();
  }

  private List<ILoggingEvent> diagnostics() {
    return logs.list.stream().filter(event -> "OPERATION_TECHNICAL_FAILED".equals(
        event.getMDCPropertyMap().get("event.code"))).toList();
  }

  @FunctionalInterface private interface Work { void run() throws Exception; }
}
