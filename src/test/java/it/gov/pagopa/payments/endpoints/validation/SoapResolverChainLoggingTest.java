package it.gov.pagopa.payments.endpoints.validation;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import it.gov.pagopa.payments.endpoints.validation.exceptions.PartnerValidationException;
import it.gov.pagopa.payments.logging.EventDetails;
import it.gov.pagopa.payments.logging.FailureDiagnostics;
import it.gov.pagopa.payments.logging.LogEventWriter;
import it.gov.pagopa.payments.logging.OperationLogScope;
import it.gov.pagopa.payments.model.PaaErrorEnum;
import it.gov.pagopa.payments.model.partner.ObjectFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.servlet.ServletOutputStream;
import javax.xml.transform.Source;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletConfig;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.ws.config.annotation.EnableWs;
import org.springframework.ws.config.annotation.WsConfigurerAdapter;
import org.springframework.ws.server.EndpointInterceptor;
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;
import org.springframework.ws.soap.server.endpoint.SimpleSoapExceptionResolver;
import org.springframework.ws.transport.http.MessageDispatcherServlet;
import org.springframework.xml.transform.StringSource;

class SoapResolverChainLoggingTest {
  private static final String FIXTURE_PROFILE = "soap-resolver-logging-fixture";
  private static final Logger LOGGER =
      (Logger) LoggerFactory.getLogger("it.gov.pagopa.payments.endpoints.validation");
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>() {
    @Override protected void append(ILoggingEvent event) {
      event.prepareForDeferredProcessing();
      super.append(event);
    }
  };

  @BeforeEach void setUp() {
    MDC.clear();
    logs.start();
    LOGGER.addAppender(logs);
  }

  @AfterEach void tearDown() {
    LOGGER.detachAppender(logs);
    logs.stop();
    assertNull(OperationLogScope.currentOrNull());
    assertNull(MDC.get("operationId"));
    MDC.clear();
  }

  @Test void fallbackKeepsExactStandardSoapFaultAndLogsOriginalAtDispatcher() throws Exception {
    IOException original = new IOException("original framework-consumed diagnostic");
    byte[] baseline;
    try (Fixture fixture = new Fixture(false)) {
      fixture.endpoint.failure = original;
      var response = fixture.send(new MockHttpServletResponse());
      assertEquals(500, response.getStatus());
      baseline = response.getContentAsByteArray();
      assertTrue(diagnostics().isEmpty());
    }
    try (Fixture fixture = new Fixture(true)) {
      fixture.endpoint.failure = original;
      var response = fixture.send(new MockHttpServletResponse());
      assertEquals(500, response.getStatus());
      assertArrayEquals(baseline, response.getContentAsByteArray());
      assertTrue(response.getContentAsString().contains("Fault"));
      assertTrue(response.getContentAsString().contains(original.getMessage()));
      assertEquals(1, diagnostics().size());
      var diagnostic = diagnostics().get(0);
      assertEquals(SoapMessageDispatcher.class.getName(), diagnostic.getLoggerName());
      assertEquals(IOException.class.getName(), diagnostic.getMDCPropertyMap().get("error.type"));
      assertEquals(original.getMessage(), diagnostic.getMDCPropertyMap().get("error.message"));
      assertTrue(diagnostic.getMDCPropertyMap().get("error.stack_trace").contains("IOException"));
      assertNull(diagnostic.getThrowableProxy());
      assertEquals(1, summaries().size());
      assertEquals("KO", summaries().get(0).getMDCPropertyMap().get("status"));
      assertEquals("500", summaries().get(0).getMDCPropertyMap().get("httpCode"));
      assertFalse(summaries().get(0).getMDCPropertyMap().containsKey("soapOutcome"));
      assertFalse(summaries().get(0).getMDCPropertyMap().containsKey("error.stack_trace"));

      fixture.endpoint.failure = null;
      var next = fixture.send(new MockHttpServletResponse());
      assertEquals(200, next.getStatus());
      assertTrue(next.getContentAsString().contains("<outcome>OK</outcome>"));
      assertEquals(1, diagnostics().size());
      assertEquals(2, summaries().size());
      assertEquals("OK", summaries().get(1).getMDCPropertyMap().get("status"));
      assertNotEquals(summaries().get(0).getMDCPropertyMap().get("operationId"),
          summaries().get(1).getMDCPropertyMap().get("operationId"));
    }
  }

  @Test void earlierAbsorbedDiagnosisDoesNotSuppressUnrelatedFrameworkFailure() throws Exception {
    try (Fixture fixture = new Fixture(true)) {
      fixture.endpoint.absorbEarlierFailure = true;
      fixture.endpoint.failure = new IOException("new unhandled failure");
      assertEquals(500, fixture.send(new MockHttpServletResponse()).getStatus());
      assertEquals(2, diagnostics().size());
      assertEquals("earlier absorbed failure",
          diagnostics().get(0).getMDCPropertyMap().get("error.message"));
      assertEquals("new unhandled failure",
          diagnostics().get(1).getMDCPropertyMap().get("error.message"));
      assertEquals(SoapMessageDispatcher.class.getName(), diagnostics().get(1).getLoggerName());
      assertEquals(1, summaries().size());
    }
  }

  @Test void frameworkConsumedDependencyFailureRetainsActualCallMetadata() throws Exception {
    try (Fixture fixture = new Fixture(true)) {
      fixture.endpoint.captureDependency = true;
      fixture.endpoint.failure = new IOException("dependency original");
      assertEquals(500, fixture.send(new MockHttpServletResponse()).getStatus());
      var failures = events("DEPENDENCY_CALL_FAILED");
      assertEquals(1, failures.size());
      assertEquals(SoapMessageDispatcher.class.getName(), failures.get(0).getLoggerName());
      assertEquals("dependency original", failures.get(0).getMDCPropertyMap().get("error.message"));
      var details = new com.fasterxml.jackson.databind.ObjectMapper()
          .readTree(failures.get(0).getMDCPropertyMap().get("ctx_details"));
      assertEquals("gpd-core", details.get("dependency").asText());
      assertEquals("getPaymentOption", details.get("dependencyOperation").asText());
      assertTrue(details.get("durationMs").asLong() >= 0);
      assertTrue(diagnostics().isEmpty());
      assertEquals(1, summaries().size());
    }
  }

  @ParameterizedTest
  @EnumSource(value = PaaErrorEnum.class, names = {"PAA_SEMANTICA", "PAA_SYSTEM_ERROR"})
  void validationRethrowStillProducesHttp200ApplicationKo(PaaErrorEnum error) throws Exception {
    try (Fixture fixture = new Fixture(true)) {
      IOException original = new IOException("converted original");
      fixture.endpoint.failure = new PartnerValidationException(error, original);
      var response = fixture.send(new MockHttpServletResponse());
      assertEquals(200, response.getStatus());
      assertTrue(response.getContentAsString().contains("<outcome>KO</outcome>"));
      assertTrue(response.getContentAsString().contains(error.getFaultCode()));
      assertFalse(response.getContentAsString().contains(":Fault"));
      assertEquals(error == PaaErrorEnum.PAA_SYSTEM_ERROR ? 1 : 0, diagnostics().size());
      if (!diagnostics().isEmpty()) {
        assertEquals(original.getMessage(),
            diagnostics().get(0).getMDCPropertyMap().get("error.message"));
      }
      assertEquals(1, summaries().size());
      assertEquals("KO", summaries().get(0).getMDCPropertyMap().get("status"));
      assertEquals("200", summaries().get(0).getMDCPropertyMap().get("httpCode"));
    }
  }

  @Test void transportThrowAfterResolverCaptureLogsOnlyExistingCatchFailure() throws Exception {
    try (Fixture fixture = new Fixture(true)) {
      fixture.endpoint.failure = new IOException("consumed endpoint failure");
      var response = new MockHttpServletResponse() {
        @Override public ServletOutputStream getOutputStream() {
          throw new IllegalStateException("response transport failed");
        }
      };
      fixture.send(response);
      assertEquals(500, response.getStatus());
      assertEquals(1, diagnostics().size());
      assertEquals("response transport failed",
          diagnostics().get(0).getMDCPropertyMap().get("error.message"));
      assertEquals(1, summaries().size());
    }
  }

  private List<ILoggingEvent> events(String code) {
    return logs.list.stream().filter(event ->
        code.equals(event.getMDCPropertyMap().get("event.code"))).toList();
  }

  private List<ILoggingEvent> diagnostics() { return events("OPERATION_TECHNICAL_FAILED"); }
  private List<ILoggingEvent> summaries() { return events("SOAP_OPERATION_COMPLETED"); }

  private static final class Fixture implements AutoCloseable {
    private final AnnotationConfigWebApplicationContext context =
        new AnnotationConfigWebApplicationContext();
    private final MessageDispatcherServlet servlet;
    private final FailingEndpoint endpoint;

    private Fixture(boolean boundary) throws Exception {
      var servletContext = new MockServletContext();
      context.getEnvironment().setActiveProfiles(FIXTURE_PROFILE);
      context.setServletContext(servletContext);
      context.register(SoapConfiguration.class);
      context.refresh();
      assertEquals(1, context.getBeansOfType(SimpleSoapExceptionResolver.class).size(),
          "@EnableWs must install its real fallback resolver");
      endpoint = context.getBean(FailingEndpoint.class);
      servlet = boundary ? new SoapMessageDispatcher(new ObjectFactory(), "intermediario")
          : new MessageDispatcherServlet();
      servlet.setApplicationContext(context);
      servlet.init(new MockServletConfig(servletContext));
    }

    private MockHttpServletResponse send(MockHttpServletResponse response) throws Exception {
      var request = new MockHttpServletRequest("POST", "/partner");
      request.setContentType("text/xml");
      request.addHeader("SOAPAction", "\"paSendRT\"");
      request.setContent(("""
          <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
            <soapenv:Body><request xmlns="urn:logging-fixture"/></soapenv:Body>
          </soapenv:Envelope>
          """).getBytes(StandardCharsets.UTF_8));
      servlet.service(request, response);
      return response;
    }

    @Override public void close() {
      servlet.destroy();
      context.close();
    }
  }

  @TestConfiguration
  @Profile(FIXTURE_PROFILE)
  @EnableWs
  public static class SoapConfiguration extends WsConfigurerAdapter {
    @Bean public FailingEndpoint failingEndpoint() { return new FailingEndpoint(); }
    @Bean public SoapFaultDefinitionExceptionResolver boundaryResolver() {
      return new SoapFaultDefinitionExceptionResolver();
    }
    @Override public void addInterceptors(List<EndpointInterceptor> interceptors) {
      var interceptor = new SoapValidatingInterceptor();
      interceptor.setValidateRequest(false);
      interceptor.setValidateResponse(false);
      interceptors.add(interceptor);
    }
  }

  @Endpoint
  @Profile(FIXTURE_PROFILE)
  public static class FailingEndpoint {
    private Exception failure;
    private boolean absorbEarlierFailure;
    private boolean captureDependency;

    @PayloadRoot(namespace = "urn:logging-fixture", localPart = "request")
    @ResponsePayload
    public Source invoke() throws Exception {
      if (absorbEarlierFailure) {
        FailureDiagnostics.absorbedFailure(new LogEventWriter(LOGGER),
            new IOException("earlier absorbed failure"));
        assertTrue(OperationLogScope.current().hasDiagnosis());
      }
      if (failure != null) {
        if (captureDependency) {
          FailureDiagnostics.dependencyFailure(EventDetails.Dependency.GPD_CORE,
              EventDetails.DependencyOperation.GET_PAYMENT_OPTION, System.nanoTime(), failure);
        }
        throw failure;
      }
      return new StringSource("<response><outcome>OK</outcome></response>");
    }
  }
}
