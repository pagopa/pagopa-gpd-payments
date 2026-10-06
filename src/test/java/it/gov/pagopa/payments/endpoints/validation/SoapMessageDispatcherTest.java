package it.gov.pagopa.payments.endpoints.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import it.gov.pagopa.payments.endpoints.validation.exceptions.PartnerValidationException;
import it.gov.pagopa.payments.model.PaaErrorEnum;
import it.gov.pagopa.payments.model.partner.ObjectFactory;
import java.io.IOException;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class SoapMessageDispatcherTest {

  @InjectMocks @Spy SoapMessageDispatcher soapMessageDispatcher;

  @Mock HttpServletRequest request;

  @Mock HttpServletResponse response;

  @Mock private ObjectFactory factory;

  private final ObjectFactory factoryUtil = new ObjectFactory();

  private final Logger logger = (Logger) LoggerFactory.getLogger(SoapMessageDispatcher.class);
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

  @BeforeEach
  void attachAppender() {
    appender.start();
    logger.addAppender(appender);
  }

  // RequestFilter clears the MDC in production
  @AfterEach
  void detachAppenderAndClearMdc() {
    logger.detachAppender(appender);
    MDC.clear();
  }

  @Test
  void paVerifyPaymentNotice() throws Exception {
    when(factory.createCtFaultBean()).thenReturn(factoryUtil.createCtFaultBean());
    when(factory.createPaVerifyPaymentNoticeRes())
        .thenReturn(factoryUtil.createPaVerifyPaymentNoticeRes());
    when(factory.createPaVerifyPaymentNoticeRes(any()))
        .thenReturn(
            factoryUtil.createPaVerifyPaymentNoticeRes(
                factoryUtil.createPaVerifyPaymentNoticeRes()));
    when(factory.createCtPaymentOptionDescriptionPA())
        .thenReturn(factoryUtil.createCtPaymentOptionDescriptionPA());
    when(factory.createCtPaymentOptionsDescriptionListPA())
        .thenReturn(factoryUtil.createCtPaymentOptionsDescriptionListPA());

    when(request.getHeader("SOAPAction")).thenReturn("paVerifyPaymentNotice");
    when(response.getOutputStream()).thenReturn(outputStream);

    doThrow(new PartnerValidationException(PaaErrorEnum.PAA_SEMANTICA))
        .when(soapMessageDispatcher)
        .callService(any(), any());

    soapMessageDispatcher.doService(request, response);

    verify(response, times(1)).getOutputStream();
  }

  @Test
  void paGetPayment() throws Exception {
    when(factory.createCtFaultBean()).thenReturn(factoryUtil.createCtFaultBean());
    when(factory.createPaGetPaymentRes()).thenReturn(factoryUtil.createPaGetPaymentRes());
    when(factory.createPaGetPaymentRes(any()))
        .thenReturn(factoryUtil.createPaGetPaymentRes(factoryUtil.createPaGetPaymentRes()));

    when(request.getHeader("SOAPAction")).thenReturn("paGetPayment");
    when(response.getOutputStream()).thenReturn(outputStream);

    doThrow(new PartnerValidationException(PaaErrorEnum.PAA_SEMANTICA))
        .when(soapMessageDispatcher)
        .callService(any(), any());

    soapMessageDispatcher.doService(request, response);

    verify(response, times(1)).getOutputStream();
  }

  @Test
  void paSendRT() throws Exception {
    when(factory.createCtFaultBean()).thenReturn(factoryUtil.createCtFaultBean());
    when(factory.createPaSendRTRes()).thenReturn(factoryUtil.createPaSendRTRes());
    when(factory.createPaSendRTRes(any()))
        .thenReturn(factoryUtil.createPaSendRTRes(factoryUtil.createPaSendRTRes()));

    when(request.getHeader("SOAPAction")).thenReturn("paSendRT");
    when(response.getOutputStream()).thenReturn(outputStream);

    doThrow(new PartnerValidationException(PaaErrorEnum.PAA_SEMANTICA))
        .when(soapMessageDispatcher)
        .callService(any(), any());

    soapMessageDispatcher.doService(request, response);

    verify(response, times(1)).getOutputStream();
  }

  @Test
  void handledFaultIsLoggedOnceAsTheOperationOutcome() throws Exception {
    when(factory.createCtFaultBean()).thenReturn(factoryUtil.createCtFaultBean());
    when(factory.createPaSendRTRes()).thenReturn(factoryUtil.createPaSendRTRes());
    when(factory.createPaSendRTRes(any()))
        .thenReturn(factoryUtil.createPaSendRTRes(factoryUtil.createPaSendRTRes()));

    when(request.getHeader("SOAPAction")).thenReturn("paSendRT");
    when(response.getOutputStream()).thenReturn(outputStream);

    doThrow(new PartnerValidationException(PaaErrorEnum.PAA_PAGAMENTO_SCONOSCIUTO))
        .when(soapMessageDispatcher)
        .callService(any(), any());

    soapMessageDispatcher.doService(request, response);

    assertEquals(1, appender.list.size());
    ILoggingEvent event = appender.list.get(0);
    assertEquals(Level.INFO, event.getLevel());
    assertEquals("Completed API operation", event.getFormattedMessage());
    assertNull(event.getThrowableProxy());
    assertEquals("paSendRT", event.getMDCPropertyMap().get("event_action"));
    assertEquals("failure", event.getMDCPropertyMap().get("event_outcome"));
    assertEquals(
        "PAA_PAGAMENTO_SCONOSCIUTO", event.getMDCPropertyMap().get("error.code"));
  }

  @Test
  void handledFaultKeepsTheOperationSetByTheEndpointAspect() throws Exception {
    MDC.put("event_action", "paGetPaymentV2");
    doThrow(new PartnerValidationException(PaaErrorEnum.PAA_PAGAMENTO_SCONOSCIUTO))
        .when(soapMessageDispatcher)
        .callService(any(), any());

    soapMessageDispatcher.doService(request, response);

    assertEquals("paGetPaymentV2", appender.list.get(0).getMDCPropertyMap().get("event_action"));
  }

  @Test
  void handledFaultWithoutSoapActionIsStillLogged() throws Exception {
    doThrow(new PartnerValidationException(PaaErrorEnum.PAA_SINTASSI_XSD))
        .when(soapMessageDispatcher)
        .callService(any(), any());

    soapMessageDispatcher.doService(request, response);

    ILoggingEvent event = appender.list.get(0);
    assertNull(event.getMDCPropertyMap().get("event_action"));
    assertEquals("PAA_SINTASSI_XSD", event.getMDCPropertyMap().get("error.code"));
  }

  @Test
  void doServiceDefault() throws Exception {
    when(factory.createCtFaultBean()).thenReturn(factoryUtil.createCtFaultBean());
    when(factory.createPaSendRTRes()).thenReturn(factoryUtil.createPaSendRTRes());
    when(factory.createPaSendRTRes(any()))
        .thenReturn(factoryUtil.createPaSendRTRes(factoryUtil.createPaSendRTRes()));

    when(request.getHeader("SOAPAction")).thenReturn("unknown");
    when(response.getOutputStream()).thenReturn(outputStream);

    doThrow(new PartnerValidationException(PaaErrorEnum.PAA_SEMANTICA))
        .when(soapMessageDispatcher)
        .callService(any(), any());

    soapMessageDispatcher.doService(request, response);

    verify(response, times(1)).getOutputStream();
  }

  public static final ServletOutputStream outputStream =
      new ServletOutputStream() {
        @Override
        public boolean isReady() {
          return false;
        }

        @Override
        public void setWriteListener(WriteListener listener) {}

        @Override
        public void write(int b) throws IOException {}
      };
}
