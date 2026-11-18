package it.gov.pagopa.payments.endpoints.validation.exceptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import it.gov.pagopa.payments.model.PaaErrorEnum;
import org.junit.jupiter.api.Test;

class PartnerValidationExceptionTest {

  @Test
  void getError() {
    var ex = new PartnerValidationException(PaaErrorEnum.PAA_SEMANTICA);
    assertEquals(PaaErrorEnum.PAA_SEMANTICA, ex.getError());
    assertNull(ex.getCause());
  }

  @Test
  void preservesCauseWithoutChangingFault() {
    var cause = new IllegalStateException("storage unavailable");
    var ex = new PartnerValidationException(PaaErrorEnum.PAA_SYSTEM_ERROR, cause);

    assertEquals(PaaErrorEnum.PAA_SYSTEM_ERROR, ex.getError());
    assertEquals("PAA_SYSTEM_ERROR, Errore del sistema", ex.getMessage());
    assertSame(cause, ex.getCause());
  }
}
