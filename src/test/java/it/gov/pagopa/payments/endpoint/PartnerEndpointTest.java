package it.gov.pagopa.payments.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import it.gov.pagopa.payments.endpoints.PartnerEndpoint;
import it.gov.pagopa.payments.endpoints.validation.exceptions.PartnerValidationException;
import it.gov.pagopa.payments.mock.PaDemandNoticePaymentReqMock;
import it.gov.pagopa.payments.mock.PaDemandNoticePaymentResMock;
import it.gov.pagopa.payments.mock.PaGetPaymentReqMock;
import it.gov.pagopa.payments.mock.PaGetPaymentResMock;
import it.gov.pagopa.payments.mock.PaGetPaymentV2ReqMock;
import it.gov.pagopa.payments.mock.PaGetPaymentV2ResMock;
import it.gov.pagopa.payments.mock.PaSendRTReqMock;
import it.gov.pagopa.payments.mock.PaSendRTResMock;
import it.gov.pagopa.payments.mock.PaVerifyPaymentNoticeReqMock;
import it.gov.pagopa.payments.mock.PaVerifyPaymentNoticeResMock;
import it.gov.pagopa.payments.model.partner.ObjectFactory;
import it.gov.pagopa.payments.model.partner.PaDemandPaymentNoticeRequest;
import it.gov.pagopa.payments.model.partner.PaDemandPaymentNoticeResponse;
import it.gov.pagopa.payments.model.partner.PaGetPaymentReq;
import it.gov.pagopa.payments.model.partner.PaGetPaymentRes;
import it.gov.pagopa.payments.model.partner.PaGetPaymentV2Request;
import it.gov.pagopa.payments.model.partner.PaGetPaymentV2Response;
import it.gov.pagopa.payments.model.partner.PaSendRTReq;
import it.gov.pagopa.payments.model.partner.PaSendRTRes;
import it.gov.pagopa.payments.model.partner.PaVerifyPaymentNoticeReq;
import it.gov.pagopa.payments.model.partner.PaVerifyPaymentNoticeRes;
import java.io.IOException;
import javax.xml.bind.JAXBElement;
import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLStreamException;

import it.gov.pagopa.payments.service.PartnerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.xml.sax.SAXException;

@ExtendWith(MockitoExtension.class)
class PartnerEndpointTest {

  @InjectMocks private PartnerEndpoint partnerEndpoint;

  @Mock private PartnerService partnerService;

  @Mock private ObjectFactory factory;

  private final ObjectFactory factoryUtil = new ObjectFactory();

  @Test
  void paVerifyPaymentNoticeTest() throws DatatypeConfigurationException {

    // Test preconditions
    PaVerifyPaymentNoticeReq requestBody = PaVerifyPaymentNoticeReqMock.getMock();
    PaVerifyPaymentNoticeRes responseBody = PaVerifyPaymentNoticeResMock.getMock();
    JAXBElement<PaVerifyPaymentNoticeReq> request =
        factoryUtil.createPaVerifyPaymentNoticeReq(requestBody);

    when(partnerService.paVerifyPaymentNotice(requestBody)).thenReturn(responseBody);
    when(factory.createPaVerifyPaymentNoticeRes(responseBody))
        .thenReturn(factoryUtil.createPaVerifyPaymentNoticeRes(responseBody));

    // Test execution
    JAXBElement<PaVerifyPaymentNoticeRes> response = partnerEndpoint.paVerifyPaymentNotice(request);

    // Test postcondiction
    assertThat(response.getValue()).isEqualTo(responseBody);
  }

  @Test
  void paGetPaymentTest() throws PartnerValidationException, DatatypeConfigurationException {

    // Test preconditions
    PaGetPaymentReq requestBody = PaGetPaymentReqMock.getMock();
    PaGetPaymentRes responseBody = PaGetPaymentResMock.getMock();
    JAXBElement<PaGetPaymentReq> request = factoryUtil.createPaGetPaymentReq(requestBody);

    when(partnerService.paGetPayment(requestBody)).thenReturn(responseBody);
    when(factory.createPaGetPaymentRes(responseBody))
        .thenReturn(factoryUtil.createPaGetPaymentRes(responseBody));

    // Test execution
    JAXBElement<PaGetPaymentRes> response = partnerEndpoint.paGetPayment(request);

    // Test postcondiction
    assertThat(response.getValue()).isEqualTo(responseBody);
  }

  @Test
  void paGetPaymentV2Test() throws PartnerValidationException, DatatypeConfigurationException {

    // Test preconditions
    PaGetPaymentV2Request requestBody = PaGetPaymentV2ReqMock.getMock();
    PaGetPaymentV2Response responseBody = PaGetPaymentV2ResMock.getMock();
    JAXBElement<PaGetPaymentV2Request> request =
        factoryUtil.createPaGetPaymentV2Request(requestBody);

    when(partnerService.paGetPaymentV2(requestBody)).thenReturn(responseBody);
    when(factory.createPaGetPaymentV2Response(responseBody))
        .thenReturn(factoryUtil.createPaGetPaymentV2Response(responseBody));

    // Test execution
    JAXBElement<PaGetPaymentV2Response> response = partnerEndpoint.paGetPaymentV2(request);

    // Test postcondiction
    assertThat(response.getValue()).isEqualTo(responseBody);
  }

  @Test
  void paSendRTTest() throws DatatypeConfigurationException {

    // Test preconditions
    PaSendRTReq requestBody = PaSendRTReqMock.getMock("11111111112222226");
    PaSendRTRes responseBody = PaSendRTResMock.getMock();
    JAXBElement<PaSendRTReq> request = factoryUtil.createPaSendRTReq(requestBody);

    when(partnerService.paSendRT(requestBody)).thenReturn(responseBody);
    when(factory.createPaSendRTRes(responseBody))
        .thenReturn(factoryUtil.createPaSendRTRes(responseBody));

    // Test execution
    JAXBElement<PaSendRTRes> response = partnerEndpoint.paSendRT(request);

    // Test postcondiction
    assertThat(response.getValue()).isEqualTo(responseBody);
  }

  @Test
  void paSendRTTest_only_with_required_receipt_fields() throws DatatypeConfigurationException {

    // Test preconditions
    PaSendRTReq requestBody = PaSendRTReqMock.getMock("11111111112222227");
    // set to null paymentMethod, paymentDateTime and Fee
    requestBody.getReceipt().setPaymentDateTime(null);
    requestBody.getReceipt().setPaymentMethod(null);
    requestBody.getReceipt().setFee(null);
    PaSendRTRes responseBody = PaSendRTResMock.getMock();
    JAXBElement<PaSendRTReq> request = factoryUtil.createPaSendRTReq(requestBody);

    when(partnerService.paSendRT(requestBody)).thenReturn(responseBody);
    when(factory.createPaSendRTRes(responseBody))
        .thenReturn(factoryUtil.createPaSendRTRes(responseBody));

    // Test execution
    JAXBElement<PaSendRTRes> response = partnerEndpoint.paSendRT(request);

    // Test postcondiction
    assertThat(response.getValue()).isEqualTo(responseBody);
  }

  @Test
  void paDemandNoticePaymentTest()
      throws DatatypeConfigurationException, XMLStreamException, ParserConfigurationException,
          IOException, SAXException {

    // Test preconditions
    PaDemandPaymentNoticeRequest requestBody = PaDemandNoticePaymentReqMock.getMock();
    PaDemandPaymentNoticeResponse responseBody = PaDemandNoticePaymentResMock.getMock();
    JAXBElement<PaDemandPaymentNoticeRequest> request =
        factoryUtil.createPaDemandPaymentNoticeRequest(requestBody);

    when(partnerService.paDemandPaymentNotice(requestBody)).thenReturn(responseBody);
    when(factory.createPaDemandPaymentNoticeResponse(responseBody))
        .thenReturn(factoryUtil.createPaDemandPaymentNoticeResponse(responseBody));

    // Test execution
    JAXBElement<PaDemandPaymentNoticeResponse> response =
        partnerEndpoint.paDemandPaymentNotice(request);

    // Test postcondiction
    assertThat(response.getValue()).isEqualTo(responseBody);
  }
}
