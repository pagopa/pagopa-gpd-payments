package it.gov.pagopa.payments.model;

import javax.validation.constraints.NotBlank;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarcaDaBollo {
  @NotBlank private String hashDocumento;
  @NotBlank private String tipoBollo;
  @NotBlank private String provinciaResidenza;
}
