package it.gov.pagopa.payments.config;

import static it.gov.pagopa.payments.config.LogContext.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.gov.pagopa.payments.controller.receipt.impl.PaymentsController;
import it.gov.pagopa.payments.model.ProblemJson;
import it.gov.pagopa.payments.model.partner.CtFaultBean;
import it.gov.pagopa.payments.model.partner.CtQrCode;
import it.gov.pagopa.payments.model.partner.CtReceipt;
import it.gov.pagopa.payments.model.partner.CtReceiptV2;
import it.gov.pagopa.payments.model.partner.ObjectFactory;
import it.gov.pagopa.payments.model.partner.PaGetPaymentReq;
import it.gov.pagopa.payments.model.partner.PaGetPaymentRes;
import it.gov.pagopa.payments.model.partner.PaSendRTReq;
import it.gov.pagopa.payments.model.partner.PaSendRTV2Request;
import it.gov.pagopa.payments.model.partner.StOutcome;
import it.gov.pagopa.payments.service.PaymentsService;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletRequestEvent;
import javax.xml.bind.JAXBElement;
import javax.xml.datatype.DatatypeFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.MDC;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.stereotype.Repository;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.ws.server.endpoint.annotation.Endpoint;

class RequestLoggingTest {
  private final RequestFilter filter = new RequestFilter();
  private final LoggingAspect aspect = new LoggingAspect();
  private final MockHttpServletRequest request = new MockHttpServletRequest();
  private final MockHttpServletResponse response = new MockHttpServletResponse();
  private Map<String, String> previousMdc;
  private RequestAttributes previousRequest;

  @BeforeEach
  void bindRequest() {
    previousMdc = MDC.getCopyOfContextMap();
    previousRequest = RequestContextHolder.getRequestAttributes();
    MDC.clear();
    MDC.put(CORRELATION_ID, "caller-context");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));
  }

  @AfterEach
  void restoreContext() {
    LogContext.restore(previousMdc);
    RequestContextHolder.setRequestAttributes(previousRequest);
  }

  @Test
  void restSummaryUsesFinalStatusAndOnlyApprovedArguments() throws Exception {
    PaymentsController target = new PaymentsController();
    ReflectionTestUtils.setField(target, "paymentsService", mock(PaymentsService.class));
    PaymentsController controller = proxy(target);
    request.addHeader(HEADER_REQUEST_ID, "request-123");
    request.addHeader("X-Client-Service-Type", "ACA");

    try (LogCapture logs = new LogCapture(RequestFilter.class)) {
      filter.doFilter(request, response, (req, res) -> {
        controller.getOrganizationReceipts(
            "88888888888", 2, 10, "private-debtor", "service", null, null, "01", "private-filter");
        assertEquals("request-123", MDC.get(CORRELATION_ID));
        assertEquals("getOrganizationReceipts", MDC.get(METHOD));
      });
      assertTrue(logs.events().isEmpty(), "A controller return is not HTTP completion");
      assertEquals("caller-context", MDC.get(CORRELATION_ID));
      String startAttribute = (String) ReflectionTestUtils.getField(RequestFilter.class, "START");
      request.setAttribute(startAttribute, System.nanoTime() - TimeUnit.SECONDS.toNanos(2));
      response.setStatus(206);
      complete();

      assertEquals(1, logs.events().size());
      JsonNode event = logs.events().get(0);
      assertEquals("request-123", event.path(CORRELATION_ID).asText());
      assertEquals("request-123", response.getHeader(HEADER_REQUEST_ID));
      assertEquals("getOrganizationReceipts", event.path(METHOD).asText());
      assertEquals("88888888888", event.path(CTX_CREDITOR_ID).asText());
      assertEquals(206, event.path(HTTP_CODE).intValue());
      assertTrue(event.path(HTTP_CODE).isIntegralNumber());
      assertTiming(event);
      assertTrue(event.path(RESPONSE_TIME).longValue() >= 2000);
      assertTrue(event.path(RESPONSE_TIME).longValue() < 10_000,
          "Elapsed time must be milliseconds, not nanoseconds");
      assertEquals(STATUS_OK, event.path(STATUS).asText());
      assertTrue(event.path(ARGS).isTextual());
      JsonNode args = new ObjectMapper().readTree(event.path(ARGS).textValue());
      assertEquals(2, args.path("pageNum").intValue());
      assertEquals(10, args.path("pageSize").intValue());
      assertEquals("ACA", args.path(SERVICE_TYPE).asText());
      assertFalse(event.toString().contains("private-"));
      assertEquals("caller-context", MDC.get(CORRELATION_ID));
    }
  }

  @Test
  void soapBusinessFaultRemainsKoWithHttp200() throws Exception {
    PaGetPaymentReq soap = new PaGetPaymentReq();
    soap.setIdPA("88888888888");
    soap.setIdBrokerPA("broker");
    soap.setIdStation("station");
    CtQrCode qr = new CtQrCode();
    qr.setNoticeNumber("000123");
    soap.setQrCode(qr);
    SoapEndpoint endpoint = proxy(new SoapEndpoint());

    try (LogCapture logs = new LogCapture(RequestFilter.class)) {
      filter.doFilter(request, response, (req, res) -> endpoint.paGetPayment(soap));
      complete();
      JsonNode event = logs.events().get(0);
      assertEquals("paGetPayment", event.path(METHOD).asText());
      assertEquals(200, event.path(HTTP_CODE).intValue());
      assertEquals(STATUS_KO, event.path(STATUS).asText());
      assertEquals("KO", event.path(CTX_SOAP_OUTCOME).asText());
      assertEquals("PAA_SEMANTICA", event.path(FAULT_CODE).asText());
      assertEquals("Invalid payment", event.path(FAULT_DETAIL).asText());
      assertEquals("000123", event.path(CTX_NOTICE_NUMBER).asText());
      JsonNode args = new ObjectMapper().readTree(event.path(ARGS).textValue());
      assertEquals("broker", args.path("idBrokerPA").asText());
      assertEquals("station", args.path("idStation").asText());
      assertEquals("GPD", args.path(SERVICE_TYPE).asText());
    }
  }

  @ParameterizedTest
  @CsvSource({
      "422,true,INVALID_REQUEST,INFO",
      "503,false,INVALID_REQUEST,INFO",
      "500,true,PAA_SYSTEM_ERROR,ERROR"
  })
  void restFaultMetadataMustMatchFinalResponse(
      int finalStatus, boolean retained, String faultCode, String level) throws Exception {
    try (LogCapture logs = new LogCapture(RequestFilter.class)) {
      filter.doFilter(request, response, (req, res) ->
          aspect.recordErrorResponse(ResponseEntity
              .status("PAA_SYSTEM_ERROR".equals(faultCode) ? 500 : 422).body(
                  ProblemJson.builder().title(faultCode).detail("Invalid value").build())));
      response.setStatus(finalStatus);
      complete();
      JsonNode event = logs.events().get(0);
      assertEquals(STATUS_KO, event.path(STATUS).asText());
      assertEquals(finalStatus, event.path(HTTP_CODE).intValue());
      assertEquals(level, event.path(LOG_LEVEL).asText());
      assertEquals(retained, event.has(FAULT_CODE));
      assertEquals(retained, event.has(FAULT_DETAIL));
      if (retained) {
        assertEquals(faultCode, event.path(FAULT_CODE).asText());
        assertEquals("Invalid value", event.path(FAULT_DETAIL).asText());
      }
    }
  }

  @ParameterizedTest
  @CsvSource({
      "/actuator/health/liveness,200,DEBUG,liveness",
      "/actuator/health/readiness,200,DEBUG,readiness",
      "/actuator/health/readiness,503,WARN,readiness",
      "/actuator/health/readiness-extra,200,INFO,"
  })
  void probeLevelsApplyOnlyToExactPaths(
      String path, int status, String level, String method) throws Exception {
    request.setRequestURI(path);
    try (LogCapture logs = new LogCapture(RequestFilter.class)) {
      filter.doFilter(request, response, (req, res) -> response.setStatus(status));
      complete();
      JsonNode event = logs.events().get(0);
      assertEquals(level, event.path(LOG_LEVEL).asText());
      assertEquals(method == null ? "" : method, event.path(METHOD).asText());
      assertEquals(status >= 400 ? STATUS_KO : STATUS_OK, event.path(STATUS).asText());
    }
  }

  @Test
  void escapingFailurePreservesExceptionAndRestoresMdcWithoutStaleFaults() throws Exception {
    request.addHeader(HEADER_REQUEST_ID, "invalid request id");
    IOException failure = new IOException("Request failed");
    try (LogCapture logs = new LogCapture(RequestFilter.class)) {
      assertSame(failure, assertThrows(IOException.class, () ->
          filter.doFilter(request, response, (req, res) -> {
            LogContext.fields(request).put(FAULT_CODE, "stale").put(FAULT_DETAIL, "stale");
            throw failure;
          })));
      assertEquals("caller-context", MDC.get(CORRELATION_ID));
      UUID.fromString(response.getHeader(HEADER_REQUEST_ID));
      response.setStatus(500);
      complete();
      JsonNode diagnostic = logs.events().stream()
          .filter(event -> event.has(ERROR_TYPE)).findFirst().orElseThrow();
      assertEquals(IOException.class.getName(), diagnostic.path(ERROR_TYPE).asText());
      assertEquals("Request failed", diagnostic.path(ERROR_MESSAGE).asText());
      JsonNode summary = logs.events().get(logs.events().size() - 1);
      assertEquals(STATUS_KO, summary.path(STATUS).asText());
      assertFalse(summary.has(FAULT_CODE));
      assertFalse(summary.has(FAULT_DETAIL));
    }
  }

  @Test
  void repositoryEventsKeepJsonStringDependencyWithoutAnHttpCode() {
    SampleRepository repository = proxy(new SampleRepository());
    MDC.put(METHOD, "enclosingApi");
    try (LogCapture logs = new LogCapture(LoggingAspect.class)) {
      assertEquals("result", repository.find());
      assertEquals(1, logs.events().size());
      JsonNode event = logs.events().get(0);
      JsonNode dependency = LogCapture.dependency(event);
      assertEquals("SampleRepository.find", dependency.path(NAME).asText());
      assertEquals("find", dependency.path(PATH).asText());
      assertFalse(event.has(DEPENDENCY));
      assertFalse(event.has(METHOD));
      assertEquals("enclosingApi", MDC.get(METHOD));
      assertEquals(STATUS_OK, event.path(STATUS).asText());
      assertFalse(event.has(HTTP_CODE));
      assertFalse(dependency.has(HTTP_CODE));
      assertTiming(event);
    }
  }

  @Repository
  public static class SampleRepository {
    public String find() {
      return "result";
    }
  }

  @ParameterizedTest
  @CsvSource({
      "false,2026-10-26T12:13:14.123,false",
      "true,2026-10-26T12:13:14.123,false",
      "false,2026-10-26T12:13:14.123+02:00,true",
      "true,2026-10-26T12:13:14.123+02:00,true"
  })
  void sendReceiptSummaryIncludesOnlyApprovedArguments(
      boolean v2, String date, boolean standIn) throws Exception {
    var calendar = DatatypeFactory.newInstance().newXMLGregorianCalendar(date);
    SoapEndpoint endpoint = proxy(new SoapEndpoint());
    request.addHeader("X-Client-Service-Type", "ACA");
    try (LogCapture logs = new LogCapture(RequestFilter.class)) {
      filter.doFilter(request, response, (req, res) -> {
        if (v2) {
          CtReceiptV2 receipt = new CtReceiptV2();
          receipt.setReceiptId("");
          receipt.setIdPSP("000123");
          receipt.setCreditorReferenceId("000987");
          receipt.setNoticeNumber("000456");
          receipt.setPaymentDateTime(calendar);
          receipt.setStandIn(standIn);
          receipt.setIdBundle("000bundle");
          receipt.setDescription("private-description");
          receipt.setPaymentNote("private-note");
          PaSendRTV2Request soap = new PaSendRTV2Request();
          soap.setIdBrokerPA("broker");
          soap.setIdStation("station");
          soap.setReceipt(receipt);
          endpoint.paSendRTV2(new ObjectFactory().createPaSendRTV2Request(soap));
        } else {
          CtReceipt receipt = new CtReceipt();
          receipt.setReceiptId("");
          receipt.setIdPSP("000123");
          receipt.setCreditorReferenceId("000987");
          receipt.setNoticeNumber("000456");
          receipt.setPaymentDateTime(calendar);
          receipt.setStandIn(standIn);
          receipt.setDescription("private-description");
          PaSendRTReq soap = new PaSendRTReq();
          soap.setIdBrokerPA("broker");
          soap.setIdStation("station");
          soap.setReceipt(receipt);
          endpoint.paSendRT(new ObjectFactory().createPaSendRTReq(soap));
        }
        assertNull(MDC.get("receiptId"));
      });
      complete();
      assertEquals(1, logs.events().size());
      JsonNode event = logs.events().get(0);
      assertTrue(event.path(ARGS).isTextual());
      JsonNode args = new ObjectMapper().readTree(event.path(ARGS).textValue());
      assertEquals(v2 ? 9 : 8, args.size());
      assertEquals("broker", args.path("idBrokerPA").asText());
      assertEquals("station", args.path("idStation").asText());
      assertEquals("", args.path("receiptId").textValue());
      assertEquals("000123", args.path("idPSP").textValue());
      assertEquals("000987", args.path("creditorReferenceId").textValue());
      assertEquals(date, args.path("paymentDateTime").textValue());
      assertTrue(args.path("standIn").isBoolean());
      assertEquals(standIn, args.path("standIn").booleanValue());
      assertEquals("ACA", args.path(SERVICE_TYPE).textValue());
      assertEquals(v2, args.has("idBundle"));
      if (v2) {
        assertEquals("000bundle", args.path("idBundle").textValue());
      }
      assertEquals("000987", event.path(CTX_IUV).asText());
      assertEquals("000456", event.path(CTX_NOTICE_NUMBER).asText());
      assertFalse(event.toString().contains("private-"));
    }
  }

  @ParameterizedTest
  @CsvSource({"false,false", "false,true", "true,false", "true,true"})
  void sendReceiptOmitsNullFieldsWithoutSuppressingHeaderArguments(
      boolean v2, boolean receiptPresent) throws Exception {
    SoapEndpoint endpoint = proxy(new SoapEndpoint());
    try (LogCapture logs = new LogCapture(RequestFilter.class)) {
      filter.doFilter(request, response, (req, res) -> {
        if (v2) {
          PaSendRTV2Request soap = new PaSendRTV2Request();
          soap.setIdBrokerPA("broker");
          soap.setIdStation("station");
          if (receiptPresent) {
            soap.setReceipt(new CtReceiptV2());
          }
          endpoint.paSendRTV2(new ObjectFactory().createPaSendRTV2Request(soap));
        } else {
          PaSendRTReq soap = new PaSendRTReq();
          soap.setIdBrokerPA("broker");
          soap.setIdStation("station");
          if (receiptPresent) {
            soap.setReceipt(new CtReceipt());
          }
          endpoint.paSendRT(new ObjectFactory().createPaSendRTReq(soap));
        }
      });
      complete();
      JsonNode args = new ObjectMapper().readTree(logs.events().get(0).path(ARGS).textValue());
      assertEquals(3, args.size());
      assertEquals("broker", args.path("idBrokerPA").asText());
      assertEquals("station", args.path("idStation").asText());
      assertEquals("GPD", args.path(SERVICE_TYPE).asText());
    }
  }

  private void complete() {
    filter.requestLoggingListener().getListener().requestDestroyed(
        new ServletRequestEvent(new MockServletContext(), request));
  }

  private <T> T proxy(T target) {
    AspectJProxyFactory factory = new AspectJProxyFactory(target);
    factory.setProxyTargetClass(true);
    factory.addAspect(aspect);
    return factory.getProxy();
  }

  private static void assertTiming(JsonNode event) {
    assertTrue(event.path(RESPONSE_TIME).isIntegralNumber());
    assertTrue(event.path(RESPONSE_TIME).longValue() >= 0);
  }

  @Endpoint
  public static class SoapEndpoint {
    public void paSendRT(JAXBElement<PaSendRTReq> request) {}

    public void paSendRTV2(JAXBElement<PaSendRTV2Request> request) {}

    public JAXBElement<PaGetPaymentRes> paGetPayment(PaGetPaymentReq request) {
      PaGetPaymentRes result = new PaGetPaymentRes();
      result.setOutcome(StOutcome.KO);
      CtFaultBean fault = new CtFaultBean();
      fault.setFaultCode("PAA_SEMANTICA");
      fault.setDescription("Invalid payment");
      result.setFault(fault);
      return new ObjectFactory().createPaGetPaymentRes(result);
    }
  }
}
