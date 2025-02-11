package it.gov.pagopa.payments.model.enumeration;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ReceiptStatus {
    CREATED,
    PAID,
    ERROR
}