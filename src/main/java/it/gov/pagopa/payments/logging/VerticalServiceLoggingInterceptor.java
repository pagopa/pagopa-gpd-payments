package it.gov.pagopa.payments.logging;

import java.io.IOException;
import java.io.InputStream;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/** Install only on the RestTemplate used for paDemandPaymentNotice. */
public final class VerticalServiceLoggingInterceptor implements ClientHttpRequestInterceptor {
  @Override
  public ClientHttpResponse intercept(
      HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
    DependencyLogCall call =
        new DependencyLogCall(
            "vertical-service", "paDemandPaymentNotice", "paDemandPaymentNotice");
    try {
      ClientHttpResponse response = execution.execute(request, body);
      recordResponseStatus(response, call);
      return new ClientHttpResponse() {
        @Override
        public HttpStatus getStatusCode() throws IOException {
          return response.getStatusCode();
        }

        @Override
        public int getRawStatusCode() throws IOException {
          return response.getRawStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
          return response.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
          return response.getHeaders();
        }

        @Override
        public InputStream getBody() throws IOException {
          try {
            return call.observe(response.getBody());
          } catch (IOException | RuntimeException error) {
            call.complete(true);
            throw error;
          }
        }

        @Override
        public void close() {
          try {
            response.close();
            call.complete(false);
          } catch (RuntimeException error) {
            call.complete(true);
            throw error;
          }
        }
      };
    } catch (IOException | RuntimeException error) {
      call.complete(true);
      throw error;
    }
  }

  private static void recordResponseStatus(ClientHttpResponse response, DependencyLogCall call)
      throws IOException {
    try {
      call.response(response.getRawStatusCode());
    } catch (IOException | RuntimeException error) {
      closeFailedResponse(response, error);
      throw error;
    }
  }

  private static void closeFailedResponse(ClientHttpResponse response, Exception error) {
    try {
      response.close();
    } catch (RuntimeException closeError) {
      error.addSuppressed(closeError);
    }
  }
}
