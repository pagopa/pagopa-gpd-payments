package it.gov.pagopa.payments.logging;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import feign.FeignException;
import it.gov.pagopa.payments.endpoints.validation.exceptions.PartnerValidationException;
import it.gov.pagopa.payments.model.PaaErrorEnum;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.MDC;

/** Retains the original message and a short, message-free trace of the outer and root exceptions. */
public final class TechnicalFailureLogging {
  private static final int MAX_STACK_FRAMES = 3;

  private TechnicalFailureLogging() {}

  public static void logTechnicalFailure(Logger logger, String message, Throwable failure) {
    String previous = MDC.get(HttpRequestLogContext.STATUS);
    try {
      MDC.put(HttpRequestLogContext.STATUS, "KO");
      var fields = JsonNodeFactory.instance.objectNode()
          .put("error.type", failure.getClass().getName())
          .put("error.stack_trace", abbreviatedStackTrace(failure));
      String exceptionMessage = failure.getMessage();
      if (exceptionMessage != null) {
        fields.put("error.message", exceptionMessage);
      }
      logger.error(message, new StructuredLogFields(fields));
    } finally {
      if (previous == null) {
        MDC.remove(HttpRequestLogContext.STATUS);
      } else {
        MDC.put(HttpRequestLogContext.STATUS, previous);
      }
    }
  }

  public static void logPartnerFailureIfTechnical(
      Logger logger, String message, PartnerValidationException failure) {
    Throwable cause = failure.getCause();
    boolean expectedHttpRejection = cause instanceof FeignException response
        && response.status() >= 400 && response.status() < 500;
    if (failure.getError() == PaaErrorEnum.PAA_SYSTEM_ERROR
        || (failure.getError() == PaaErrorEnum.PAA_SEMANTICA
            && cause != null && !expectedHttpRejection)) {
      logTechnicalFailure(logger, message, failure);
    }
  }

  private static String abbreviatedStackTrace(Throwable failure) {
    StringBuilder trace = new StringBuilder();
    appendFrames(trace, failure);
    Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    Throwable root = failure;
    Throwable cause;
    while ((cause = root.getCause()) != null) {
      if (!visited.add(root)) {
        // A cyclic chain has no root cause to report.
        return trace.append("\nCyclic cause chain omitted").toString();
      }
      root = cause;
    }
    if (root != failure) {
      trace.append("\nCaused by: ");
      appendFrames(trace, root);
    }
    return trace.toString();
  }

  private static void appendFrames(StringBuilder trace, Throwable failure) {
    trace.append(failure.getClass().getName());
    StackTraceElement[] frames = failure.getStackTrace();
    for (int index = 0; index < Math.min(frames.length, MAX_STACK_FRAMES); index++) {
      trace.append("\n\tat ").append(frames[index]);
    }
    if (frames.length > MAX_STACK_FRAMES) {
      trace.append("\n\t... ").append(frames.length - MAX_STACK_FRAMES).append(" frames omitted");
    }
  }
}
