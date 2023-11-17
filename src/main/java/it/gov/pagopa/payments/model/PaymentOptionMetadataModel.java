package it.gov.pagopa.payments.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
public class PaymentOptionMetadataModel implements Serializable {
    private String key;
    private String value;
}