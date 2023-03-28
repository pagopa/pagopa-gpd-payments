package it.gov.pagopa.payments.model;

import java.io.Serializable;
import javax.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarcaDaBollo implements Serializable {
  private static final long serialVersionUID = -5862140737726963810L;

  @NotBlank private String hashDocumento;
  @NotBlank private String tipoBollo;
  @NotBlank private String provinciaResidenza;
}
