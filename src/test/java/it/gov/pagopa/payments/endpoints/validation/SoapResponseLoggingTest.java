package it.gov.pagopa.payments.endpoints.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import it.gov.pagopa.payments.config.RequestFilter;
import it.gov.pagopa.payments.config.RequestLogCapture;
import it.gov.pagopa.payments.endpoints.validation.exceptions.PartnerValidationException;
import it.gov.pagopa.payments.model.PaaErrorEnum;
import it.gov.pagopa.payments.model.partner.ObjectFactory;
import java.io.IOException;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SoapResponseLoggingTest {

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void applicationFaultMetadataRequiresSuccessfulResponseWriting(boolean writingFails)
      throws Exception {
    RequestFilter filter = new RequestFilter();
    SoapMessageDispatcher dispatcher = new SoapMessageDispatcher(new ObjectFactory(), "test") {
      @Override
      protected void callService(HttpServletRequest request, HttpServletResponse response) {
        throw new PartnerValidationException(PaaErrorEnum.PAA_SEMANTICA);
      }
    };
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("SOAPAction", "paGetPayment");
    MockHttpServletResponse response = writingFails ? failingResponse() : new MockHttpServletResponse();
    try (RequestLogCapture logs = new RequestLogCapture()) {
      filter.doFilter(request, response, (req, res) -> dispatcher.doService(request, response));
      filter.logCompletedRequest(request);
      JsonNode record = logs.singleSummary();

      assertEquals("KO", record.path("status").asText());
      assertEquals(writingFails ? 500 : 200, response.getStatus());
      if (writingFails) {
        assertFalse(record.has("ctx_soap_outcome"));
        assertFalse(record.has("ctx_soap_fault_code"));
      } else {
        assertEquals("KO", record.path("ctx_soap_outcome").asText());
        assertEquals("PAA_SEMANTICA", record.path("ctx_soap_fault_code").asText());
      }
    }
  }

  private MockHttpServletResponse failingResponse() {
    return new MockHttpServletResponse() {
      @Override
      public ServletOutputStream getOutputStream() {
        return new ServletOutputStream() {
          @Override
          public void write(int value) throws IOException {
            throw new IOException("test transport failure");
          }

          @Override
          public boolean isReady() {
            return true;
          }

          @Override
          public void setWriteListener(WriteListener listener) {}
        };
      }
    };
  }
}
