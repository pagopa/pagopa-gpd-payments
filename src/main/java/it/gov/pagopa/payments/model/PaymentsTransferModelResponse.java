package it.gov.pagopa.payments.model;

import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class PaymentsTransferModelResponse implements Serializable {

  private String organizationFiscalCode;
  private String idTransfer;
  private long amount;
  private String remittanceInformation; // causale
  private String category; // taxonomy
  private String iban;
  private MarcaDaBollo marcaDaBollo;
  private String postalIban;
  private LocalDateTime insertedDate;
  private TransferStatus status;
  private LocalDateTime lastUpdatedDate;
}
