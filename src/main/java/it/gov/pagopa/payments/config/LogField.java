package it.gov.pagopa.payments.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Opts a scalar parameter into the operation's logging allowlist. */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface LogField {
  String value();

  boolean argument() default false;
}
