package it.gov.pagopa.payments.config;

import static it.gov.pagopa.payments.config.LogContext.CORRELATION_ID;
import static it.gov.pagopa.payments.config.LogContext.CTX_CREDITOR_ID;
import static it.gov.pagopa.payments.config.LogContext.CTX_DETAILS;
import static it.gov.pagopa.payments.config.LogContext.CTX_IUV;
import static it.gov.pagopa.payments.config.LogContext.CTX_NOTICE_NUMBER;
import static it.gov.pagopa.payments.config.LogContext.DEPENDENCY;
import static it.gov.pagopa.payments.config.LogContext.ERROR_TYPE;
import static it.gov.pagopa.payments.config.LogContext.HTTP_CODE;
import static it.gov.pagopa.payments.config.LogContext.NAME;
import static it.gov.pagopa.payments.config.LogContext.RESPONSE_TIME;
import static it.gov.pagopa.payments.config.LogContext.STATUS;
import static it.gov.pagopa.payments.config.LogContext.STATUS_KO;
import static it.gov.pagopa.payments.config.LogContext.STATUS_OK;

import com.azure.core.http.HttpPipelineCallContext;
import com.azure.core.http.HttpPipelineNextPolicy;
import com.azure.core.http.HttpPipelineNextSyncPolicy;
import com.azure.core.http.HttpPipelinePosition;
import com.azure.core.http.HttpResponse;
import com.azure.core.http.policy.HttpLoggingPolicy;
import com.azure.core.http.policy.HttpPipelinePolicy;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import reactor.core.publisher.Mono;

/** Observes HTTP attempts without consuming bodies or changing SDK retry decisions. */
public final class AzureLoggingPolicy implements HttpPipelinePolicy {
  public static final String TABLES = "azure-tables";
  public static final String QUEUE = "azure-queue";
  public static final String BLOB = "azure-blob";
  private static final String CAPTURED_FIELDS = AzureLoggingPolicy.class.getName();
  private static final String HOST = "host";
  private static final String ATTEMPT = "attempt";
  private static final String COMPLETED = "Completed Azure HTTP attempt";
  private static final Logger LOG = LoggerFactory.getLogger(AzureLoggingPolicy.class);
  private final String dependency;
  private final HttpPipelinePosition position;

  public AzureLoggingPolicy(String dependency) {
    this(dependency, HttpPipelinePosition.PER_RETRY);
  }

  private AzureLoggingPolicy(String dependency, HttpPipelinePosition position) {
    this.dependency = dependency;
    this.position = position;
  }

  // Capture before retries or request buffering can move execution onto an SDK worker.
  public static AzureLoggingPolicy correlationPolicy() {
    return new AzureLoggingPolicy(null, HttpPipelinePosition.PER_CALL);
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
          .doOnNext(response -> completed(fields, started, response, null))
          .doOnError(failure -> completed(fields, started, null, failure));
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
      completed(fields, started, null, failure);
      throw failure;
    }
    completed(fields, started, response, null);
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
    ObjectNode details = fields.putObject(CTX_DETAILS)
        .put(HOST, context.getHttpRequest().getUrl().getHost());
    details.putObject(DEPENDENCY)
        .put(NAME, dependency + "." + context.getHttpRequest().getHttpMethod().name());
    context.getData(HttpLoggingPolicy.RETRY_COUNT_CONTEXT)
        .filter(Number.class::isInstance).map(Number.class::cast)
        .ifPresent(attempt -> details.put(ATTEMPT, attempt.intValue()));
    return fields;
  }

  private static void completed(
      ObjectNode fields, long started, HttpResponse response, Throwable failure) {
    fields.put(RESPONSE_TIME, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    int code = response == null ? 0 : response.getStatusCode();
    if (response != null) {
      fields.with(CTX_DETAILS).with(DEPENDENCY).put(HTTP_CODE, code);
    }
    fields.put(STATUS, failure != null || code >= 400 ? STATUS_KO : STATUS_OK);
    if (failure != null) {
      fields.put(ERROR_TYPE, failure.getClass().getName());
    }
    Level level = failure != null || code == 429 || code >= 500 ? Level.WARN : Level.INFO;
    Map<String, String> previous = MDC.getCopyOfContextMap();
    try {
      MDC.clear();
      LogContext.log(LOG, level, COMPLETED, fields);
    } finally {
      LogContext.restore(previous);
    }
  }
}
