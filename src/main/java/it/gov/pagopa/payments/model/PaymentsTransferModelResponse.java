package it.gov.pagopa.payments.model;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString
@Builder
public class PaymentsTransferModelResponse implements Serializable {

  private static final long serialVersionUID = -5561690846606532251L;
  
  private String organizationFiscalCode;
  private String idTransfer;
  private long amount;
  private String remittanceInformation; // causale
  private String category; // taxonomy
  private String iban;
  private Stamp stamp;
  private String postalIban;
  private LocalDateTime insertedDate;
  private TransferStatus status;
  private LocalDateTime lastUpdatedDate;
  @Builder.Default
  private List<TransferMetadataModel> transferMetadata = new ArrayList<TransferMetadataModel>();
}
