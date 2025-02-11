package it.gov.pagopa.payments.model;

import it.gov.pagopa.payments.model.enumeration.ReceiptStatus;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

@Getter
@Setter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
public class ReceiptModelResponse {
  private String organizationFiscalCode;
  private String iuv;
  private String debtor;
  private LocalDateTime paymentDateTime;
  private ReceiptStatus status;
}
