package it.gov.pagopa.payments.model.spontaneous;

import it.gov.pagopa.payments.model.Type;
import java.io.Serializable;
import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class DebtorModel implements Serializable {

  @NotNull(message = "type is required")
  private Type type;

  @NotBlank(message = "fiscal code is required")
  @ToString.Exclude
  private String fiscalCode;

  @NotBlank(message = "full name is required")
  @ToString.Exclude
  private String fullName;

  private String streetName;

  private String civicNumber;

  private String postalCode;

  private String city;

  private String province;

  private String region;

  private String country;

  @Email(message = "Please provide a valid email address")
  @ToString.Exclude
  private String email;

  @ToString.Exclude
  private String phone;
}
