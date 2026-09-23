package it.gov.pagopa.payments.endpoints.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import it.gov.pagopa.payments.endpoints.validation.exceptions.PartnerValidationException;
import it.gov.pagopa.payments.model.PaaErrorEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class SoapFaultDefinitionExceptionResolverTest {

  private final Logger logger =
      (Logger) LoggerFactory.getLogger(SoapFaultDefinitionExceptionResolver.class);
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private final SoapFaultDefinitionExceptionResolver resolver =
      new SoapFaultDefinitionExceptionResolver();

  @BeforeEach
  void setUp() {
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(appender);
  }

  @Test
  void handledFaultIsRethrownWithoutLogging() {
    PartnerValidationException exception =
        new PartnerValidationException(PaaErrorEnum.PAA_PAGAMENTO_SCONOSCIUTO);

    assertThrows(
        PartnerValidationException.class, () -> resolver.resolveException(null, null, exception));

    assertTrue(appender.list.isEmpty());
  }

  @Test
  void blockingFailureKeepsErrorLevelAndStackTrace() {
    IllegalStateException exception = new IllegalStateException("ko");

    assertFalse(resolver.resolveException(null, null, exception));

    ILoggingEvent event = appender.list.get(0);
    assertEquals(Level.ERROR, event.getLevel());
    assertEquals(IllegalStateException.class.getName(), event.getThrowableProxy().getClassName());
  }
}
