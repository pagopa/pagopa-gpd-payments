package it.gov.pagopa.payments.model.spontaneous;

import it.gov.pagopa.payments.model.Type;
import java.io.Serializable;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class DebtorModel implements Serializable {

  @NotNull(message = "type is required")
  private Type type;

  @NotBlank(message = "fiscal code is required")
  private String fiscalCode;

  @NotBlank(message = "full name is required")
  private String fullName;

  private String streetName;

  private String civicNumber;

  private String postalCode;

  private String city;

  private String province;

  private String region;

  private String country;

  @Email(message = "Please provide a valid email address")
  private String email;

  private String phone;
}
