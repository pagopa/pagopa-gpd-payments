package it.gov.pagopa.payments.endpoints.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import it.gov.pagopa.payments.endpoints.validation.exceptions.PartnerValidationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.xml.sax.SAXParseException;

class SoapValidatingInterceptorTest {

  private static final String DEBTOR_FISCAL_CODE = "RSSMRA80A01H501U";

  private final Logger logger = (Logger) LoggerFactory.getLogger(SoapValidatingInterceptor.class);
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private final SoapValidatingInterceptor interceptor = new SoapValidatingInterceptor();

  @BeforeEach
  void setUp() {
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(appender);
    MDC.clear();
  }

  @Test
  void xsdFaultIsAWarningWithoutTheRejectedValue() {
    SAXParseException[] errors = {
      new SAXParseException(
          "cvc-pattern-valid: Value '" + DEBTOR_FISCAL_CODE + "' is not facet-valid",
          null,
          null,
          3,
          14),
      new SAXParseException("Content is not allowed in prolog.", null, null, 1, 1)
    };

    assertThrows(
        PartnerValidationException.class,
        () -> interceptor.handleRequestValidationErrors(null, errors));

    ILoggingEvent event = appender.list.get(0);
    assertEquals(Level.WARN, event.getLevel());
    assertEquals("Rejected SOAP request failing XSD validation", event.getFormattedMessage());
    String details = event.getMDCPropertyMap().get("ctx_details.xsd_errors");
    assertEquals("[3,14]: cvc-pattern-valid -- [1,1]: invalid-xml", details);
    assertFalse(details.contains(DEBTOR_FISCAL_CODE));
  }
}
