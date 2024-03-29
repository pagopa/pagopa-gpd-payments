package it.gov.pagopa.payments.model.spontaneous;

import java.io.Serializable;
import java.util.List;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class ServiceModel implements Serializable {

  @NotBlank(message = "service id is required")
  private String id;

  @Valid @NotNull private List<ServicePropertyModel> properties;
}
