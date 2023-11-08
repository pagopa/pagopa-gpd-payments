package it.gov.pagopa.payments.model;

import lombok.ToString;

@ToString
public enum DebtPositionStatus {
  DRAFT,
  PUBLISHED,
  VALID,
  INVALID,
  EXPIRED,
  PARTIALLY_PAID,
  PAID,
  REPORTED;
}
