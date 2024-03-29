package it.gov.pagopa.payments.endpoints.validation.exceptions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import it.gov.pagopa.payments.model.PaaErrorEnum;
import org.junit.jupiter.api.Test;

class PartnerValidationExceptionTest {

  @Test
  void getError() {
    var ex = new PartnerValidationException(PaaErrorEnum.PAA_SEMANTICA);
    assertEquals(PaaErrorEnum.PAA_SEMANTICA, ex.getError());
  }
}
