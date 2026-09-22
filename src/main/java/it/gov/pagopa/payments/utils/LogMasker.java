package it.gov.pagopa.payments.utils;

import java.util.regex.Pattern;

/** Masking applied before any value reaches a log event, as required by the OER guidelines. */
public final class LogMasker {

  /** Creditor institution fiscal code / VAT number: exactly eleven digits, not personal data. */
  private static final Pattern ORGANIZATION_CODE = Pattern.compile("\\d{11}");

  private static final Pattern EMAIL =
      Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

  private static final Pattern IBAN = Pattern.compile("\\b[A-Z]{2}\\d{2}[A-Z0-9]{11,30}\\b");

  private static final String REDACTED = "****";

  private LogMasker() {}

  /**
   * Keeps organization fiscal codes readable and masks anything else, leaving the first and last two
   * characters so that two values stay distinguishable during an investigation.
   */
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

  /** Replaces every e-mail address and IBAN found inside a free text. */
  public static String redact(String text) {
    if (text == null || text.isBlank()) {
      return text;
    }
    String redacted = EMAIL.matcher(text).replaceAll(REDACTED);
    return IBAN.matcher(redacted).replaceAll(REDACTED);
  }
}
