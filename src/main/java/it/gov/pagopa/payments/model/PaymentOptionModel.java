package it.gov.pagopa.payments.model;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PaymentOptionModel implements Serializable {

  /** */
  private static final long serialVersionUID = -7904798992919216489L;

  @NotNull(message = "paymentDate is required")
  private LocalDateTime paymentDate;

  @NotBlank(message = "paymentMethod is required")
  private String paymentMethod;

  @NotBlank(message = "pspCode is required")
  private String pspCode;

  // optional pspFiscalCode https://github.com/pagopa/pagopa-api/blob/c752179c66da9e3a2a71dd16397fde6b0ad08818/wsdl/xsd/paForNode.xsd#L22
  private String pspTaxCode;

  @NotBlank(message = "pspCompany is required")
  private String pspCompany;

  @NotBlank(message = "idReceipt is required")
  private String idReceipt;

  @NotBlank(message = "fee is required")
  @Builder.Default
  private String fee = "0";

  @Valid
  @Size(min=0, max=10)
  @Schema(description = "it can added a maximum of 10 key-value pairs for metadata")
  private List<PaymentOptionMetadataModel> paymentOptionMetadata = new ArrayList<>();
}
