package it.gov.pagopa.payments.endpoints.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

  private ListAppender<ILoggingEvent> appender;
  private SoapFaultDefinitionExceptionResolver resolver;

  @BeforeEach
  void setUp() {
    appender = new ListAppender<>();
    appender.start();
    ((Logger) LoggerFactory.getLogger(SoapFaultDefinitionExceptionResolver.class))
        .addAppender(appender);
    resolver = new SoapFaultDefinitionExceptionResolver();
  }

  @AfterEach
  void tearDown() {
    ((Logger) LoggerFactory.getLogger(SoapFaultDefinitionExceptionResolver.class))
        .detachAppender(appender);
  }

  @Test
  void handledFaultIsLoggedAtInfoWithoutStackTrace() {
    PartnerValidationException exception =
        new PartnerValidationException(PaaErrorEnum.PAA_PAGAMENTO_SCONOSCIUTO);

    assertThrows(
        PartnerValidationException.class, () -> resolver.resolveException(null, null, exception));

    assertEquals(1, appender.list.size());
    ILoggingEvent event = appender.list.get(0);
    assertEquals(Level.INFO, event.getLevel());
    assertNull(event.getThrowableProxy(), "a handled fault must not carry a stack trace");
    assertFalse(event.getFormattedMessage().contains("Exception"));
  }

  @Test
  void blockingFailureKeepsErrorLevelAndStackTrace() {
    IllegalStateException exception = new IllegalStateException("ko");

    assertFalse(resolver.resolveException(null, null, exception));

    ILoggingEvent event = appender.list.get(0);
    assertEquals(Level.ERROR, event.getLevel());
    assertEquals(
        IllegalStateException.class.getName(), event.getThrowableProxy().getClassName());
  }
}
