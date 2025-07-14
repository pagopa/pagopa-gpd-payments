package it.gov.pagopa.payments.mock;

import it.gov.pagopa.payments.model.partner.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;

public class PaSendRTReqMock {

  public static PaSendRTReq getMock(String iuv) throws DatatypeConfigurationException {

    CtReceipt receipt = new CtReceipt();
    receipt.setReceiptId("c110729d258c4ab1b765fe902aae41d6");
    receipt.setNoticeNumber("3" + iuv);
    receipt.setFiscalCode("77777777777");
    receipt.setOutcome(StOutcome.OK);
    receipt.setCreditorReferenceId(iuv);
    receipt.setPaymentMethod("creditCard");
    receipt.setPSPCompanyName("Intesa San Paolo");
    receipt.setFee(BigDecimal.valueOf(2));
    receipt.setStandIn(false);
    receipt.setPaymentDateTime(
        DatatypeFactory.newInstance().newXMLGregorianCalendar(LocalDateTime.now().toString()));

    PaSendRTReq mock = new PaSendRTReq();
    mock.setIdBrokerPA("77777777777");
    mock.setIdPA("77777777777");
    mock.setIdStation("77777777777_01");
    mock.setReceipt(receipt);

    return mock;
  }

  public static PaSendRTV2Request getMockV2(String iuv) throws DatatypeConfigurationException {

    CtReceiptV2 receipt = new CtReceiptV2();
    receipt.setReceiptId("c110729d258c4ab1b765fe902aae41d6");
    receipt.setNoticeNumber("3" + iuv);
    receipt.setFiscalCode("77777777777");
    receipt.setOutcome(StOutcome.OK);
    receipt.setCreditorReferenceId(iuv);
    receipt.setPaymentMethod("creditCard");
    receipt.setPaymentNote("payment note");
    receipt.setPSPCompanyName("Intesa San Paolo");
    receipt.setFee(BigDecimal.valueOf(2));
    receipt.setPaymentDateTime(
        DatatypeFactory.newInstance().newXMLGregorianCalendar(LocalDateTime.now().toString()));
    receipt.setPrimaryCiIncurredFee(BigDecimal.valueOf(1));
    receipt.setIdBundle("idBundle");
    receipt.setIdCiBundle("idCiBundle");

    PaSendRTV2Request mock = new PaSendRTV2Request();
    mock.setIdBrokerPA("77777777777");
    mock.setIdPA("77777777777");
    mock.setIdStation("77777777777_01");
    mock.setReceipt(receipt);

    return mock;
  }

  public static PaSendRTReq getMockDebtor(String iuv) throws DatatypeConfigurationException {
    CtEntityUniqueIdentifier debtorCode = new CtEntityUniqueIdentifier();
    debtorCode.setEntityUniqueIdentifierValue("debtorCode");

    CtSubject debtor = new CtSubject();
    debtor.setUniqueIdentifier(debtorCode);

    CtReceipt receipt = new CtReceipt();
    receipt.setReceiptId("c110729d258c4ab1b765fe902aae41d6");
    receipt.setNoticeNumber("3" + iuv);
    receipt.setFiscalCode("77777777777");
    receipt.setOutcome(StOutcome.OK);
    receipt.setCreditorReferenceId(iuv);
    receipt.setPaymentMethod("creditCard");
    receipt.setPSPCompanyName("Intesa San Paolo");
    receipt.setFee(BigDecimal.valueOf(2));
    receipt.setDebtor(debtor);
    receipt.setPaymentDateTime(
            DatatypeFactory.newInstance().newXMLGregorianCalendar(LocalDateTime.now().toString()));
    receipt.setStandIn(false);

    PaSendRTReq mock = new PaSendRTReq();
    mock.setIdBrokerPA("77777777777");
    mock.setIdPA("77777777777");
    mock.setIdStation("77777777777_01");
    mock.setReceipt(receipt);

    return mock;
  }
}
