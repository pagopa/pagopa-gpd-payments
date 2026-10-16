package it.gov.pagopa.payments.logging;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.gov.pagopa.payments.model.partner.CtMapEntry;
import it.gov.pagopa.payments.model.partner.CtMetadata;
import it.gov.pagopa.payments.model.partner.CtReceipt;
import it.gov.pagopa.payments.model.partner.CtReceiptV2;
import it.gov.pagopa.payments.model.partner.CtSubject;
import it.gov.pagopa.payments.model.partner.ObjectFactory;
import it.gov.pagopa.payments.model.partner.PaSendRTReq;
import it.gov.pagopa.payments.model.partner.PaSendRTV2Request;
import it.gov.pagopa.payments.model.partner.PaVerifyPaymentNoticeReq;
import it.gov.pagopa.payments.model.partner.PaGetPaymentReq;
import it.gov.pagopa.payments.model.partner.PaGetPaymentV2Request;
import it.gov.pagopa.payments.model.partner.PaDemandPaymentNoticeRequest;
import it.gov.pagopa.payments.model.partner.StTransferType;
import java.io.StringWriter;
import java.util.Map;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.datatype.DatatypeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;

class RequestArgsTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void allSoapOperationsIncludeOnlyTheirApprovedRoutingAndOperationFields() throws Exception {
    var verify = new PaVerifyPaymentNoticeReq();
    verify.setIdBrokerPA("0001");
    verify.setIdStation("");
    verify.setIdPA("excluded-creditor");
    assertEquals("{\"idBrokerPA\":\"0001\",\"idStation\":\"\"}",
        RequestArgs.paVerifyPaymentNotice(verify));

    var date = DatatypeFactory.newInstance().newXMLGregorianCalendar("2026-10-16+02:00");
    var get = new PaGetPaymentReq();
    get.setIdBrokerPA("0001");
    get.setIdStation("0002");
    get.setIdPA("excluded-creditor");
    get.setAmount(new java.math.BigDecimal("123.45"));
    get.setPaymentNote("excluded-note");
    var qr = new it.gov.pagopa.payments.model.partner.CtQrCode();
    qr.setNoticeNumber("excluded-nav");
    qr.setFiscalCode("excluded-qr-creditor");
    get.setQrCode(qr);
    get.setTransferType(StTransferType.PAGOPA);
    get.setDueDate(date);
    var getV2 = new PaGetPaymentV2Request();
    getV2.setIdBrokerPA("0001");
    getV2.setIdStation("0002");
    getV2.setIdPA("excluded-creditor");
    getV2.setAmount(new java.math.BigDecimal("123.45"));
    getV2.setPaymentNote("excluded-note");
    getV2.setQrCode(qr);
    verify.setQrCode(qr);
    assertEquals("{\"idBrokerPA\":\"0001\",\"idStation\":\"\"}",
        RequestArgs.paVerifyPaymentNotice(verify));
    getV2.setTransferType(StTransferType.PAGOPA);
    getV2.setDueDate(date);
    JsonNode expectedGet = mapper.readTree("""
        {"idBrokerPA":"0001","idStation":"0002","transferType":"PAGOPA","dueDate":"2026-10-16+02:00"}
        """);
    assertEquals(expectedGet, mapper.readTree(RequestArgs.paGetPayment(get)));
    assertEquals(expectedGet, mapper.readTree(RequestArgs.paGetPaymentV2(getV2)));
    get.setTransferType(StTransferType.POSTAL);
    assertEquals("POSTAL", mapper.readTree(RequestArgs.paGetPayment(get)).get("transferType").asText());
    assertEquals("2026-10-16+02:00", date.toXMLFormat());

    var demand = new PaDemandPaymentNoticeRequest();
    demand.setIdBrokerPA("0001");
    demand.setIdStation("0002");
    demand.setIdServizio("0003");
    demand.setIdPA("excluded-creditor");
    demand.setIdSoggettoServizio("excluded-subject");
    demand.setDatiSpecificiServizioRequest(new byte[] {1, 2, 3});
    assertEquals(mapper.readTree("""
        {"idBrokerPA":"0001","idStation":"0002","idServizio":"0003"}
        """), mapper.readTree(RequestArgs.paDemandPaymentNotice(demand)));

    var first = v1(privateV1());
    first.setIdBrokerPA("0001");
    first.setIdStation("0002");
    var second = v2(privateV2());
    second.setIdBrokerPA("0001");
    second.setIdStation("0002");
    JsonNode routing = mapper.readTree("{\"idBrokerPA\":\"0001\",\"idStation\":\"0002\"}");
    assertEquals(routing, mapper.readTree(RequestArgs.paSendRT(first)));
    assertEquals(routing, mapper.readTree(RequestArgs.paSendRTV2(second)));
    first.setReceipt(null);
    second.setReceipt(null);
    assertEquals(routing, mapper.readTree(RequestArgs.paSendRT(first)));
    assertEquals(routing, mapper.readTree(RequestArgs.paSendRTV2(second)));
  }

  @Test
  void missingAndEmptyNonReceiptRequestsOmitArgs() {
    assertNull(RequestArgs.paVerifyPaymentNotice(null));
    assertNull(RequestArgs.paGetPayment(null));
    assertNull(RequestArgs.paGetPaymentV2(null));
    assertNull(RequestArgs.paDemandPaymentNotice(null));
    assertNull(RequestArgs.paVerifyPaymentNotice(new PaVerifyPaymentNoticeReq()));
    assertNull(RequestArgs.paGetPayment(new PaGetPaymentReq()));
    assertNull(RequestArgs.paGetPaymentV2(new PaGetPaymentV2Request()));
    assertNull(RequestArgs.paDemandPaymentNotice(new PaDemandPaymentNoticeRequest()));
  }

  @Test
  void v1ContainsExactlyTheFiveApprovedReceiptFields() throws Exception {
    CtReceipt receipt = privateV1();
    receipt.setReceiptId("receipt-1");
    receipt.setIdPSP("000001");
    receipt.setCreditorReferenceId("000000123456789");
    receipt.setPaymentDateTime(
        DatatypeFactory.newInstance().newXMLGregorianCalendar("2026-10-15T10:20:30"));
    receipt.setStandIn(true);

    JsonNode json = mapper.readTree(RequestArgs.paSendRT(v1(receipt)));

    assertEquals(mapper.readTree("""
        {"receiptId":"receipt-1","idPSP":"000001","creditorReferenceId":"000000123456789",
         "paymentDateTime":"2026-10-15T10:20:30","standIn":true}
        """), json);
    assertFalse(json.has("idBundle"));
  }

  @Test
  void v2ContainsExactlyTheSixApprovedReceiptFields() throws Exception {
    CtReceiptV2 receipt = privateV2();
    receipt.setReceiptId("receipt-2");
    receipt.setIdPSP("000002");
    receipt.setCreditorReferenceId("000000987654321");
    receipt.setPaymentDateTime(
        DatatypeFactory.newInstance().newXMLGregorianCalendar("2026-10-15T10:20:30+02:00"));
    receipt.setStandIn(false);
    receipt.setIdBundle("000003");

    assertEquals(mapper.readTree("""
        {"receiptId":"receipt-2","idPSP":"000002","creditorReferenceId":"000000987654321",
         "paymentDateTime":"2026-10-15T10:20:30+02:00","standIn":false,"idBundle":"000003"}
        """), mapper.readTree(RequestArgs.paSendRTV2(v2(receipt))));
  }

  @Test
  void missingRequestsReceiptsAndEmptyProjectionsOmitArgsEntirely() {
    assertNull(RequestArgs.paSendRT(null));
    assertNull(RequestArgs.paSendRTV2(null));
    assertNull(RequestArgs.paSendRT(new PaSendRTReq()));
    assertNull(RequestArgs.paSendRTV2(new PaSendRTV2Request()));
    assertNull(RequestArgs.paSendRT(v1(new CtReceipt())));
    assertNull(RequestArgs.paSendRTV2(v2(new CtReceiptV2())));
  }

  @Test
  void unapprovedDataAloneDoesNotProduceArgs() {
    assertNull(RequestArgs.paSendRT(v1(privateV1())));
    assertNull(RequestArgs.paSendRTV2(v2(privateV2())));
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(booleans = {true, false})
  void optionalStandInRetainsItsThreeStatesInBothVersions(Boolean standIn) throws Exception {
    CtReceipt first = new CtReceipt();
    first.setReceiptId("r");
    first.setStandIn(standIn);
    CtReceiptV2 second = new CtReceiptV2();
    second.setReceiptId("r");
    second.setStandIn(standIn);

    for (String args : new String[] {
        RequestArgs.paSendRT(v1(first)), RequestArgs.paSendRTV2(v2(second))}) {
      JsonNode json = mapper.readTree(args);
      if (standIn == null) {
        assertEquals(mapper.readTree("{\"receiptId\":\"r\"}"), json);
      } else {
        assertEquals(2, json.size());
        assertTrue(json.get("standIn").isBoolean());
        assertEquals(standIn, json.get("standIn").booleanValue());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "2026-10-15T10:20:30",
      "2026-10-15T10:20:30.123400",
      "2026-10-15T10:20:30.123400Z",
      "2026-10-15T10:20:30.001+02:00",
      "2026-10-15T10:20:30-05:30"
  })
  void datesPreserveTheFullXmlRepresentationInBothVersions(String date) throws Exception {
    var calendar = DatatypeFactory.newInstance().newXMLGregorianCalendar(date);
    CtReceipt first = new CtReceipt();
    first.setPaymentDateTime(calendar);
    CtReceiptV2 second = new CtReceiptV2();
    second.setPaymentDateTime(calendar);

    for (String args : new String[] {
        RequestArgs.paSendRT(v1(first)), RequestArgs.paSendRTV2(v2(second))}) {
      JsonNode json = mapper.readTree(args);
      assertEquals(1, json.size());
      assertEquals(date, json.get("paymentDateTime").textValue());
    }
    assertEquals(date, calendar.toXMLFormat());
  }

  @Test
  void stringsKeepLeadingZerosEscapingEmptyValuesAndUnboundedLength() throws Exception {
    String receiptId = "0000\"\\\n\r\t\u0000\u00e0\u20ac" + "x".repeat(100_000);
    CtReceipt first = new CtReceipt();
    first.setReceiptId(receiptId);
    first.setIdPSP("");
    first.setCreditorReferenceId("000000123456789");
    CtReceiptV2 second = new CtReceiptV2();
    second.setReceiptId(receiptId);
    second.setIdPSP("");
    second.setCreditorReferenceId("000000123456789");
    second.setIdBundle("0000\"\\\nbundle");

    for (String args : new String[] {
        RequestArgs.paSendRT(v1(first)), RequestArgs.paSendRTV2(v2(second))}) {
      JsonNode json = mapper.readTree(args);
      assertEquals(receiptId, json.get("receiptId").textValue());
      assertEquals("", json.get("idPSP").textValue());
      assertTrue(json.get("creditorReferenceId").isTextual());
      assertEquals("000000123456789", json.get("creditorReferenceId").textValue());
      assertFalse(args.contains("\n"));
      assertFalse(args.contains("\u0000"));
    }
    assertEquals(second.getIdBundle(),
        mapper.readTree(RequestArgs.paSendRTV2(v2(second))).get("idBundle").textValue());
  }

  @Test
  void optionalNullFieldsAreOmittedAndBundleCanBeTheOnlyV2Field() throws Exception {
    CtReceipt first = new CtReceipt();
    first.setReceiptId("r");
    CtReceiptV2 second = new CtReceiptV2();
    second.setReceiptId("r");
    assertEquals("{\"receiptId\":\"r\"}", RequestArgs.paSendRT(v1(first)));
    assertEquals("{\"receiptId\":\"r\"}", RequestArgs.paSendRTV2(v2(second)));
    second.setReceiptId(null);
    second.setIdBundle("bundle");
    assertEquals("{\"idBundle\":\"bundle\"}", RequestArgs.paSendRTV2(v2(second)));
  }

  @Test
  void requestsAndMdcAreUnchangedIncludingWhenThereIsNoProjection() throws Exception {
    PaSendRTReq first = v1(privateV1());
    PaSendRTV2Request second = v2(privateV2());
    first.getReceipt().setReceiptId("r");
    second.getReceipt().setPaymentDateTime(
        DatatypeFactory.newInstance().newXMLGregorianCalendar("2026-10-15T10:20:30.123400"));
    var context = JAXBContext.newInstance(PaSendRTReq.class, PaSendRTV2Request.class);
    var firstElement = new ObjectFactory().createPaSendRTReq(first);
    var secondElement = new ObjectFactory().createPaSendRTV2Request(second);
    String firstBefore = xml(context, firstElement);
    String secondBefore = xml(context, secondElement);
    Map<String, String> previous = MDC.getCopyOfContextMap();
    try {
      MDC.setContextMap(Map.of("args", "existing", "requestId", "request", "custom", "untouched"));
      Map<String, String> expected = MDC.getCopyOfContextMap();
      RequestArgs.paSendRT(first);
      RequestArgs.paSendRTV2(second);
      RequestArgs.paSendRT(null);
      RequestArgs.paSendRTV2(v2(new CtReceiptV2()));
      assertEquals(expected, MDC.getCopyOfContextMap());
      MDC.clear();
      RequestArgs.paSendRT(first);
      RequestArgs.paSendRTV2(second);
      assertTrue(MDC.getCopyOfContextMap() == null || MDC.getCopyOfContextMap().isEmpty());
    } finally {
      if (previous == null) {
        MDC.clear();
      } else {
        MDC.setContextMap(previous);
      }
    }
    assertEquals(firstBefore, xml(context, firstElement));
    assertEquals(secondBefore, xml(context, secondElement));
  }

  private static PaSendRTReq v1(CtReceipt receipt) {
    PaSendRTReq request = new PaSendRTReq();
    request.setIdPA("private-header-pa");
    request.setReceipt(receipt);
    return request;
  }

  private static PaSendRTV2Request v2(CtReceiptV2 receipt) {
    PaSendRTV2Request request = new PaSendRTV2Request();
    request.setIdPA("private-header-pa");
    request.setReceipt(receipt);
    return request;
  }

  private static CtReceipt privateV1() {
    CtReceipt receipt = new CtReceipt();
    receipt.setNoticeNumber("private-nav");
    receipt.setFiscalCode("private-fiscal-code");
    receipt.setDescription("private-payment-notes");
    receipt.setPspFiscalCode("private-psp-fiscal-code");
    receipt.setDebtor(subject());
    receipt.setPayer(subject());
    receipt.setMetadata(metadata());
    return receipt;
  }

  private static CtReceiptV2 privateV2() {
    CtReceiptV2 receipt = new CtReceiptV2();
    receipt.setNoticeNumber("private-nav");
    receipt.setFiscalCode("private-fiscal-code");
    receipt.setDescription("private-description");
    receipt.setPaymentNote("private-payment-notes");
    receipt.setPspFiscalCode("private-psp-fiscal-code");
    receipt.setIdCiBundle("private-ci-bundle");
    receipt.setDebtor(subject());
    receipt.setPayer(subject());
    receipt.setMetadata(metadata());
    return receipt;
  }

  private static CtSubject subject() {
    CtSubject subject = new CtSubject();
    subject.setFullName("private-name");
    subject.setStreetName("private-address");
    subject.setEMail("private@example.test");
    return subject;
  }

  private static CtMetadata metadata() {
    CtMapEntry entry = new CtMapEntry();
    entry.setKey("private-key");
    entry.setValue("private-value");
    CtMetadata metadata = new CtMetadata();
    metadata.getMapEntry().add(entry);
    return metadata;
  }

  private static String xml(JAXBContext context, JAXBElement<?> request) throws Exception {
    StringWriter xml = new StringWriter();
    context.createMarshaller().marshal(request, xml);
    return xml.toString();
  }
}
