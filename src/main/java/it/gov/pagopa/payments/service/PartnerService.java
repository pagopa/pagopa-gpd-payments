package it.gov.pagopa.payments.service;

import it.gov.pagopa.payments.endpoints.validation.exceptions.PartnerValidationException;
import it.gov.pagopa.payments.model.partner.*;
import it.gov.pagopa.payments.service.primitive.DemandPayService;
import it.gov.pagopa.payments.service.primitive.GetPaymentService;
import it.gov.pagopa.payments.service.primitive.ReceiveRTService;
import it.gov.pagopa.payments.service.primitive.VerifyPaymentService;
import javax.annotation.Nullable;
import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLStreamException;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xml.sax.SAXException;
import java.io.IOException;

@Service
@Slf4j
@NoArgsConstructor
@AllArgsConstructor
public class PartnerService {

    @Autowired
    GetPaymentService getPaymentService;
    @Autowired
    VerifyPaymentService verifyPaymentService;
    @Autowired
    ReceiveRTService receiveRTService;
    @Autowired
    DemandPayService demandPayService;

    @Transactional(readOnly = true)
    public PaVerifyPaymentNoticeRes paVerifyPaymentNotice(PaVerifyPaymentNoticeReq req, @Nullable String serviceType)
            throws DatatypeConfigurationException, PartnerValidationException {
        return verifyPaymentService.paVerifyPaymentNotice(req, serviceType);
    }

    @Transactional(readOnly = true)
    public PaGetPaymentRes paGetPayment(PaGetPaymentReq req, @Nullable String serviceType)
            throws DatatypeConfigurationException, PartnerValidationException {
        return getPaymentService.paGetPayment(req, serviceType);
    }

    @Transactional(readOnly = true)
    public PaGetPaymentV2Response paGetPaymentV2(PaGetPaymentV2Request req, @Nullable String serviceType)
            throws DatatypeConfigurationException, PartnerValidationException {
        return getPaymentService.paGetPaymentV2(req, serviceType);
    }

    @Transactional
    public PaSendRTRes paSendRT(PaSendRTReq request) {
        return receiveRTService.paSendRT(request);
    }

    @Transactional
    public PaSendRTV2Response paSendRTV2(PaSendRTV2Request request) {
        return receiveRTService.paSendRTV2(request);
    }

    @Transactional
    public PaDemandPaymentNoticeResponse paDemandPaymentNotice(PaDemandPaymentNoticeRequest request)
            throws DatatypeConfigurationException, ParserConfigurationException, IOException, SAXException, XMLStreamException {
        return demandPayService.paDemandPaymentNotice(request);
    }
}
