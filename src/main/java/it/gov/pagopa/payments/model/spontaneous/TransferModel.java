package it.gov.pagopa.payments.model.spontaneous;

import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serializable;
import javax.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class TransferModel implements Serializable {

  @NotBlank(message = "id transfer is required")
  @Schema(
      type = "string",
      allowableValues = {"1", "2", "3", "4", "5"})
  private String idTransfer;

  @NotBlank(message = "amount is required")
  private long amount;

  @NotBlank(message = "remittance information is required")
  private String remittanceInformation; // causale

  @NotBlank(message = "organizationFiscalCode is required")
  private String organizationFiscalCode;

  @NotBlank(message = "category is required")
  private String category; // taxonomy

  @NotBlank(message = "iban is required")
  private String iban;

  private String postalIban;
}
