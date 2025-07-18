package it.gov.pagopa.payments.service.primitive;

import feign.FeignException;
import it.gov.pagopa.payments.endpoints.validation.exceptions.PartnerValidationException;
import it.gov.pagopa.payments.model.PaaErrorEnum;
import it.gov.pagopa.payments.model.PaymentsModelResponse;
import it.gov.pagopa.payments.model.partner.*;
import it.gov.pagopa.payments.service.PaymentOptService;
import it.gov.pagopa.payments.utils.CommonUtil;
import it.gov.pagopa.payments.utils.Validator;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Nullable;
import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import java.math.BigDecimal;

@Service
@Slf4j
@NoArgsConstructor
@AllArgsConstructor
public class VerifyPaymentService {

    @Autowired
    private ObjectFactory factory;

    private PaymentOptService POService;


    public PaVerifyPaymentNoticeRes paVerifyPaymentNotice(PaVerifyPaymentNoticeReq req, @Nullable String serviceType)
            throws DatatypeConfigurationException, PartnerValidationException {

        log.debug("[paVerifyPaymentNotice] get payment option [noticeNumber={}]", req.getQrCode().getNoticeNumber());
        PaymentsModelResponse paymentOption = null;

        try {
            paymentOption = POService.getAndValidatePaymentOption(req.getIdPA(), req.getIdStation(),
                    req.getQrCode().getNoticeNumber(), serviceType);
        } catch (FeignException.NotFound e) {
            log.error("[paVerifyPaymentNotice] GPD Error not found [noticeNumber={}]", req.getQrCode().getNoticeNumber(), e);
            throw new PartnerValidationException(PaaErrorEnum.PAA_PAGAMENTO_SCONOSCIUTO);
        } catch (PartnerValidationException e) {
            log.error("[paVerifyPaymentNotice] GPD PartnerValidation Error [noticeNumber={}]", req.getQrCode().getNoticeNumber(), e);
            throw e;
        } catch (Exception e) {
            log.error("[paVerifyPaymentNotice] GPD Generic Error [noticeNumber={}]", req.getQrCode().getNoticeNumber(), e);
            throw new PartnerValidationException(PaaErrorEnum.PAA_SYSTEM_ERROR);
        }

        Validator.checkDebtPositionStatus(paymentOption);

        PaVerifyPaymentNoticeRes result;
        log.debug("[paVerifyPaymentNotice] Response OK generation [noticeNumber={}]", req.getQrCode().getNoticeNumber());
        try {
            result = generatePaVerifyPaymentNoticeResponse(paymentOption);
        } catch (Exception e) {
            log.error("[paVerifyPaymentNotice] paymentOption {}", paymentOption, e);
            throw e;
        }
        return result;
    }

    /**
     * map the response of GPD in the XML model
     *
     * @param source {@link PaymentsModelResponse} response from GPD
     * @return XML model
     * @throws DatatypeConfigurationException If the DatatypeFactory is not available or cannot be
     *                                        instantiated.
     */
    public PaVerifyPaymentNoticeRes generatePaVerifyPaymentNoticeResponse(PaymentsModelResponse source)
            throws DatatypeConfigurationException {

        PaVerifyPaymentNoticeRes result = factory.createPaVerifyPaymentNoticeRes();
        CtPaymentOptionsDescriptionListPA paymentList = factory.createCtPaymentOptionsDescriptionListPA();
        CtPaymentOptionDescriptionPA paymentOption = factory.createCtPaymentOptionDescriptionPA();
        // generare una paVerifyPaymentNoticeRes positiva
        result.setOutcome(StOutcome.OK);
        // paymentList
        paymentOption.setAmount(BigDecimal.valueOf(source.getAmount()));
        paymentOption.setOptions(StAmountOption.EQ); // de-scoping
        paymentOption.setDueDate(DatatypeFactory.newInstance()
                .newXMLGregorianCalendar(CommonUtil.convertToGregorianCalendar(source.getDueDate())));
        paymentOption.setDetailDescription(Validator.validatePaymentOptionDescription(source.getDescription()));
        var cpp = source.getTransfer().stream()
                .noneMatch(elem -> elem.getPostalIban() == null || elem.getPostalIban().isBlank());
        paymentOption.setAllCCP(cpp); // allCPP fa parte del modello del option
        paymentList.setPaymentOptionDescription(paymentOption);

        result.setPaymentList(paymentList);
        // general info
        result.setFiscalCodePA(source.getOrganizationFiscalCode());
        result.setPaymentDescription(Validator.validatePaymentOptionDescription(source.getDescription()));
        result.setCompanyName(Validator.validateCompanyName(source.getCompanyName()));
        result.setOfficeName(Validator.validateOfficeName(source.getOfficeName()));
        return result;
    }
}
