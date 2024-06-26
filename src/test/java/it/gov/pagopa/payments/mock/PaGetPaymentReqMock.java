package it.gov.pagopa.payments.mock;

import it.gov.pagopa.payments.model.partner.CtQrCode;
import it.gov.pagopa.payments.model.partner.PaGetPaymentReq;
import it.gov.pagopa.payments.model.partner.PaGetPaymentV2Request;
import it.gov.pagopa.payments.model.partner.StTransferType;

public class PaGetPaymentReqMock {

  public static PaGetPaymentReq getMock() {

    CtQrCode qrCode = new CtQrCode();
    qrCode.setFiscalCode("77777777777");
    qrCode.setNoticeNumber("311111111112222222");

    PaGetPaymentReq mock = new PaGetPaymentReq();
    mock.setIdBrokerPA("77777777777");
    mock.setIdPA("77777777777");
    mock.setIdStation("77777777777_01");
    mock.setQrCode(qrCode);

    return mock;
  }

  public static PaGetPaymentV2Request getMockV2() {

    CtQrCode qrCode = new CtQrCode();
    qrCode.setFiscalCode("77777777777");
    qrCode.setNoticeNumber("311111111112222222");

    PaGetPaymentV2Request mock = new PaGetPaymentV2Request();
    mock.setIdBrokerPA("77777777777");
    mock.setIdPA("77777777777");
    mock.setIdStation("77777777777_01");
    mock.setQrCode(qrCode);

    return mock;
  }
  
  public static PaGetPaymentV2Request getMockTransferTypePAGOPA() {

	    CtQrCode qrCode = new CtQrCode();
	    qrCode.setFiscalCode("77777777777");
	    qrCode.setNoticeNumber("311111111112222222");

	    PaGetPaymentV2Request mock = new PaGetPaymentV2Request();
	    mock.setIdBrokerPA("77777777777");
	    mock.setIdPA("77777777777");
	    mock.setIdStation("77777777777_01");
	    mock.setQrCode(qrCode);
	    mock.setTransferType(StTransferType.PAGOPA);

	    return mock;
	  }
}
