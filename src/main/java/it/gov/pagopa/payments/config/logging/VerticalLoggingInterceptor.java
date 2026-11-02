package it.gov.pagopa.payments.config.logging;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/** Observes vertical HTTP exchanges without reading bodies or changing response error handling. */
public final class VerticalLoggingInterceptor implements ClientHttpRequestInterceptor {
  private static final Logger LOG = LoggerFactory.getLogger(VerticalLoggingInterceptor.class);
  private final Level expectedLogLevel;

  public VerticalLoggingInterceptor() {
    this(Level.INFO);
  }

  @Autowired
  public VerticalLoggingInterceptor(
      @Value("${logging.http.expected-level:INFO}") Level expectedLogLevel) {
    this.expectedLogLevel = Objects.requireNonNull(expectedLogLevel, "expectedLogLevel");
  }

  @Override
  public ClientHttpResponse intercept(
      HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
    ObjectNode fields = LogContext.event()
        .put(LogContext.DEPENDENCY, LogDependencies.VERTICAL_SERVICE);
    ObjectNode dependency = fields.putObject(LogContext.CTX_DETAILS)
        .putObject(LogContext.DEPENDENCY)
        .put(LogContext.NAME, LogDependencies.VERTICAL_SERVICE + "." + request.getMethodValue());
    long started = System.nanoTime();
    ClientHttpResponse response;
    int code;
    try {
      response = execution.execute(request, body);
      code = response.getRawStatusCode();
    } catch (IOException | RuntimeException failure) {
      fields.put(LogContext.STATUS, LogContext.STATUS_KO)
          .put(LogContext.ERROR_TYPE, failure.getClass().getName());
      completed(fields, started, Level.WARN);
      throw failure;
    }
    dependency.put(LogContext.HTTP_CODE, code);
    fields.put(LogContext.STATUS, code >= 400 ? LogContext.STATUS_KO : LogContext.STATUS_OK);
    Level level = code >= 500 ? Level.ERROR
        : code >= 200 && code < 300 ? expectedLogLevel : Level.WARN;
    completed(fields, started, level);
    return response;
  }

  private static void completed(ObjectNode fields, long started, Level level) {
    fields.put(LogContext.RESPONSE_TIME,
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    LogContext.log(LOG, level, LogMessages.VERTICAL_HTTP_ATTEMPT_COMPLETED, fields);
  }
}
