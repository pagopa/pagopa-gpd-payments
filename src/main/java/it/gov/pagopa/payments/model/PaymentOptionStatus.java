package it.gov.pagopa.payments.model;

import lombok.ToString;

@ToString
public enum PaymentOptionStatus {
  PO_UNPAID,
  PO_PAID,
  PO_PARTIALLY_REPORTED,
  PO_REPORTED;
}
