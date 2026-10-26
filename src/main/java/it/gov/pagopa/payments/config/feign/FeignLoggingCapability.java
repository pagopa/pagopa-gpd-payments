package it.gov.pagopa.payments.config.feign;

import com.fasterxml.jackson.databind.node.ObjectNode;
import feign.Capability;
import feign.Client;
import feign.RequestTemplate;
import feign.Response;
import it.gov.pagopa.payments.config.LogContext;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

/** Decorates the configured transport without reading or replacing response bodies. */
public final class FeignLoggingCapability implements Capability {
  private static final Logger LOG = LoggerFactory.getLogger(FeignLoggingCapability.class);
  private static final String COMPLETED = "Completed Feign HTTP attempt";
  private static final String BODY_PRESENT = "body_present";
  private static final String SEND_PAYMENT_OPTION_RECEIPT = "sendPaymentOptionReceipt";

  @Override
  public Client enrich(Client client) {
    return (request, options) -> {
      ObjectNode fields = LogContext.event();
      RequestTemplate template = request.requestTemplate();
      String operation = template.methodMetadata().method().getName();
      ObjectNode dependency = fields.putObject(LogContext.CTX_DETAILS)
          .put(LogContext.PATH, template.methodMetadata().template().path())
          .putObject(LogContext.DEPENDENCY)
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
      dependency.put(LogContext.HTTP_CODE, code).put(BODY_PRESENT, response.body() != null);
      fields.put(LogContext.STATUS, code >= 400 ? LogContext.STATUS_KO : LogContext.STATUS_OK);
      boolean expected = code == 404
          || (code == 409 && SEND_PAYMENT_OPTION_RECEIPT.equals(operation));
      completed(fields, started, code >= 400 && !expected ? Level.WARN : Level.INFO);
      return response;
    };
  }

  private static void completed(ObjectNode fields, long started, Level level) {
    fields.put(LogContext.RESPONSE_TIME,
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    LogContext.log(LOG, level, COMPLETED, fields);
  }
}
