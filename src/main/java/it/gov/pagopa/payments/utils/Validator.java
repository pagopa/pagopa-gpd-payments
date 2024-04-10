package it.gov.pagopa.payments.utils;

import it.gov.pagopa.payments.model.PaymentsModelResponse;

import java.util.Optional;

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
}
