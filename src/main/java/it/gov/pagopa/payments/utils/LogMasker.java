package it.gov.pagopa.payments.utils;

import java.util.regex.Pattern;

public final class LogMasker {

  // creditor institution fiscal code: eleven digits, not personal data
  private static final Pattern ORGANIZATION_CODE = Pattern.compile("\\d{11}");

  private static final String REDACTED = "****";

  private LogMasker() {}

  /** Keeps organization codes, masks anything else leaving the first and last two characters. */
  public static String maskIfPersonal(String value) {
    if (value == null || value.isBlank()) {
      return value;
    }
    if (ORGANIZATION_CODE.matcher(value).matches()) {
      return value;
    }
    if (value.length() <= 4) {
      return REDACTED;
    }
    return value.substring(0, 2) + REDACTED + value.substring(value.length() - 2);
  }
}
