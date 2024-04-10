package it.gov.pagopa.payments.utils;

import java.util.Optional;

public class Validator {
    public static final int PAYMENT_OPTION_DESCRIPTION_MAX = 140;
    public static final int OFFICE_NAME_MAX = 140;
    public static final int COMPANY_NAME_MAX = 140;
    public static final String DEFAULT_VALUE = "NA";

    public static String validateOfficeName(String officeName) {
        return Optional.ofNullable(officeName)
                                    .map(office -> office.length() > OFFICE_NAME_MAX ? office.substring(OFFICE_NAME_MAX) : office)
                                    .orElse(null);
    }

    public static String validateCompanyName(String companyName) {
        return Optional.ofNullable(companyName)
                .map(company -> company.length() > COMPANY_NAME_MAX ? company.substring(COMPANY_NAME_MAX) : company)
                .orElse(DEFAULT_VALUE);
    }

    public static String validatePaymentOptionDescription(String description) {
        return Optional.ofNullable(description)
                .map(desc -> desc.length() > PAYMENT_OPTION_DESCRIPTION_MAX ? desc.substring(PAYMENT_OPTION_DESCRIPTION_MAX) : desc)
                .orElse(DEFAULT_VALUE);
    }
}
