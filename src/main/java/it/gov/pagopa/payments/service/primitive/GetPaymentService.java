package it.gov.pagopa.payments.service.primitive;

import feign.FeignException;
import it.gov.pagopa.payments.endpoints.validation.exceptions.PartnerValidationException;
import it.gov.pagopa.payments.model.PaaErrorEnum;
import it.gov.pagopa.payments.model.PaymentOptionMetadataModel;
import it.gov.pagopa.payments.model.PaymentsModelResponse;
import it.gov.pagopa.payments.model.partner.*;
import it.gov.pagopa.payments.service.PaymentOptService;
import it.gov.pagopa.payments.utils.CommonUtil;
import it.gov.pagopa.payments.utils.CustomizedMapper;
import it.gov.pagopa.payments.utils.Validator;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Nullable;
import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeConstants;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static it.gov.pagopa.payments.service.mapper.TransferMapperSOAP.getTransferResponse;
import static it.gov.pagopa.payments.service.mapper.TransferMapperSOAP.getTransferResponseV2;

@Service
@Slf4j
@NoArgsConstructor
@AllArgsConstructor
public class GetPaymentService {

    @Autowired
    private ObjectFactory factory;

    @Autowired
    private CustomizedMapper customizedModelMapper;

    private PaymentOptService paymentOptService;


    public PaGetPaymentRes paGetPayment(PaGetPaymentReq req, @Nullable String serviceType)
            throws DatatypeConfigurationException, PartnerValidationException {
        log.debug("[paGetPayment] method call [noticeNumber={}]", req.getQrCode().getNoticeNumber());
        PaymentsModelResponse paymentOption = this.manageGetPaymentRequest(req.getIdPA(), req.getIdStation(), req.getQrCode(), serviceType);
        log.debug("[paGetPayment] Response OK generation [noticeNumber={}]", req.getQrCode().getNoticeNumber());
        return this.generatePaGetPaymentResponse(paymentOption, req);
    }

    public PaGetPaymentV2Response paGetPaymentV2(PaGetPaymentV2Request req, @Nullable String serviceType)
            throws DatatypeConfigurationException, PartnerValidationException {
        log.debug("[paGetPaymentV2] method call [noticeNumber={}]", req.getQrCode().getNoticeNumber());
        PaymentsModelResponse paymentOption = this.manageGetPaymentRequest(req.getIdPA(), req.getIdStation(), req.getQrCode(), serviceType);
        log.debug("[paGetPaymentV2] Response OK generation [noticeNumber={}]", req.getQrCode().getNoticeNumber());
        return this.generatePaGetPaymentResponse(paymentOption, req);
    }

    /**
     * map the response of GPD in the XML model
     *
     * @param source  {@link PaymentsModelResponse} response from GPD
     * @param request SOAP input model
     * @return XML model
     * @throws DatatypeConfigurationException If the DatatypeFactory is not available or cannot be
     *                                        instantiated.
     */
    private PaGetPaymentRes generatePaGetPaymentResponse(PaymentsModelResponse source, PaGetPaymentReq request)
            throws DatatypeConfigurationException {

        PaGetPaymentRes response = factory.createPaGetPaymentRes();
        CtPaymentPA responseData = factory.createCtPaymentPA();
        CtTransferListPA transferList = factory.createCtTransferListPA();

        response.setOutcome(StOutcome.OK);

        // general payment data
        responseData.setCreditorReferenceId(source.getIuv());
        responseData.setPaymentAmount(BigDecimal.valueOf(source.getAmount()));

        DatatypeFactory datatypeFactory = DatatypeFactory.newInstance();
        XMLGregorianCalendar dueDateXMLGregorian = datatypeFactory
                .newXMLGregorianCalendar(CommonUtil.convertToGregorianCalendar(source.getDueDate()));
        responseData.setDueDate(dueDateXMLGregorian);

        if (source.getRetentionDate() != null) {
            XMLGregorianCalendar retentionDateXMLGregorian = datatypeFactory
                    .newXMLGregorianCalendar(CommonUtil.convertToGregorianCalendar(source.getRetentionDate()));
            retentionDateXMLGregorian.setTimezone(DatatypeConstants.FIELD_UNDEFINED);
            responseData.setRetentionDate(retentionDateXMLGregorian);
        }

        responseData.setLastPayment(false); // de-scoping
        responseData.setDescription(Validator.validatePaymentOptionDescription(source.getDescription()));
        responseData.setCompanyName(Validator.validateCompanyName(source.getCompanyName()));
        responseData.setOfficeName(Validator.validateOfficeName(source.getOfficeName()));

        List<PaymentOptionMetadataModel> paymentOptionMetadataModels = source.getPaymentOptionMetadata();
        if (paymentOptionMetadataModels != null && !paymentOptionMetadataModels.isEmpty()) {
            responseData.setMetadata(getCtMetadata(factory, paymentOptionMetadataModels));
        }

        CtSubject debtor = getDebtor(source);
        responseData.setDebtor(debtor);

        // Transfer list
        transferList.getTransfer().addAll(source.getTransfer().stream()
                .map(paymentsTransferModelResponse ->
                        getTransferResponse(paymentsTransferModelResponse, request.getTransferType()))
                .collect(Collectors.toList()));

        responseData.setTransferList(transferList);
        response.setData(responseData);

        return response;
    }

    /**
     * map the response of GPD in the XML V2 model
     *
     * @param source  {@link PaymentsModelResponse} response from GPD
     * @param request SOAP input model
     * @return XML model
     * @throws DatatypeConfigurationException If the DatatypeFactory is not available or cannot be
     *                                        instantiated.
     */
    private PaGetPaymentV2Response generatePaGetPaymentResponse(PaymentsModelResponse source, PaGetPaymentV2Request request)
            throws DatatypeConfigurationException {

        PaGetPaymentV2Response response = factory.createPaGetPaymentV2Response();
        CtPaymentPAV2 responseData = factory.createCtPaymentPAV2();
        CtTransferListPAV2 transferList = factory.createCtTransferListPAV2();

        response.setOutcome(StOutcome.OK);

        // general payment data
        responseData.setCreditorReferenceId(source.getIuv());
        responseData.setPaymentAmount(BigDecimal.valueOf(source.getAmount()));

        DatatypeFactory datatypeFactory = DatatypeFactory.newInstance();
        XMLGregorianCalendar dueDateXMLGregorian = datatypeFactory
                .newXMLGregorianCalendar(CommonUtil.convertToGregorianCalendar(source.getDueDate()));
        responseData.setDueDate(dueDateXMLGregorian);

        if (source.getRetentionDate() != null) {
            XMLGregorianCalendar retentionDateXMLGregorian = datatypeFactory
                    .newXMLGregorianCalendar(CommonUtil.convertToGregorianCalendar(source.getRetentionDate()));
            retentionDateXMLGregorian.setTimezone(DatatypeConstants.FIELD_UNDEFINED);
            responseData.setRetentionDate(retentionDateXMLGregorian);
        }

        responseData.setLastPayment(false); // de-scoping
        responseData.setDescription(Validator.validatePaymentOptionDescription(source.getDescription()));
        responseData.setCompanyName(Validator.validateCompanyName(source.getCompanyName()));
        responseData.setOfficeName(Validator.validateOfficeName(source.getOfficeName()));

        List<PaymentOptionMetadataModel> paymentOptionMetadataModels = source.getPaymentOptionMetadata();
        if (paymentOptionMetadataModels != null && !paymentOptionMetadataModels.isEmpty()) {
            responseData.setMetadata(getCtMetadata(factory, paymentOptionMetadataModels));
        }

        // debtor data
        CtSubject debtor = getDebtor(source);

        // Transfer list
        transferList.getTransfer().addAll(source.getTransfer().stream()
                .map(paymentsTransferModelResponse ->
                        getTransferResponseV2(customizedModelMapper, paymentsTransferModelResponse, request.getTransferType()))
                .toList());

        responseData.setTransferList(transferList);
        responseData.setDebtor(debtor);
        response.setData(responseData);

        return response;
    }

    private PaymentsModelResponse manageGetPaymentRequest(String idPa, String station, CtQrCode qrCode, String serviceType) {

        log.debug("[manageGetPaymentRequest] get payment option [noticeNumber={}]", qrCode.getNoticeNumber());
        PaymentsModelResponse paymentOption = null;

        try {
            paymentOption = paymentOptService.getAndValidatePaymentOption(idPa, station, qrCode.getNoticeNumber(), serviceType);
        } catch (FeignException.NotFound e) {
            log.error("[manageGetPaymentRequest] GPD Error not found [noticeNumber={}]", qrCode.getNoticeNumber(), e);
            throw new PartnerValidationException(PaaErrorEnum.PAA_PAGAMENTO_SCONOSCIUTO);
        } catch (PartnerValidationException e) {
            log.error("[manageGetPaymentRequest] GPD PartnerValidation Error [noticeNumber={}]", qrCode.getNoticeNumber(), e);
            throw e;
        } catch (Exception e) {
            log.error("[manageGetPaymentRequest] GPD Generic Error [noticeNumber={}]", qrCode.getNoticeNumber(), e);
            throw new PartnerValidationException(PaaErrorEnum.PAA_SYSTEM_ERROR);
        }
        Validator.checkDebtPositionStatus(paymentOption);
        return paymentOption;
    }

    public CtSubject getDebtor(PaymentsModelResponse source) {

        CtEntityUniqueIdentifier uniqueIdentifier = factory.createCtEntityUniqueIdentifier();
        CtSubject debtor = factory.createCtSubject();

        uniqueIdentifier.setEntityUniqueIdentifierType(StEntityUniqueIdentifierType.fromValue(source.getType().name()));
        uniqueIdentifier.setEntityUniqueIdentifierValue(source.getFiscalCode());

        debtor.setUniqueIdentifier(uniqueIdentifier);
        debtor.setFullName(source.getFullName());
        // optional fields --> before the set it is checked that the field is not null and not empty
        Optional.ofNullable(source.getStreetName()).filter(Predicate.not(String::isEmpty)).ifPresent(debtor::setStreetName);
        Optional.ofNullable(source.getCivicNumber()).filter(Predicate.not(String::isEmpty)).ifPresent(debtor::setCivicNumber);
        Optional.ofNullable(source.getPostalCode()).filter(Predicate.not(String::isEmpty)).ifPresent(debtor::setPostalCode);
        Optional.ofNullable(source.getCity()).filter(Predicate.not(String::isEmpty)).ifPresent(debtor::setCity);
        Optional.ofNullable(source.getProvince()).filter(Predicate.not(String::isEmpty)).ifPresent(debtor::setStateProvinceRegion);
        Optional.ofNullable(source.getCountry()).filter(Predicate.not(String::isEmpty)).ifPresent(debtor::setCountry);
        Optional.ofNullable(source.getEmail()).filter(Predicate.not(String::isEmpty)).ifPresent(debtor::setEMail);

        return debtor;
    }

    public static CtMetadata getCtMetadata(ObjectFactory factory, List<PaymentOptionMetadataModel> paymentOptionMetadataModels) {
        CtMetadata paymentOptionMetadata = factory.createCtMetadata();
        List<CtMapEntry> poMapEntry = paymentOptionMetadata.getMapEntry();
        for (PaymentOptionMetadataModel po : paymentOptionMetadataModels) {
            poMapEntry.add(getPaymentOptionMetadata(po));
        }
        return paymentOptionMetadata;
    }

    public static CtMapEntry getPaymentOptionMetadata(PaymentOptionMetadataModel metadataModel) {
        CtMapEntry ctMapEntry = new CtMapEntry();
        ctMapEntry.setKey(metadataModel.getKey());
        ctMapEntry.setValue(metadataModel.getValue());
        return ctMapEntry;
    }
}
