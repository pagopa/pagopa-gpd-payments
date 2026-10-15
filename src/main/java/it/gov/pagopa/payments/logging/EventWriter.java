package it.gov.pagopa.payments.logging;

import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.MDC;
import org.slf4j.event.Level;

/**
 * Event-local writer; not a Spring bean and not installed on any production path.
 * The supplied logger retains the emitting class's identity and level configuration.
 */
public final class EventWriter {
  public enum FailureDisposition {
    RECOVERABLE, TERMINAL
  }

  private final Logger logger;

  public EventWriter(Logger logger) {
    this.logger = Objects.requireNonNull(logger, "logger");
  }

  public void emit(LogEvent event) {
    emit(event, null);
  }

  /** Emits a catalog event without a throwable, rejecting unapproved detail types. */
  public void emit(LogEvent event, EventDetails details) {
    Objects.requireNonNull(event, "event");
    if (event == LogEvent.DEPENDENCY_CALL_FAILED || event == LogEvent.OPERATION_TECHNICAL_FAILED) {
      throw new IllegalArgumentException("Use the dedicated technical failure methods");
    }
    if (event == LogEvent.DEPENDENCY_CALL_COMPLETED
        && details instanceof EventDetails.DependencyCall call
        && call.dependencyOutcome() != EventDetails.Outcome.OK) {
      throw new IllegalArgumentException("Completed dependency diagnostics require outcome OK");
    }
    write(event, details, event.defaultLevel(), null);
  }

  /** Selects WARN for a recoverable attempt or ERROR for a terminal dependency failure. */
  public void dependencyFailed(
      EventDetails.DependencyCall details, FailureDisposition disposition, Throwable failure) {
    Objects.requireNonNull(details, "details");
    Objects.requireNonNull(disposition, "disposition");
    Objects.requireNonNull(failure, "failure");
    if (details.dependencyOutcome() != EventDetails.Outcome.KO) {
      throw new IllegalArgumentException("Failed dependency diagnostics require outcome KO");
    }
    write(
        LogEvent.DEPENDENCY_CALL_FAILED,
        details,
        disposition == FailureDisposition.RECOVERABLE ? Level.WARN : Level.ERROR,
        failure);
  }

  /** Reports original messages with bounded stack selection, without mutating the exception. */
  public void technicalFailure(Throwable failure) {
    write(
        LogEvent.OPERATION_TECHNICAL_FAILED,
        null,
        LogEvent.OPERATION_TECHNICAL_FAILED.defaultLevel(),
        Objects.requireNonNull(failure, "failure"));
  }

  private void write(LogEvent event, EventDetails details, Level level, Throwable failure) {
    if (!event.accepts(details)) {
      throw new IllegalArgumentException("Detail type is not approved for " + event.code());
    }
    if (!enabled(level)) {
      return;
    }
    // Event fields must neither inherit a previous diagnostic nor survive this emission.
    Map<String, String> previous = MDC.getCopyOfContextMap();
    try {
      MDC.put("event.code", event.code());
      MDC.remove("ctx_details");
      MDC.remove("error.type");
      MDC.remove("error.message");
      MDC.remove("error.stack_trace");
      MDC.remove("soapTechnicalErrorLogged");
      String json = EventDetailsJson.serialize(details);
      if (json != null) {
        MDC.put("ctx_details", json);
      }
      if (failure != null) {
        SafeDiagnostic diagnostic = SafeDiagnostic.from(failure);
        MDC.put("error.type", diagnostic.type());
        if (diagnostic.message() != null) {
          MDC.put("error.message", diagnostic.message());
        }
        MDC.put("error.stack_trace", diagnostic.stackTrace());
      }
      // Deliberately use message-only overloads: ECS must never see the original throwable.
      switch (level) {
        case DEBUG -> logger.debug(event.message());
        case INFO -> logger.info(event.message());
        case WARN -> logger.warn(event.message());
        case ERROR -> logger.error(event.message());
        default -> throw new IllegalArgumentException("Unsupported catalog level");
      }
    } finally {
      if (previous == null) {
        MDC.clear();
      } else {
        MDC.setContextMap(previous);
      }
    }
  }

  private boolean enabled(Level level) {
    return switch (level) {
      case DEBUG -> logger.isDebugEnabled();
      case INFO -> logger.isInfoEnabled();
      case WARN -> logger.isWarnEnabled();
      case ERROR -> logger.isErrorEnabled();
      default -> false;
    };
  }
}
