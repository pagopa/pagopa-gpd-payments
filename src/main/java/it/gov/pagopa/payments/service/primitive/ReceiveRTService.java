package it.gov.pagopa.payments.service.primitive;

import com.azure.core.util.Context;
import com.azure.storage.queue.QueueClient;
import com.microsoft.azure.storage.StorageException;
import feign.FeignException;
import feign.RetryableException;
import it.gov.pagopa.payments.endpoints.validation.exceptions.PartnerValidationException;
import it.gov.pagopa.payments.entity.ReceiptEntity;
import it.gov.pagopa.payments.model.PaaErrorEnum;
import it.gov.pagopa.payments.model.PaymentOptionModel;
import it.gov.pagopa.payments.model.PaymentOptionModelResponse;
import it.gov.pagopa.payments.model.PaymentOptionStatus;
import it.gov.pagopa.payments.model.partner.*;
import it.gov.pagopa.payments.repository.ReceiptRepository;
import it.gov.pagopa.payments.service.PaymentOptService;
import it.gov.pagopa.payments.utils.CommonUtil;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.namespace.QName;
import java.io.StringWriter;
import java.net.URISyntaxException;
import java.security.InvalidKeyException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
@Slf4j
@NoArgsConstructor
@AllArgsConstructor
public class ReceiveRTService {
    @Autowired
    private ObjectFactory factory;

    @Value(value = "${azure.queue.send.invisibilityTime}")
    private Long queueSendInvisibilityTime;

    @Autowired
    private QueueClient queueClient;

    @Autowired
    private ReceiptRepository receiptRepository;

    private PaymentOptService paymentOptService;


    @Transactional
    public PaSendRTRes paSendRT(PaSendRTReq request) {

        PaymentOptionModelResponse paymentOption = managePaSendRtRequest(request);

        if (!PaymentOptionStatus.PO_PAID.equals(paymentOption.getStatus())) {
            log.error("[paSendRT] Payment Option [statusError: {}] [noticeNumber={}]", paymentOption.getStatus(), request.getReceipt().getNoticeNumber());
            throw new PartnerValidationException(PaaErrorEnum.PAA_SEMANTICA);
        }

        log.debug("[paSendRT] Generate Response [noticeNumber={}]", request.getReceipt().getNoticeNumber());
        // status is always equals to PO_PAID
        return generatePaSendRTResponse();
    }

    @Transactional
    public PaSendRTV2Response paSendRTV2(PaSendRTV2Request req) {
        String noticeNumber = req.getReceipt().getNoticeNumber();
        PaymentOptionModelResponse paymentOption = managePaSendRtRequest(req);

        if (!PaymentOptionStatus.PO_PAID.equals(paymentOption.getStatus())) {
            log.error("[paSendRTV2] Payment Option [statusError: {}] [noticeNumber={}]", paymentOption.getStatus(), noticeNumber);
            throw new PartnerValidationException(PaaErrorEnum.PAA_SEMANTICA);
        }

        log.debug("[paSendRTV2] Generate Response [noticeNumber={}]", noticeNumber);
        // status is always equals to PO_PAID
        return generatePaSendRTV2Response();
    }

    public static String marshal(PaSendRTReq paSendRTReq) throws JAXBException {
        StringWriter sw = new StringWriter();
        JAXBContext context = JAXBContext.newInstance(PaSendRTReq.class);
        Marshaller mar = context.createMarshaller();
        mar.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
        JAXBElement<PaSendRTReq> jaxbElement = new JAXBElement<>(new QName("", "paSendRTReq"), PaSendRTReq.class, paSendRTReq);
        mar.marshal(jaxbElement, sw);
        return sw.toString();
    }

    public static String marshalV2(PaSendRTV2Request paSendRTV2Request) throws JAXBException {
        StringWriter sw = new StringWriter();
        JAXBContext context = JAXBContext.newInstance(PaSendRTV2Request.class);
        Marshaller mar = context.createMarshaller();
        mar.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
        JAXBElement<PaSendRTV2Request> jaxbElement = new JAXBElement<>(new QName("", "PaSendRTV2Request"),
                PaSendRTV2Request.class, paSendRTV2Request);
        mar.marshal(jaxbElement, sw);
        return sw.toString();
    }

    private PaSendRTRes generatePaSendRTResponse() {
        PaSendRTRes result = factory.createPaSendRTRes();
        result.setOutcome(StOutcome.OK);
        return result;
    }

    private PaSendRTV2Response generatePaSendRTV2Response() {
        PaSendRTV2Response result = factory.createPaSendRTV2Response();
        result.setOutcome(StOutcome.OK);
        return result;
    }

    private PaymentOptionModelResponse managePaSendRtRequest(PaSendRTReq req) {
        CtReceipt receipt = req.getReceipt();
        log.debug("[managePaSendRtRequest] save receipt [noticeNumber={}]", receipt.getNoticeNumber());

        String debtorIdentifier =
                Optional.ofNullable(receipt.getDebtor())
                        .map(CtSubject::getUniqueIdentifier)
                        .map(CtEntityUniqueIdentifier::getEntityUniqueIdentifierValue)
                        .orElse("");
        ReceiptEntity receiptEntity =
                receiptRepository.createReceiptEntity(
                        req.getIdPA(),
                        receipt.getCreditorReferenceId(),
                        debtorIdentifier,
                        receipt.getPaymentDateTime().toString());

        try {
            receiptEntity.setDocument(marshal(req));
        } catch (JAXBException e) {
            log.error("[managePaSendRtRequest] Error in receipt marshalling [noticeNumber={}]", receipt.getNoticeNumber(), e);
            throw new PartnerValidationException(PaaErrorEnum.PAA_SYSTEM_ERROR);
        }

        boolean isStandIn = receipt.isStandIn() != null ? receipt.isStandIn() : false;

        LocalDateTime paymentDateTime =
                receipt.getPaymentDateTime() != null
                        ? req
                        .getReceipt()
                        .getPaymentDateTime()
                        .toGregorianCalendar()
                        .toZonedDateTime()
                        .toLocalDateTime()
                        : null;
        PaymentOptionModel body =
                PaymentOptionModel.builder()
                        .idReceipt(receipt.getReceiptId())
                        .paymentDate(paymentDateTime)
                        .pspCode(receipt.getIdPSP())
                        .pspTaxCode(receipt.getPspFiscalCode())
                        .pspCompany(receipt.getPSPCompanyName())
                        .paymentMethod(receipt.getPaymentMethod())
                        .fee(String.valueOf(CommonUtil.getFeeInCent(receipt.getFee())))
                        .build();

        return this.setPaidPaymentOptionRetryIfException(
                receipt.getNoticeNumber(),
                req.getIdPA(),
                receipt.getCreditorReferenceId(),
                isStandIn,
                body,
                receiptEntity);
    }

    private PaymentOptionModelResponse managePaSendRtRequest(PaSendRTV2Request req) {
        CtReceiptV2 receipt = req.getReceipt();
        log.debug("[managePaSendRtRequest] save V2 receipt [noticeNumber={}]", receipt.getNoticeNumber());

        String debtorIdentifier =
                Optional.ofNullable(receipt.getDebtor())
                        .map(CtSubject::getUniqueIdentifier)
                        .map(CtEntityUniqueIdentifier::getEntityUniqueIdentifierValue)
                        .orElse("");
        ReceiptEntity receiptEntity =
                receiptRepository.createReceiptEntity(
                        req.getIdPA(),
                        receipt.getCreditorReferenceId(),
                        debtorIdentifier,
                        receipt.getPaymentDateTime().toString());
        try {
            receiptEntity.setDocument(marshalV2(req));
        } catch (JAXBException e) {
            log.error("[managePaSendRtRequest] Error in receipt marshalling [noticeNumber={}]", receipt.getNoticeNumber(), e);
            throw new PartnerValidationException(PaaErrorEnum.PAA_SYSTEM_ERROR);
        }

        boolean isStandIn = receipt.isStandIn() != null ? receipt.isStandIn() : false;

        LocalDateTime paymentDateTime =
                receipt.getPaymentDateTime() != null
                        ? req
                        .getReceipt()
                        .getPaymentDateTime()
                        .toGregorianCalendar()
                        .toZonedDateTime()
                        .toLocalDateTime()
                        : null;

        PaymentOptionModel body =
                PaymentOptionModel.builder()
                        .idReceipt(receipt.getReceiptId())
                        .paymentDate(paymentDateTime)
                        .pspCode(receipt.getIdPSP())
                        .pspTaxCode(receipt.getPspFiscalCode())
                        .pspCompany(receipt.getPSPCompanyName())
                        .paymentMethod(receipt.getPaymentMethod())
                        .fee(String.valueOf(CommonUtil.getFeeInCent(receipt.getFee())))
                        .build();

        return this.setPaidPaymentOptionRetryIfException(
                receipt.getNoticeNumber(),
                req.getIdPA(),
                receipt.getCreditorReferenceId(),
                isStandIn,
                body,
                receiptEntity);
    }
    
    private PaymentOptionModelResponse setPaidPaymentOptionRetryIfException(
            String noticeNumber,
            String idPa,
            String creditorReferenceId,
            boolean isStandIn,
            PaymentOptionModel body,
            ReceiptEntity receiptEntity) {
        try {
            return paymentOptService.sendReceiptToGPD(noticeNumber, idPa, creditorReferenceId, isStandIn, body, receiptEntity);
        } catch (RetryableException e) {
            log.error("[getReceiptPaymentOption] PAA_SYSTEM_ERROR: GPD Not Reachable [noticeNumber={}]", noticeNumber, e);
            this.retry(receiptEntity);

            throw new PartnerValidationException(PaaErrorEnum.PAA_SYSTEM_ERROR);
        } catch (FeignException e) {
            log.error("[getReceiptPaymentOption] PAA_SEMANTICA: GPD Error Response [noticeNumber={}]", noticeNumber, e);
            this.retry(receiptEntity);

            throw new PartnerValidationException(PaaErrorEnum.PAA_SEMANTICA);
        } catch (StorageException e) {
            log.error("[getReceiptPaymentOption] PAA_SYSTEM_ERROR: Storage exception [noticeNumber={}]", noticeNumber, e);
            this.retry(receiptEntity);

            throw new PartnerValidationException(PaaErrorEnum.PAA_SYSTEM_ERROR);
        } catch (PartnerValidationException e) {
            // { PAA_RECEIPT_DUPLICATA, PAA_PAGAMENTO_SCONOSCIUTO }
            throw e;
        } catch (Exception e) {
            // no retry because the long-term retry is enabled only when there is a gpd-core error
            // response or a storage communication failure
            log.error("[getReceiptPaymentOption] PAA_SYSTEM_ERROR: GPD Generic Error [noticeNumber={}]", noticeNumber, e);
            throw new PartnerValidationException(PaaErrorEnum.PAA_SYSTEM_ERROR);
        }
    }

    private void retry(ReceiptEntity receiptEntity) {
        String receiptXML = receiptEntity.getDocument();
        Duration visibility = Duration.ofSeconds(queueSendInvisibilityTime);
        queueClient.sendMessageWithResponse(receiptXML, visibility, null, null, Context.NONE);
    }

    public PaymentOptionModelResponse setPaidPaymentOption(
            String noticeNumber,
            String idPa,
            String creditorReferenceId,
            boolean isStandIn,
            PaymentOptionModel body,
            ReceiptEntity receiptEntity)
            throws FeignException, URISyntaxException, InvalidKeyException, StorageException {
        return paymentOptService.sendReceiptToGPD(noticeNumber, idPa, creditorReferenceId, isStandIn, body, receiptEntity);
    }
}
