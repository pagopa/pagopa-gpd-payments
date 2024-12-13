package it.gov.pagopa.payments.model.spontaneous;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import javax.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class SpontaneousPaymentOptionModel implements Serializable {

  @NotBlank(message = "iuv is required")
  private String iuv;

  @NotBlank(message = "organizationFiscalCode is required")
  private String organizationFiscalCode;

  @NotBlank(message = "amount is required")
  private long amount;

  private String description;

  @NotBlank(message = "is partial payment is required")
  private Boolean isPartialPayment;

  @NotBlank(message = "due date is required")
  private LocalDateTime dueDate;

  private LocalDateTime retentionDate;

  private long fee;

  private List<TransferModel> transfer;
}
