package it.gov.pagopa.payments.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.gov.pagopa.payments.controller.BaseController;
import it.gov.pagopa.payments.controller.DeadLetterController;
import it.gov.pagopa.payments.controller.receipt.IPaymentsController;
import it.gov.pagopa.payments.controller.receipt.impl.PaymentsController;
import java.io.StringReader;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

class RequestLogProjectionTest {

  private final ObjectMapper json = new ObjectMapper();

  @ParameterizedTest
  @ValueSource(strings = {
    "paVerifyPaymentNotice", "paGetPayment", "paGetPaymentV2",
    "paSendRT", "paSendRTV2", "paDemandPaymentNotice", "unknown"
  })
  void soapPayloadCannotSupplyTheCommonServiceType(String operation) throws Exception {
    assertEmpty(RequestLogProjection.soapRequest(
        operation, xml("request", "<serviceType>untrusted-xml-value</serviceType>")));
  }

  @ParameterizedTest
  @CsvSource({
    "paVerifyPaymentNotice,paVerifyPaymentNoticeReq",
    "paGetPayment,paGetPaymentReq",
    "paGetPaymentV2,paGetPaymentV2Request"
  })
  void projectsNoticeRequestsWithOperationSpecificFields(String operation, String root)
      throws Exception {
    Element payload = xml(root, """
        <idPA>creditor</idPA><idBrokerPA>broker</idBrokerPA><idStation>station</idStation>
        <qrCode><fiscalCode>other-creditor</fiscalCode><noticeNumber>302000000000000001</noticeNumber>
          <creditorReferenceId>not-an-iuv</creditorReferenceId></qrCode>
        <transferType>PAGOPA</transferType><dueDate>2026-10-22+02:00</dueDate>
        <amount>9.99</amount><paymentNote>private</paymentNote>
        <creditorReferenceId>not-a-request-iuv</creditorReferenceId>
        <debtor><fiscalCode>debtor</fiscalCode></debtor>
        """);

    RequestLogProjection.Projection projection = RequestLogProjection.soapRequest(operation, payload);

    assertEquals("creditor", projection.creditorId());
    assertEquals("302000000000000001", projection.noticeNumber());
    assertNull(projection.iuv());
    ObjectNode expected = object("""
        {"idBrokerPA":"broker","idStation":"station"}
        """);
    if (!operation.equals("paVerifyPaymentNotice")) {
      expected.put("transferType", "PAGOPA").put("dueDate", "2026-10-22+02:00");
    }
    assertEquals(expected, projection.args());
  }

  @Test
  void demandIncludesOnlyCommonFieldsAndServiceId() throws Exception {
    RequestLogProjection.Projection projection =
        RequestLogProjection.soapRequest("paDemandPaymentNotice", xml(
            "paDemandPaymentNoticeRequest", """
            <idPA>creditor</idPA><idBrokerPA>broker</idBrokerPA><idStation>station</idStation>
            <idServizio>00001</idServizio><idSoggettoServizio>private</idSoggettoServizio>
            <datiSpecificiServizioRequest>opaque</datiSpecificiServizioRequest>
            <qrCode><noticeNumber>not-supported</noticeNumber></qrCode>
            """));

    assertEquals("creditor", projection.creditorId());
    assertNull(projection.noticeNumber());
    assertNull(projection.iuv());
    assertEquals(object("""
        {"idBrokerPA":"broker","idStation":"station","idServizio":"00001"}
        """), projection.args());
  }

  @ParameterizedTest
  @ValueSource(strings = {"paSendRT", "paSendRTV2"})
  void receiptUsesCreditorIdentifiersAndMinimalOperationalFields(String operation) throws Exception {
    RequestLogProjection.Projection projection = RequestLogProjection.soapRequest(
        operation, xml(operation.equals("paSendRT") ? "paSendRTReq" : "paSendRTV2Request", """
            <idPA>creditor</idPA><idBrokerPA>broker</idBrokerPA><idStation>station</idStation>
            <receipt>
              <receiptId>receipt</receiptId><fiscalCode>receipt-creditor</fiscalCode>
              <noticeNumber>302000000000000001</noticeNumber><creditorReferenceId>actual-iuv</creditorReferenceId>
              <idPSP>psp</idPSP><paymentDateTime>2026-10-22T10:00:00+02:00</paymentDateTime>
              <standIn>false</standIn><idBundle>bundle</idBundle><idCiBundle>excluded</idCiBundle>
              <paymentAmount>12.34</paymentAmount><description>private</description>
              <debtor><fiscalCode>debtor</fiscalCode><fullName>private</fullName></debtor>
              <payer><fullName>private</fullName></payer>
              <transferList><transfer><IBAN>account</IBAN><MBDAttachment>opaque</MBDAttachment></transfer></transferList>
              <metadata>opaque</metadata><paymentNote>private</paymentNote>
            </receipt>
            """));

    assertEquals("creditor", projection.creditorId());
    assertEquals("302000000000000001", projection.noticeNumber());
    assertEquals("actual-iuv", projection.iuv());
    ObjectNode expected = object("""
        {"idBrokerPA":"broker","idStation":"station","receiptId":"receipt","idPSP":"psp",
         "paymentDateTime":"2026-10-22T10:00:00+02:00","standIn":false}
        """);
    if (operation.equals("paSendRTV2")) {
      expected.put("idBundle", "bundle");
    }
    assertEquals(expected, projection.args());
    assertTrue(projection.args().get("standIn").isBoolean());
    assertFalse(projection.args().has("creditorReferenceId"));
  }

  @Test
  void receiptFallsBackOnlyToReceiptCreditorFiscalCode() throws Exception {
    RequestLogProjection.Projection projection = RequestLogProjection.soapRequest("paSendRT", xml(
        "paSendRTReq", """
        <receipt><fiscalCode>receipt-creditor</fiscalCode>
        <noticeNumber>302000000000000001</noticeNumber>
        <debtor><fiscalCode>debtor</fiscalCode></debtor></receipt>
        """));
    assertEquals("receipt-creditor", projection.creditorId());
    assertNull(projection.iuv());
    assertNull(projection.args());
    assertNull(RequestLogProjection.soapRequest("paSendRT",
        xml("paSendRTReq", "<receipt><debtor><fiscalCode>debtor</fiscalCode></debtor></receipt>"))
        .creditorId());
  }

  @ParameterizedTest
  @CsvSource({"true,true", "1,true", "false,false", "0,false"})
  void projectsXmlBooleanLexicalForms(String value, boolean expected) throws Exception {
    ObjectNode args = RequestLogProjection.soapRequest("paSendRT",
        xml("paSendRTReq", "<receipt><standIn>" + value + "</standIn></receipt>")).args();
    assertTrue(args.get("standIn").isBoolean());
    assertEquals(expected, args.get("standIn").booleanValue());
  }

  @Test
  void absentOrInvalidOptionalBooleanIsNotFalse() throws Exception {
    assertNull(RequestLogProjection.soapRequest("paSendRT", xml("paSendRTReq", "<receipt/>")).args());
    assertNull(RequestLogProjection.soapRequest("paSendRT",
        xml("paSendRTReq", "<receipt><standIn>not-a-boolean</standIn></receipt>")).args());
  }

  @Test
  void domProjectionDoesNotSearchDescendantsOrSerializeStructuredValues() throws Exception {
    RequestLogProjection.Projection projection = RequestLogProjection.soapRequest("paGetPayment",
        xml("paGetPaymentReq", """
            <extra><idPA>private</idPA><idBrokerPA>private</idBrokerPA></extra>
            <idStation><private>nested-private</private></idStation>
            <qrCode><extra><noticeNumber>private</noticeNumber></extra></qrCode>
            """));
    assertEmpty(projection);
  }

  @Test
  void supportsUnqualifiedDomAndPreservesStrings() throws Exception {
    Element payload = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(new InputSource(new StringReader(
            "<paGetPaymentReq><idPA> 001 </idPA><idStation><![CDATA[ station ]]></idStation></paGetPaymentReq>")))
        .getDocumentElement();
    RequestLogProjection.Projection projection = RequestLogProjection.soapRequest("paGetPayment", payload);
    assertEquals(" 001 ", projection.creditorId());
    assertEquals(" station ", projection.args().get("idStation").textValue());
  }

  @ParameterizedTest
  @ValueSource(strings = {"paGetPaymentRes", "paGetPaymentV2Response"})
  void responseIuvComesOnlyFromPaymentData(String root) throws Exception {
    assertEquals("real-iuv", RequestLogProjection.soapResponseIuv(xml(root, """
        <data><creditorReferenceId>real-iuv</creditorReferenceId><noticeNumber>302000000000000001</noticeNumber></data>
        """)));
    assertNull(RequestLogProjection.soapResponseIuv(xml(root, """
        <data><noticeNumber>302000000000000001</noticeNumber>
        <debtor><creditorReferenceId>private</creditorReferenceId></debtor></data>
        <creditorReferenceId>wrong-level</creditorReferenceId>
        """)));
  }

  @ParameterizedTest
  @ValueSource(strings = {
    "paVerifyPaymentNoticeRes", "paDemandPaymentNoticeResponse", "paSendRTRes",
    "paSendRTV2Response", "unknown", "Fault"
  })
  void responsesWithoutSchemaIuvNeverDeriveItFromNoticeNumber(String root) throws Exception {
    assertNull(RequestLogProjection.soapResponseIuv(xml(root, """
        <qrCode><noticeNumber>302000000000000001</noticeNumber></qrCode>
        <creditorReferenceId>unsupported</creditorReferenceId>
        <data><creditorReferenceId>unsupported</creditorReferenceId></data>
        """)));
  }

  @Test
  void unknownAndMissingInputsAreEmpty() throws Exception {
    assertEmpty(RequestLogProjection.soapRequest("unknown", xml("unknown",
        "<idPA>private</idPA><idStation>private</idStation>")));
    assertEmpty(RequestLogProjection.soapRequest(null, xml("unknown", "")));
    assertEmpty(RequestLogProjection.soapRequest("paGetPayment", null));
    assertEmpty(RequestLogProjection.restRequest(null, new MockHttpServletRequest()));
    assertEmpty(RequestLogProjection.restRequest(receiptHandler(), null));
    assertNull(RequestLogProjection.soapResponseIuv(null));
  }

  @Test
  void receiptLookupReadsOnlyPathIdsAndSegregationCodes() throws Exception {
    MockHttpServletRequest request = requestWithPrivateParameters();
    request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
        Map.of("organizationfiscalcode", "creditor", "iuv", "00123"));
    request.setParameter("organizationfiscalcode", "wrong-query-creditor");
    request.setParameter("iuv", "wrong-query-iuv");
    request.setParameter("segregationCodes", "01,02");

    RequestLogProjection.Projection projection = RequestLogProjection.restRequest(receiptHandler(), request);

    assertEquals("creditor", projection.creditorId());
    assertNull(projection.noticeNumber());
    assertEquals("00123", projection.iuv());
    assertEquals(object("{\"segregationCodes\":\"01,02\"}"), projection.args());
  }

  @Test
  void receiptListPreservesSafeStringsAndUsesNumericDefaults() throws Exception {
    MockHttpServletRequest request = requestWithPrivateParameters();
    request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
        Map.of("organizationfiscalcode", "creditor", "iuv", "not-a-list-id"));
    request.setParameter("service", "001");
    request.setParameter("from", "2026-10-01");
    request.setParameter("to", "unbound-date");
    request.setParameter("segregationCodes", "01,02");

    RequestLogProjection.Projection projection = RequestLogProjection.restRequest(listHandler(), request);

    assertEquals("creditor", projection.creditorId());
    assertNull(projection.iuv());
    assertEquals(object("""
        {"pageNum":0,"pageSize":20,"service":"001","from":"2026-10-01",
         "to":"unbound-date","segregationCodes":"01,02"}
        """), projection.args());
    assertTrue(projection.args().get("pageNum").isInt());
    assertTrue(projection.args().get("pageSize").isInt());
  }

  @ParameterizedTest
  @CsvSource({"7,45", "-1,0", "2147483647,100"})
  void projectsBoundIntegerTypesWithoutDuplicatingValidation(int pageNum, int pageSize)
      throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("pageNum", String.valueOf(pageNum));
    request.setParameter("pageSize", String.valueOf(pageSize));
    ObjectNode args = RequestLogProjection.restRequest(listHandler(), request).args();
    assertEquals(pageNum, args.get("pageNum").intValue());
    assertEquals(pageSize, args.get("pageSize").intValue());
  }

  @Test
  void emptyNumericParametersUseMvcDefaultsAndOptionalStringsRemainStrings() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("pageNum", "");
    request.setParameter("pageSize", "");
    request.setParameter("service", "");
    assertEquals(object("{\"pageNum\":0,\"pageSize\":20,\"service\":\"\"}"),
        RequestLogProjection.restRequest(listHandler(), request).args());
  }

  @Test
  void invalidNumericParametersAreOmittedWithoutChangingRequestOrLeakingValues() throws Exception {
    Logger logger = (Logger) LoggerFactory.getLogger(RequestLogProjection.class);
    Level previous = logger.getLevel();
    ListAppender<ILoggingEvent> events = new ListAppender<>();
    events.start();
    logger.addAppender(events);
    logger.setLevel(Level.DEBUG);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("pageNum", "private-invalid-number");
    request.setParameter("pageSize", "2147483648");
    try {
      assertNull(RequestLogProjection.restRequest(listHandler(), request).args());
      assertEquals("private-invalid-number", request.getParameter("pageNum"));
      assertEquals("2147483648", request.getParameter("pageSize"));
      assertEquals(2, events.list.size());
      assertEquals("Request logging projection omitted invalid integer field pageNum",
          events.list.get(0).getFormattedMessage());
      assertEquals("Request logging projection omitted invalid integer field pageSize",
          events.list.get(1).getFormattedMessage());
      assertNull(events.list.get(0).getThrowableProxy());
    } finally {
      logger.detachAppender(events);
      logger.setLevel(previous);
      events.stop();
    }
  }

  @Test
  void deadLetterListUsesActualDefaultAndOnlyMaxMessages() throws Exception {
    HandlerMethod handler = new HandlerMethod(new DeadLetterController(null),
        DeadLetterController.class.getMethod("getDeadLetters", int.class));
    MockHttpServletRequest request = requestWithPrivateParameters();
    assertEquals(object("{\"maxMessages\":50}"),
        RequestLogProjection.restRequest(handler, request).args());
    request.setParameter("maxMessages", "12");
    assertEquals(object("{\"maxMessages\":12}"),
        RequestLogProjection.restRequest(handler, request).args());
    request.setParameter("maxMessages", "invalid-private");
    assertEmpty(RequestLogProjection.restRequest(handler, request));
  }

  @Test
  void numericDefaultsMatchActualControllerAnnotations() throws Exception {
    var parameters = IPaymentsController.class.getMethod("getOrganizationReceipts",
        String.class, int.class, int.class, String.class, String.class, String.class,
        String.class, String.class, String.class).getParameters();
    assertEquals("0", parameters[1].getAnnotation(RequestParam.class).defaultValue());
    assertEquals("20", parameters[2].getAnnotation(RequestParam.class).defaultValue());
    assertEquals("50", DeadLetterController.class.getMethod("getDeadLetters", int.class)
        .getParameters()[0].getAnnotation(RequestParam.class).defaultValue());
  }

  @Test
  void detailHomeAndInfoHaveExplicitEmptyProjections() throws Exception {
    MockHttpServletRequest request = requestWithPrivateParameters();
    assertEmpty(RequestLogProjection.restRequest(new HandlerMethod(new DeadLetterController(null),
        DeadLetterController.class.getMethod("getDeadLetter", String.class)), request));
    for (String method : new String[] {"home", "healthCheck"}) {
      assertEmpty(RequestLogProjection.restRequest(
          new HandlerMethod(new BaseController(), BaseController.class.getMethod(method)), request));
    }
  }

  @Test
  void dispatchUsesControllerTypeNotOnlyMethodName() throws Exception {
    MockHttpServletRequest request = requestWithPrivateParameters();
    request.setParameter("segregationCodes", "not-approved-here");
    assertEmpty(RequestLogProjection.restRequest(new HandlerMethod(new UnrelatedController(),
        UnrelatedController.class.getMethod("getReceiptByIUV")), request));
    assertEmpty(RequestLogProjection.restRequest(new HandlerMethod(new UnrelatedController(),
        UnrelatedController.class.getMethod("getDeadLetters")), request));
  }

  @Test
  void supportsControllerImplementationWithInterfaceDeclaredHandler() throws Exception {
    HandlerMethod handler = new HandlerMethod(new PaymentsController(),
        IPaymentsController.class.getMethod("getReceiptByIUV", String.class, String.class, String.class));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("segregationCodes", "01");
    assertEquals(object("{\"segregationCodes\":\"01\"}"),
        RequestLogProjection.restRequest(handler, request).args());
  }

  @Test
  void pathVariablesAreNotGuessedFromQueryOrUnexpectedAttributes() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("organizationfiscalcode", "wrong");
    request.setParameter("iuv", "wrong");
    assertEmpty(RequestLogProjection.restRequest(receiptHandler(), request));
    request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, "wrong-type");
    assertEmpty(RequestLogProjection.restRequest(receiptHandler(), request));
    request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
        Map.of("organizationfiscalcode", 123, "iuv", 456));
    assertEmpty(RequestLogProjection.restRequest(receiptHandler(), request));
  }

  @Test
  void projectionSnapshotsAreIndependentAndDoNotChangeMdc() throws Exception {
    Map<String, String> previous = MDC.getCopyOfContextMap();
    MDC.put("projection-test", "unchanged");
    try {
      Map<String, String> before = MDC.getCopyOfContextMap();
      ObjectNode source = object("{\"nested\":{\"value\":\"original\"}}");
      RequestLogProjection.Projection projection =
          new RequestLogProjection.Projection("creditor", "nav", "iuv", source);
      ((ObjectNode) source.get("nested")).put("value", "mutated");
      ((ObjectNode) projection.args().get("nested")).put("value", "also-mutated");
      assertEquals(object("{\"nested\":{\"value\":\"original\"}}"), projection.args());
      RequestLogProjection.soapRequest("paGetPayment", xml("paGetPaymentReq", "<idPA>creditor</idPA>"));
      RequestLogProjection.restRequest(listHandler(), new MockHttpServletRequest());
      RequestLogProjection.soapResponseIuv(xml("paGetPaymentRes", ""));
      assertEquals(before, MDC.getCopyOfContextMap());
      assertNull(new RequestLogProjection.Projection(null, null, null, object("{}")).args());
    } finally {
      MDC.clear();
      if (previous != null) {
        MDC.setContextMap(previous);
      }
    }
  }

  private Element xml(String root, String content) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    return factory.newDocumentBuilder().parse(new InputSource(new StringReader(
        "<p:" + root + " xmlns:p=\"http://pagopa-api.pagopa.gov.it/pa/paForNode.xsd\">"
            + content + "</p:" + root + ">"))).getDocumentElement();
  }

  private ObjectNode object(String value) throws Exception {
    return (ObjectNode) json.readTree(value);
  }

  private HandlerMethod receiptHandler() throws Exception {
    return new HandlerMethod(new PaymentsController(), PaymentsController.class.getMethod(
        "getReceiptByIUV", String.class, String.class, String.class));
  }

  private HandlerMethod listHandler() throws Exception {
    return new HandlerMethod(new PaymentsController(), PaymentsController.class.getMethod(
        "getOrganizationReceipts", String.class, int.class, int.class, String.class, String.class,
        String.class, String.class, String.class, String.class));
  }

  private MockHttpServletRequest requestWithPrivateParameters() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("debtor", "private-debtor");
    request.setParameter("debtorOrIuv", "private-search");
    request.setParameter("filename", "private-filename");
    request.setParameter("payload", "private-payload");
    request.setParameter("amount", "private-amount");
    return request;
  }

  private void assertEmpty(RequestLogProjection.Projection projection) {
    assertNull(projection.creditorId());
    assertNull(projection.noticeNumber());
    assertNull(projection.iuv());
    assertNull(projection.args());
  }

  public static class UnrelatedController {
    public void getReceiptByIUV() {
      // Only this unrelated controller's handler metadata is used by the projection test.
    }

    public void getDeadLetters() {
      // Only this unrelated controller's handler metadata is used by the projection test.
    }
  }
}
