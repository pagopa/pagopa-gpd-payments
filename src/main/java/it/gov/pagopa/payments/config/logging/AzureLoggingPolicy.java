package it.gov.pagopa.payments.config.logging;

import static it.gov.pagopa.payments.config.logging.LogContext.CORRELATION_ID;
import static it.gov.pagopa.payments.config.logging.LogContext.CTX_CREDITOR_ID;
import static it.gov.pagopa.payments.config.logging.LogContext.CTX_DETAILS;
import static it.gov.pagopa.payments.config.logging.LogContext.CTX_IUV;
import static it.gov.pagopa.payments.config.logging.LogContext.CTX_NOTICE_NUMBER;
import static it.gov.pagopa.payments.config.logging.LogContext.DEPENDENCY;
import static it.gov.pagopa.payments.config.logging.LogContext.ERROR_TYPE;
import static it.gov.pagopa.payments.config.logging.LogContext.HTTP_CODE;
import static it.gov.pagopa.payments.config.logging.LogContext.NAME;
import static it.gov.pagopa.payments.config.logging.LogContext.RESPONSE_TIME;
import static it.gov.pagopa.payments.config.logging.LogContext.STATUS;
import static it.gov.pagopa.payments.config.logging.LogContext.STATUS_KO;
import static it.gov.pagopa.payments.config.logging.LogContext.STATUS_OK;

import com.azure.core.http.HttpPipelineCallContext;
import com.azure.core.http.HttpPipelineNextPolicy;
import com.azure.core.http.HttpPipelineNextSyncPolicy;
import com.azure.core.http.HttpPipelinePosition;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.azure.core.http.policy.HttpPipelinePolicy;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Observes HTTP attempts without consuming bodies or changing SDK retry decisions. */
public final class AzureLoggingPolicy implements HttpPipelinePolicy {
  public static final String TABLES = LogDependencies.AZURE_TABLES;
  public static final String QUEUE = LogDependencies.AZURE_QUEUE;
  public static final String BLOB = LogDependencies.AZURE_BLOB;
  private static final String CAPTURED_FIELDS = AzureLoggingPolicy.class.getName();
  private static final String HOST = "host";
  private static final Logger LOG = LoggerFactory.getLogger(AzureLoggingPolicy.class);
  private final String dependency;
  private final HttpPipelinePosition position;
  private final Level expectedLogLevel;

  @Component
  public static final class Factory {
    private final Level expectedLogLevel;

    public Factory(@Value("${logging.http.expected-level:INFO}") Level expectedLogLevel) {
      this.expectedLogLevel = Objects.requireNonNull(expectedLogLevel, "expectedLogLevel");
    }

    public AzureLoggingPolicy create(String dependency) {
      return new AzureLoggingPolicy(dependency, expectedLogLevel);
    }
  }

  public AzureLoggingPolicy(String dependency) {
    this(dependency, Level.INFO);
  }

  public AzureLoggingPolicy(String dependency, Level expectedLogLevel) {
    this(dependency, HttpPipelinePosition.PER_RETRY, expectedLogLevel);
  }

  private AzureLoggingPolicy(
      String dependency, HttpPipelinePosition position, Level expectedLogLevel) {
    this.dependency = dependency;
    this.position = position;
    this.expectedLogLevel = Objects.requireNonNull(expectedLogLevel, "expectedLogLevel");
  }

  // Capture before retries or request buffering can move execution onto an SDK worker.
  public static AzureLoggingPolicy correlationPolicy() {
    return new AzureLoggingPolicy(null, HttpPipelinePosition.PER_CALL, Level.INFO);
  }

  @Override
  public HttpPipelinePosition getPipelinePosition() {
    return position;
  }

  @Override
  public Mono<HttpResponse> process(HttpPipelineCallContext context, HttpPipelineNextPolicy next) {
    if (position == HttpPipelinePosition.PER_CALL) {
      capture(context);
      return next.process();
    }
    return Mono.defer(() -> {
      ObjectNode fields = fields(context);
      long started = System.nanoTime();
      return Mono.defer(() -> next.clone().process())
          .doOnNext(response -> completed(context, fields, started, response, null))
          .doOnError(failure -> completed(context, fields, started, null, failure));
    });
  }

  @Override
  public HttpResponse processSync(
      HttpPipelineCallContext context, HttpPipelineNextSyncPolicy next) {
    if (position == HttpPipelinePosition.PER_CALL) {
      capture(context);
      return next.processSync();
    }
    ObjectNode fields = fields(context);
    long started = System.nanoTime();
    HttpResponse response;
    try {
      response = next.processSync();
    } catch (RuntimeException failure) {
      completed(context, fields, started, null, failure);
      throw failure;
    }
    completed(context, fields, started, response, null);
    return response;
  }

  private static void capture(HttpPipelineCallContext context) {
    ObjectNode fields = LogContext.event();
    for (String key : new String[] {
        CORRELATION_ID, CTX_CREDITOR_ID, CTX_NOTICE_NUMBER, CTX_IUV}) {
      String value = MDC.get(key);
      if (value != null) {
        fields.put(key, value);
      }
    }
    context.setData(CAPTURED_FIELDS, fields);
  }

  private ObjectNode fields(HttpPipelineCallContext context) {
    ObjectNode fields = LogContext.event();
    context.getData(CAPTURED_FIELDS).filter(ObjectNode.class::isInstance)
        .map(ObjectNode.class::cast).ifPresent(fields::setAll);
    fields.put(DEPENDENCY, dependency);
    ObjectNode details = fields.putObject(CTX_DETAILS)
        .put(HOST, context.getHttpRequest().getUrl().getHost());
    details.putObject(DEPENDENCY)
        .put(NAME, dependency + "." + context.getHttpRequest().getHttpMethod().name());
    return fields;
  }

  private int expectedStatus(HttpRequest request) {
    return switch (dependency) {
      case TABLES -> switch (request.getHttpMethod()) {
        case GET -> 200;
        case POST -> "return-no-content".equalsIgnoreCase(request.getHeaders().getValue("Prefer"))
            ? 204 : 201;
        case PUT, PATCH -> 204;
        default -> -1;
      };
      case QUEUE -> switch (request.getHttpMethod()) {
        case GET -> 200;
        case POST -> 201;
        case PUT, DELETE -> 204;
        default -> -1;
      };
      case BLOB -> switch (request.getHttpMethod()) {
        case GET -> request.getHeaders().getValue("Range") != null
            || request.getHeaders().getValue("x-ms-range") != null ? 206 : 200;
        case HEAD -> 200;
        case PUT -> 201;
        default -> -1;
      };
      default -> -1;
    };
  }

  private void completed(
      HttpPipelineCallContext context, ObjectNode fields, long started,
      HttpResponse response, Throwable failure) {
    fields.put(RESPONSE_TIME, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    int code = response == null ? 0 : response.getStatusCode();
    if (response != null) {
      fields.with(CTX_DETAILS).with(DEPENDENCY).put(HTTP_CODE, code);
    }
    fields.put(STATUS, failure != null || code >= 400 ? STATUS_KO : STATUS_OK);
    if (failure != null) {
      fields.put(ERROR_TYPE, failure.getClass().getName());
    }
    Level level;
    if (failure != null) {
      level = Level.WARN;
    } else if (code >= 500) {
      level = Level.ERROR;
    } else if (code == expectedStatus(context.getHttpRequest())) {
      level = expectedLogLevel;
    } else {
      level = Level.WARN;
    }
    Map<String, String> previous = MDC.getCopyOfContextMap();
    try {
      MDC.clear();
      LogContext.log(LOG, level, LogMessages.AZURE_HTTP_ATTEMPT_COMPLETED, fields);
    } finally {
      LogContext.restore(previous);
    }
  }
}
