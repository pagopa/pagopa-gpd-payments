package it.gov.pagopa.payments.utils;

import it.gov.pagopa.payments.endpoints.validation.exceptions.PartnerValidationException;
import it.gov.pagopa.payments.model.DebtPositionStatus;
import it.gov.pagopa.payments.model.PaaErrorEnum;
import it.gov.pagopa.payments.model.PaymentOptionStatus;
import it.gov.pagopa.payments.model.PaymentsModelResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

@Slf4j
public class Validator {
    public static final int PAYMENT_OPTION_DESCRIPTION_MAX = 140;
    public static final int OFFICE_NAME_MAX = 140;
    public static final int COMPANY_NAME_MAX = 140;
    public static final String DEFAULT_VALUE = "NA";

    /**
     * Validate and corrects the office name in case of rectifiable errors
     *
     * @param officeName from GPD-Core response model {@link PaymentsModelResponse}
     */
    public static String validateOfficeName(String officeName) {
        return Optional.ofNullable(officeName)
                       .map(office -> office.length() > OFFICE_NAME_MAX ? office.substring(0, OFFICE_NAME_MAX) : office)
                       .orElse(null);
    }

    /**
     * Validate and corrects the company name in case of rectifiable errors
     *
     * @param companyName from GPD-Core response model {@link PaymentsModelResponse}
     */
    public static String validateCompanyName(String companyName) {
        return Optional.ofNullable(companyName)
                       .map(company -> company.isEmpty() ? DEFAULT_VALUE : company)
                       .map(company -> company.length() > COMPANY_NAME_MAX ? company.substring(0, COMPANY_NAME_MAX) : company)
                       .orElse(DEFAULT_VALUE);
    }

    /**
     * Validate and corrects the payment option description in case of rectifiable errors
     *
     * @param description from GPD-Core response model {@link PaymentsModelResponse}
     */
    public static String validatePaymentOptionDescription(String description) {
        return Optional.ofNullable(description)
                       .map(desc -> desc.isEmpty() ? DEFAULT_VALUE : desc)
                       .map(desc -> desc.length() > PAYMENT_OPTION_DESCRIPTION_MAX ? desc.substring(0, PAYMENT_OPTION_DESCRIPTION_MAX) : desc)
                       .orElse(DEFAULT_VALUE);
    }

    private static final String DEBT_POSITION_STATUS_ERROR =
            "[Check DP] Debt position status error: ";

    /**
     * Verify debt position status
     *
     * @param paymentOption {@link PaymentsModelResponse} response from GPD
     */
    public static void checkDebtPositionStatus(PaymentsModelResponse paymentOption) {
        String iuvLog = " [iuv=" + paymentOption.getIuv() + ", nav=" + paymentOption.getNav() + "]";

        try {
            if (paymentOption.getDebtPositionStatus().equals(DebtPositionStatus.EXPIRED)) {
                throw new PartnerValidationException(PaaErrorEnum.PAA_PAGAMENTO_SCADUTO);
            } else if (paymentOption.getDebtPositionStatus().equals(DebtPositionStatus.INVALID)) {
                throw new PartnerValidationException(PaaErrorEnum.PAA_PAGAMENTO_ANNULLATO);
            } else if (paymentOption.getDebtPositionStatus().equals(DebtPositionStatus.DRAFT)
                    || paymentOption.getDebtPositionStatus().equals(DebtPositionStatus.PUBLISHED)) {
                throw new PartnerValidationException(PaaErrorEnum.PAA_PAGAMENTO_SCONOSCIUTO);
            } else if (paymentOption.getDebtPositionStatus().equals(DebtPositionStatus.PAID)
                    || paymentOption.getStatus().equals(PaymentOptionStatus.PO_PAID)
                    || paymentOption.getDebtPositionStatus().equals(DebtPositionStatus.REPORTED)) {
                throw new PartnerValidationException(PaaErrorEnum.PAA_PAGAMENTO_DUPLICATO);
            }
        } catch (Exception e) {
            log.error(DEBT_POSITION_STATUS_ERROR + "{}{}", paymentOption.getDebtPositionStatus(), iuvLog);
            throw e;
        }
    }
}
