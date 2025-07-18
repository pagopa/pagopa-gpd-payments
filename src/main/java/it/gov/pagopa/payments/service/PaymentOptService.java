package it.gov.pagopa.payments.service;

import com.microsoft.azure.storage.StorageException;
import feign.FeignException;
import it.gov.pagopa.payments.client.GpdClient;
import it.gov.pagopa.payments.endpoints.validation.exceptions.PartnerValidationException;
import it.gov.pagopa.payments.entity.ReceiptEntity;
import it.gov.pagopa.payments.model.*;
import it.gov.pagopa.payments.model.client.cache.ConfigCacheData;
import it.gov.pagopa.payments.model.client.cache.MaintenanceStation;
import it.gov.pagopa.payments.repository.ReceiptRepository;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
@NoArgsConstructor
public class PaymentOptService {

    private GpdClient gpdClient;
    private ReceiptRepository receiptRepository;

    public PaymentOptService(@Autowired GpdClient gpdClient, @Autowired ReceiptRepository receiptRepository) {
        this.gpdClient = gpdClient;
        this.receiptRepository = receiptRepository;
    }

    public static final String SERVICE_TYPE_ACA = "ACA";

    public PaymentsModelResponse getAndValidatePaymentOption(
            String idPa, String stationId, String noticeNumber, String serviceType) {

        PaymentsModelResponse paymentOption;

        if (SERVICE_TYPE_ACA.equalsIgnoreCase(serviceType)) {
            paymentOption = paymentOptionFlowACA(idPa, stationId, noticeNumber);
        } else {
            // Searching debt position. If something went wrong, an exception is thrown and
            // the caller will handle it.
            paymentOption = gpdClient.getPaymentOption(idPa, noticeNumber);
        }

        return paymentOption;
    }

    public PaymentOptionModelResponse sendReceiptToGPD(
            String noticeNumber,
            String idPa,
            String creditorReferenceId,
            boolean isStandIn,
            PaymentOptionModel body,
            ReceiptEntity receiptEntity)
            throws FeignException, StorageException {
        PaymentOptionModelResponse paymentOption = new PaymentOptionModelResponse();
        try {
            paymentOption = gpdClient.sendPaymentOptionReceipt(idPa, noticeNumber, body);

            boolean isNotACA = true; // default SERVICE_TYPE_GPD
            if(paymentOption.getServiceType() != null) {
                isNotACA = !paymentOption.getServiceType().equals(SERVICE_TYPE_ACA);
            }

            // Saving the receipt if the PaymentOption is in the PO_PAID status and service type isn't ACA,
            // includes all other service types ie GPD, WISP. ACA receipts are saved only if they are stand-in payments.
            if (PaymentOptionStatus.PO_PAID.equals(paymentOption.getStatus()) && (isNotACA || isStandIn)) {
                receiptRepository.saveReceipt(receiptEntity);
            }
        } catch (FeignException.Conflict e) {
            // if PO is already paid on GPD --> checks and in case creates the receipt in PAID status.
            try {
                log.error(
                        "[getReceiptPaymentOption] PAA_RECEIPT_DUPLICATA: GPD Conflict Error Response [noticeNumber={}]",
                        noticeNumber,
                        e);
                boolean receiptNotFoundInStorage = receiptRepository.getReceipt(idPa, creditorReferenceId) == null;

                boolean isNotACA = true; // default SERVICE_TYPE_GPD
                if(paymentOption.getServiceType() != null) {
                    isNotACA = !paymentOption.getServiceType().equals(SERVICE_TYPE_ACA);
                }

                if (receiptNotFoundInStorage && (isNotACA || isStandIn)) {
                    // if receipt is not found, or it's a {GPD,WISP} payment, or it's an ACA payments paid in stand-in,
                    // the receipt will be saved in the storage with the PAID status.
                    receiptRepository.saveReceipt(receiptEntity);
                }
            } catch (Exception ex) {
                log.error(
                        "[getReceiptPaymentOption] GPD Generic Error [noticeNumber={}] during receipt status"
                                + " save",
                        noticeNumber,
                        e);
            }
            throw new PartnerValidationException(PaaErrorEnum.PAA_RECEIPT_DUPLICATA);
        } catch (FeignException.NotFound e) {
            log.error(
                    "[getReceiptPaymentOption] PAA_PAGAMENTO_SCONOSCIUTO: GPD Not Found Error Response [noticeNumber={}]",
                    noticeNumber,
                    e);
            throw new PartnerValidationException(PaaErrorEnum.PAA_PAGAMENTO_SCONOSCIUTO);
        } catch (PartnerValidationException e) {
            throw e;
        }
        return paymentOption;
    }

    private PaymentsModelResponse paymentOptionFlowACA(String idPa, String stationId, String noticeNumber) {
        log.debug(
                "[getAndValidatePaymentOption] Debt position in ACA required to be handled in Stand-In mode [noticeNumber={}]",
                noticeNumber);

        if (ConfigCacheData.isConfigDataNull()) {
            log.error(
                    "[getAndValidatePaymentOption] ACA checks cannot be made: cache values were not properly set.");
            throw new PartnerValidationException(PaaErrorEnum.PAA_SYSTEM_ERROR);
        }

        // check if station is in maintenance and if is required to handle payments in StandIn mode,
        // otherwise throw exception
        MaintenanceStation stationInMaintenance = ConfigCacheData.getStationInMaintenance(stationId);

        if (stationInMaintenance != null && !stationInMaintenance.getIsStandin()) {
            OffsetDateTime startDatetime = OffsetDateTime.parse(
                    String.valueOf(stationInMaintenance.getStartDate()),
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME);

            // check if station maintenance is in progress, ie started
            if(Instant.now().toEpochMilli() > startDatetime.toInstant().toEpochMilli()) {
                log.error(
                        "[getAndValidatePaymentOption] Station under maintenance but Stand-In mode not enabled [station={}]",
                        stationId);
                throw new PartnerValidationException(PaaErrorEnum.PAA_PAGAMENTO_SCONOSCIUTO);
            }
        }

        // check if relation between station and creditor institution permits ACA flow,
        // otherwise throw exception
        ConfigCacheData.StationCI creditorInstitutionStation =
                ConfigCacheData.getCreditorInstitutionStation(idPa, stationId);
        if (creditorInstitutionStation == null || !creditorInstitutionStation.isAca()) {
            log.error(
                    "[getAndValidatePaymentOption] Station not enabled for ACA payments for this creditor institution [station={}, creditorInstitution={}]",
                    stationId,
                    idPa);
            throw new PartnerValidationException(PaaErrorEnum.PAA_PAGAMENTO_SCONOSCIUTO);
        }

        // Searching debt position and, if something went wrong, an exception is thrown and
        // the caller will handle it.
        PaymentsModelResponse paymentOption = gpdClient.getPaymentOption(idPa, noticeNumber);

        // check if the retrieved payment option is related to a debt position generated by ACA
        if (paymentOption == null
                || !SERVICE_TYPE_ACA.equalsIgnoreCase(paymentOption.getServiceType())) {
            log.error(
                    "[getAndValidatePaymentOption] Payment not generated by ACA service [noticeNumber={}]",
                    noticeNumber);
            throw new PartnerValidationException(PaaErrorEnum.PAA_PAGAMENTO_SCONOSCIUTO);
        }

        // check if relation between station and creditor institution permits StandIn payments,
        // otherwise throw exception
        if (!creditorInstitutionStation.isStandin()) {
            log.error(
                    "[getAndValidatePaymentOption] Station not enabled for Stand-In mode for this creditor institution [station={}, creditorInstitution={}]",
                    stationId,
                    idPa);
            throw new PartnerValidationException(PaaErrorEnum.PAA_PAGAMENTO_SCONOSCIUTO);
        }

        // check if payment was flagged to be paid in StandIn mode, otherwise throw exception
        if (Boolean.FALSE.equals(paymentOption.getPayStandIn())) {
            log.error(
                    "[getAndValidatePaymentOption] Debt position cannot be paid in Stand-In mode [noticeNumber={}]",
                    noticeNumber);
            throw new PartnerValidationException(PaaErrorEnum.PAA_PAGAMENTO_SCONOSCIUTO);
        }

        return paymentOption;
    }
}
