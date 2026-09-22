package it.gov.pagopa.payments.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import it.gov.pagopa.payments.mock.PaSendRTReqMock;
import it.gov.pagopa.payments.model.partner.CtEntityUniqueIdentifier;
import it.gov.pagopa.payments.model.partner.CtSubject;
import it.gov.pagopa.payments.model.partner.PaSendRTReq;
import it.gov.pagopa.payments.model.partner.StEntityUniqueIdentifierType;
import it.gov.pagopa.payments.utils.LogMasker;
import java.util.List;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Verifies the milestone-driven logging rules enforced by {@link LoggingAspect}. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LoggingAspectTest {

  private static final String DEBTOR_FISCAL_CODE = "RSSMRA80A01H501U";
  private static final String DEBTOR_FULL_NAME = "Mario Rossi";
  private static final String DEBTOR_EMAIL = "mario.rossi@example.com";

  @Mock private ProceedingJoinPoint joinPoint;
  @Mock private MethodSignature signature;

  private ListAppender<ILoggingEvent> appender;
  private LoggingAspect loggingAspect;

  @BeforeEach
  void setUp() {
    appender = new ListAppender<>();
    appender.start();
    ((Logger) LoggerFactory.getLogger(LoggingAspect.class)).addAppender(appender);

    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/partner");
    loggingAspect = new LoggingAspect(request, new MockHttpServletResponse());
  }

  @AfterEach
  void tearDown() {
    ((Logger) LoggerFactory.getLogger(LoggingAspect.class)).detachAppender(appender);
    MDC.clear();
  }

  @Test
  void apiMilestoneIsASingleEventWithStaticMessageAndBusinessIdentifiers() throws Throwable {
    PaSendRTReq request = requestWithDebtor();
    givenJoinPoint(request, "paSendRT");
    when(joinPoint.proceed()).thenReturn("response");

    loggingAspect.logApiInvocation(joinPoint);

    List<ILoggingEvent> events = appender.list;
    assertEquals(1, events.size(), "a single milestone must be logged per operation");
    ILoggingEvent event = events.get(0);
    assertEquals(Level.INFO, event.getLevel());
    assertEquals("Completed API operation", event.getFormattedMessage());

    assertEquals("377777777777", event.getMDCPropertyMap().get(LogContext.CTX_NAV));
    assertEquals("77777777777", event.getMDCPropertyMap().get(LogContext.CTX_IUV));
    assertEquals(
        "77777777777", event.getMDCPropertyMap().get(LogContext.CTX_ORGANIZATION_FISCAL_CODE));
    assertEquals(
        "c110729d258c4ab1b765fe902aae41d6",
        event.getMDCPropertyMap().get(LogContext.CTX_TRANSACTION_ID));
    assertEquals("success", event.getMDCPropertyMap().get(LoggingAspect.EVENT_OUTCOME));
    assertEquals("77777777777_01", event.getMDCPropertyMap().get(LogContext.CTX_DETAILS_STATION));
  }

  @Test
  void noPiiIsWrittenInTheLogs() throws Throwable {
    PaSendRTReq request = requestWithDebtor();
    givenJoinPoint(request, "paSendRT");
    when(joinPoint.proceed()).thenReturn(request);

    loggingAspect.logApiInvocation(joinPoint);

    ILoggingEvent event = appender.list.get(0);
    String loggedContent = event.getFormattedMessage() + event.getMDCPropertyMap();
    assertFalse(loggedContent.contains(DEBTOR_FISCAL_CODE), "debtor fiscal code must not be logged");
    assertFalse(loggedContent.contains(DEBTOR_FULL_NAME), "debtor name must not be logged");
    assertFalse(loggedContent.contains(DEBTOR_EMAIL), "debtor e-mail must not be logged");
  }

  @Test
  void contextIsRestoredWhenTheOperationEnds() throws Throwable {
    givenJoinPoint(requestWithDebtor(), "paSendRT");
    when(joinPoint.proceed()).thenReturn("response");

    loggingAspect.logApiInvocation(joinPoint);

    assertNull(MDC.get(LogContext.CTX_NAV));
    assertNull(MDC.get(LogContext.CTX_IUV));
    assertNull(MDC.get(LoggingAspect.START_TIME));
  }

  @Test
  void contextSurvivesAFailureSoThatTheOwnerCanLogTheMilestone() throws Throwable {
    givenJoinPoint(requestWithDebtor(), "paSendRT");
    when(joinPoint.proceed()).thenThrow(new IllegalStateException("ko"));

    assertThrows(IllegalStateException.class, () -> loggingAspect.logApiInvocation(joinPoint));

    assertTrue(appender.list.isEmpty(), "the failure milestone belongs to the outcome owner");
    assertEquals("377777777777", MDC.get(LogContext.CTX_NAV));
    assertEquals("77777777777", MDC.get(LogContext.CTX_IUV));
    assertNotNull(MDC.get(LoggingAspect.START_TIME));
  }

  @Test
  void internalServiceStepsAreNotLoggedAtInfoLevel() throws Throwable {
    givenJoinPoint(requestWithDebtor(), "paSendRT");
    when(joinPoint.proceed()).thenReturn("response");

    loggingAspect.logServiceInvocation(joinPoint);

    assertTrue(
        appender.list.stream().noneMatch(event -> Level.INFO.equals(event.getLevel())),
        "internal steps must not produce INFO events");
  }

  @Test
  void ioBoundaryIsLoggedAtInfoLevelWithVolatileDataInDetails() throws Throwable {
    givenJoinPoint(requestWithDebtor(), "sendPaymentOptionReceipt");
    when(joinPoint.proceed()).thenReturn("response");

    loggingAspect.logIoInvocation(joinPoint);

    ILoggingEvent event = appender.list.get(0);
    assertEquals(Level.INFO, event.getLevel());
    assertEquals("Completed I/O operation", event.getFormattedMessage());
    assertEquals(
        "sendPaymentOptionReceipt", event.getMDCPropertyMap().get(LoggingAspect.CTX_DETAILS_PATH));
  }

  @Test
  void personalFiscalCodesAreMaskedWhileOrganizationOnesAreKept() {
    assertEquals("77777777777", LogMasker.maskIfPersonal("77777777777"));
    assertEquals("RS****1U", LogMasker.maskIfPersonal(DEBTOR_FISCAL_CODE));
    assertEquals("payment from ****", LogMasker.redact("payment from " + DEBTOR_EMAIL));
    assertEquals("iban ****", LogMasker.redact("iban IT60X0542811101000000123456"));
  }

  private void givenJoinPoint(Object argument, String methodName) {
    when(joinPoint.getSignature()).thenReturn(signature);
    when(joinPoint.getArgs()).thenReturn(new Object[] {argument});
    when(joinPoint.getTarget()).thenReturn(this);
    when(signature.getName()).thenReturn(methodName);
    when(signature.getParameterNames()).thenReturn(new String[] {"request"});
  }

  private PaSendRTReq requestWithDebtor() throws Exception {
    PaSendRTReq request = PaSendRTReqMock.getMock("77777777777");
    CtEntityUniqueIdentifier uniqueIdentifier = new CtEntityUniqueIdentifier();
    uniqueIdentifier.setEntityUniqueIdentifierType(StEntityUniqueIdentifierType.F);
    uniqueIdentifier.setEntityUniqueIdentifierValue(DEBTOR_FISCAL_CODE);
    CtSubject debtor = new CtSubject();
    debtor.setUniqueIdentifier(uniqueIdentifier);
    debtor.setFullName(DEBTOR_FULL_NAME);
    debtor.setEMail(DEBTOR_EMAIL);
    request.getReceipt().setDebtor(debtor);
    return request;
  }
}
