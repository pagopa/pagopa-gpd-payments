package it.gov.pagopa.payments.model;

import it.gov.pagopa.payments.utils.Sensitive;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@Getter
@Setter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
public class ReceiptModelResponse {

  private String organizationFiscalCode;
  private String iuv;
  @Sensitive
  private String debtor;
  private String paymentDateTime;
  private String status;
}
