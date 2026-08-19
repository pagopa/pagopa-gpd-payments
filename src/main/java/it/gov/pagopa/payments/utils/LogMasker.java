package it.gov.pagopa.payments.utils;

import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;

/**
 * Utilities to keep PII (Personal Identifiable Information) and sensitive financial data out of the
 * logs.
 *
 * <p>Values that identify a natural person (personal fiscal code, e-mail, full name, address) or a
 * bank account (IBAN) must never be written in clear text: they are either dropped by the caller or
 * masked through this class.
 */
@UtilityClass
public class LogMasker {

  public static final String MASK = "****";

  /** Italian personal fiscal code, e.g. {@code RSSMRA80A01H501U}. */
  private static final Pattern PERSONAL_FISCAL_CODE =
      Pattern.compile("[A-Za-z]{6}\\d{2}[A-Za-z]\\d{2}[A-Za-z]\\d{3}[A-Za-z]");

  /** IBAN, e.g. {@code IT60X0542811101000000123456}. */
  private static final Pattern IBAN = Pattern.compile("[A-Z]{2}\\d{2}[A-Za-z0-9]{11,30}");

  private static final Pattern EMAIL =
      Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

  /**
   * Masks a value keeping only the first and the last two characters, enough to correlate log
   * entries without disclosing the value itself.
   *
   * @param value the value to mask, may be {@code null}
   * @return the masked value, or {@code null} if the input is {@code null}
   */
  public static String mask(String value) {
    if (value == null) {
      return null;
    }
    if (value.length() <= 4) {
      return MASK;
    }
    return value.substring(0, 2) + MASK + value.substring(value.length() - 2);
  }

  /**
   * Masks the value only when it looks like the fiscal code of a natural person. Fiscal codes of
   * public administrations (numeric VAT-like codes) are business identifiers, not PII, and are kept
   * in clear text so that they remain searchable.
   *
   * @param fiscalCode the fiscal code to evaluate, may be {@code null}
   * @return the fiscal code, masked when personal
   */
  public static String maskIfPersonal(String fiscalCode) {
    if (fiscalCode == null || fiscalCode.isBlank()) {
      return null;
    }
    return PERSONAL_FISCAL_CODE.matcher(fiscalCode).matches() ? mask(fiscalCode) : fiscalCode;
  }

  /**
   * Removes PII and financial data from a free text (e.g. a validation error or an exception
   * message) that could accidentally quote a payload value.
   *
   * @param text the text to redact, may be {@code null}
   * @return the redacted text, or {@code null} if the input is {@code null}
   */
  public static String redact(String text) {
    if (text == null || text.isBlank()) {
      return text;
    }
    String redacted = EMAIL.matcher(text).replaceAll(MASK);
    redacted = PERSONAL_FISCAL_CODE.matcher(redacted).replaceAll(MASK);
    return IBAN.matcher(redacted).replaceAll(MASK);
  }
}
