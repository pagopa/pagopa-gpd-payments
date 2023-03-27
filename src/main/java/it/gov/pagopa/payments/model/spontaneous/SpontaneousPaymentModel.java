package it.gov.pagopa.payments.model.spontaneous;

import java.io.Serializable;
import javax.validation.Valid;
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
public class SpontaneousPaymentModel implements Serializable {

  @Valid @NotNull private DebtorModel debtor;

  @Valid @NotNull private ServiceModel service;
}
