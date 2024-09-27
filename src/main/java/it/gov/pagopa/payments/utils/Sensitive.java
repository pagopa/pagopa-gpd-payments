package it.gov.pagopa.payments.utils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Annotation to obfuscate the data during the toString()
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface Sensitive {
}
