package it.gov.pagopa.payments.model.spontaneous;

import java.io.Serializable;
import javax.validation.constraints.NotBlank;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class ServicePropertyModel implements Serializable {

  @NotBlank private String name;

  private String value;
}
