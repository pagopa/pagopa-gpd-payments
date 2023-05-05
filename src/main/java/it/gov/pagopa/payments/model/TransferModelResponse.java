package it.gov.pagopa.payments.model;

import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TransferModelResponse implements Serializable {
  private static final long serialVersionUID = 2528813110300207419L;

  private String organizationFiscalCode;
  private String idTransfer;
  private long amount;
  private String remittanceInformation; // causale
  private String category; // taxonomy
  private String iban;
  private String postalIban;
  private Stamp stamp;
  private LocalDateTime insertedDate;
  private TransferStatus status;
  private LocalDateTime lastUpdatedDate;
}
