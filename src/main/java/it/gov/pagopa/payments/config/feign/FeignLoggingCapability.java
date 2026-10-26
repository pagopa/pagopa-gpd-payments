package it.gov.pagopa.payments.config.feign;

import com.fasterxml.jackson.databind.node.ObjectNode;
import feign.Capability;
import feign.Client;
import feign.RequestTemplate;
import feign.Response;
import it.gov.pagopa.payments.client.GpdClient;
import it.gov.pagopa.payments.config.LogContext;
import it.gov.pagopa.payments.config.LogMessages;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

/** Decorates the configured transport without reading or replacing response bodies. */
public final class FeignLoggingCapability implements Capability {
  private static final Logger LOG = LoggerFactory.getLogger(FeignLoggingCapability.class);
  private static final String EMPTY_GPD_BODY = "GPD returned HTTP 200 with an empty response body";
  private static final String SEND_PAYMENT_OPTION_RECEIPT = "sendPaymentOptionReceipt";
  private static final String GET_PAYMENT_OPTION = "getPaymentOption";

  @Override
  public Client enrich(Client client) {
    return (request, options) -> {
      ObjectNode fields = LogContext.event();
      RequestTemplate template = request.requestTemplate();
      String operation = template.methodMetadata().method().getName();
      ObjectNode dependency = fields.putObject(LogContext.CTX_DETAILS)
          .putObject(LogContext.DEPENDENCY)
          .put(LogContext.PATH, template.methodMetadata().template().path())
          .put(LogContext.NAME, template.feignTarget().name() + "." + operation);
      long started = System.nanoTime();
      Response response;
      try {
        response = client.execute(request, options);
      } catch (IOException | RuntimeException failure) {
        fields.put(LogContext.STATUS, LogContext.STATUS_KO)
            .put(LogContext.ERROR_TYPE, failure.getClass().getName());
        if (failure.getMessage() != null) {
          fields.put(LogContext.ERROR_MESSAGE, failure.getMessage());
        }
        completed(fields, started, Level.WARN);
        throw failure;
      }
      int code = response.status();
      dependency.put(LogContext.HTTP_CODE, code);
      fields.put(LogContext.STATUS, code >= 400 ? LogContext.STATUS_KO : LogContext.STATUS_OK);
      boolean gpd = GpdClient.class.equals(template.feignTarget().type());
      Level level = responseLevel(template, operation, code);
      if (gpd && code == 200
          && (response.body() == null || Integer.valueOf(0).equals(response.body().length()))) {
        fields.put(LogContext.STATUS, LogContext.STATUS_KO)
            .put(LogContext.ERROR_MESSAGE, EMPTY_GPD_BODY);
        level = Level.WARN;
      }
      completed(fields, started, level);
      return response;
    };
  }

  private static Level responseLevel(RequestTemplate template, String operation, int code) {
    if (code >= 500) {
      return Level.ERROR;
    }
    if (GpdClient.class.equals(template.feignTarget().type())) {
      if (code == 409 && SEND_PAYMENT_OPTION_RECEIPT.equals(operation)) {
        return Level.INFO;
      }
      if (GET_PAYMENT_OPTION.equals(operation) || SEND_PAYMENT_OPTION_RECEIPT.equals(operation)) {
        return code == 200 ? Level.DEBUG : Level.WARN;
      }
    }
    return code >= 200 && code < 300 ? Level.INFO : Level.WARN;
  }

  private static void completed(ObjectNode fields, long started, Level level) {
    fields.put(LogContext.RESPONSE_TIME,
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    LogContext.log(LOG, level, LogMessages.FEIGN_HTTP_ATTEMPT_COMPLETED, fields);
  }
}
