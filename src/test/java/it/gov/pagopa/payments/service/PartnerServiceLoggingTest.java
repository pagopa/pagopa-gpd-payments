package it.gov.pagopa.payments.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import feign.FeignException;
import it.gov.pagopa.payments.client.GpdClient;
import it.gov.pagopa.payments.endpoints.validation.exceptions.PartnerValidationException;
import it.gov.pagopa.payments.mock.MockUtil;
import it.gov.pagopa.payments.mock.PaVerifyPaymentNoticeReqMock;
import it.gov.pagopa.payments.model.DebtPositionStatus;
import it.gov.pagopa.payments.model.PaaErrorEnum;
import it.gov.pagopa.payments.model.PaymentsModelResponse;
import it.gov.pagopa.payments.model.partner.PaVerifyPaymentNoticeReq;
import java.io.IOException;
import java.util.List;
import javax.xml.datatype.DatatypeConfigurationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

/**
 * Guards the log level of the handled payment faults. They are the bulk of the production log
 * volume, so a regression to ERROR, or a stack trace coming back, must break the build.
 */
@ExtendWith(MockitoExtension.class)
class PartnerServiceLoggingTest {

  private static final String SERVICE_TYPE_GPD = "GPD";

  @InjectMocks private PartnerService partnerService;

  @Mock private GpdClient gpdClient;

  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void setUp() {
    appender = new ListAppender<>();
    appender.start();
    ((Logger) LoggerFactory.getLogger(PartnerService.class)).addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    ((Logger) LoggerFactory.getLogger(PartnerService.class)).detachAppender(appender);
  }

  @Test
  void debtPositionNotFoundIsLoggedAtInfoWithoutStackTrace() throws DatatypeConfigurationException {
    PaVerifyPaymentNoticeReq request = PaVerifyPaymentNoticeReqMock.getMock();
    FeignException.NotFound notFound = Mockito.mock(FeignException.NotFound.class);
    lenient().when(notFound.getSuppressed()).thenReturn(new Throwable[0]);
    when(gpdClient.getPaymentOption(anyString(), anyString())).thenThrow(notFound);

    PartnerValidationException raised =
        assertThrows(
            PartnerValidationException.class,
            () -> partnerService.paVerifyPaymentNotice(request, SERVICE_TYPE_GPD));

    assertEquals(PaaErrorEnum.PAA_PAGAMENTO_SCONOSCIUTO, raised.getError());
    assertNoErrorEvents();
    assertNoStackTraces();
    assertTrue(hasEventAt(Level.INFO), "the handled fault must still leave an INFO milestone");
  }

  @Test
  void nonPayableDebtPositionStatusIsLoggedAtInfoWithoutStackTrace()
      throws DatatypeConfigurationException, IOException {
    PaVerifyPaymentNoticeReq request = PaVerifyPaymentNoticeReqMock.getMock();
    PaymentsModelResponse paymentOption =
        MockUtil.readModelFromFile(
            "gpd/getPaymentOption_PO_UNPAID.json", PaymentsModelResponse.class);
    paymentOption.setDebtPositionStatus(DebtPositionStatus.EXPIRED);
    when(gpdClient.getPaymentOption(anyString(), anyString())).thenReturn(paymentOption);

    PartnerValidationException raised =
        assertThrows(
            PartnerValidationException.class,
            () -> partnerService.paVerifyPaymentNotice(request, SERVICE_TYPE_GPD));

    assertEquals(PaaErrorEnum.PAA_PAGAMENTO_SCADUTO, raised.getError());
    assertNoErrorEvents();
    assertNoStackTraces();
  }

  @Test
  void blockingFailureKeepsErrorLevelAndStackTrace() throws DatatypeConfigurationException {
    PaVerifyPaymentNoticeReq request = PaVerifyPaymentNoticeReqMock.getMock();
    when(gpdClient.getPaymentOption(anyString(), anyString()))
        .thenThrow(new IllegalStateException("GPD unreachable"));

    PartnerValidationException raised =
        assertThrows(
            PartnerValidationException.class,
            () -> partnerService.paVerifyPaymentNotice(request, SERVICE_TYPE_GPD));

    assertEquals(PaaErrorEnum.PAA_SYSTEM_ERROR, raised.getError());
    ILoggingEvent error = eventAt(Level.ERROR);
    assertNotNull(error, "a blocking anomaly must stay at ERROR");
    assertNotNull(error.getThrowableProxy(), "a blocking anomaly must carry the stack trace");
  }

  private void assertNoErrorEvents() {
    assertNull(eventAt(Level.ERROR), "a handled fault must not be logged at ERROR");
  }

  private void assertNoStackTraces() {
    List<ILoggingEvent> withTrace =
        appender.list.stream().filter(event -> event.getThrowableProxy() != null).toList();
    assertTrue(withTrace.isEmpty(), "a handled fault must not carry a stack trace");
  }

  private boolean hasEventAt(Level level) {
    return eventAt(level) != null;
  }

  private ILoggingEvent eventAt(Level level) {
    return appender.list.stream()
        .filter(event -> level.equals(event.getLevel()))
        .findFirst()
        .orElse(null);
  }
}
