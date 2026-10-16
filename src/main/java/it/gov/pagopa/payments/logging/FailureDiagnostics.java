package it.gov.pagopa.payments.logging;

import com.azure.core.exception.HttpResponseException;
import feign.FeignException;
import it.gov.pagopa.payments.endpoints.validation.exceptions.PartnerValidationException;
import it.gov.pagopa.payments.exception.AppException;
import it.gov.pagopa.payments.exception.DeadLetterAccessException;
import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Carries dependency facts to the final boundary; absorbed failures are emitted at their catch.
 * Unscoped propagation is the caller's responsibility: capture never logs or invents a scope.
 */
public final class FailureDiagnostics {
  private FailureDiagnostics() {}

  public static void dependencyFailure(
      EventDetails.Dependency dependency,
      EventDetails.DependencyOperation operation,
      long startedNanos,
      Throwable failure) {
    dependencyFailure(dependency, operation, startedNanos, failure, null, null);
  }

  public static void dependencyFailure(
      EventDetails.Dependency dependency,
      EventDetails.DependencyOperation operation,
      long startedNanos,
      Throwable failure,
      EventDetails.Reason reason,
      String messageId) {
    OperationLogScope scope = OperationLogScope.currentOrNull();
    if (scope == null) {
      return;
    }
    long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    EventDetails.DependencyCall details = new EventDetails.DependencyCall(
            dependency, operation, EventDetails.Outcome.KO, downstreamStatus(failure),
            durationMs, null, reason, messageId);
    scope.rememberDependency(new OperationLogScope.DependencyFailure(failure, details));
  }

  /** Call only at a final boundary, not at intermediate propagation/conversion catches. */
  public static void technicalFailure(LogEventWriter writer, Throwable failure) {
    OperationLogScope scope = OperationLogScope.currentOrNull();
    if (scope != null && scope.isHandled(failure)) {
      return;
    }
    OperationLogScope.DependencyFailure context =
        scope == null ? null : scope.dependencyFailure(failure);
    if (context == null) {
      writer.technicalFailure(originalFailure(failure));
    } else {
      writer.dependencyFailed(context.details(),
          LogEventWriter.FailureDisposition.TERMINAL, context.failure());
    }
    if (scope != null) {
      scope.recordDiagnosis();
    }
  }

  public static void absorbedFailure(LogEventWriter writer, Throwable failure) {
    technicalFailure(writer, failure);
    handled(failure);
  }

  /** Call only after the recovery action and its retry event have succeeded. */
  public static void recovered(Throwable failure) {
    handled(failure);
  }

  private static void handled(Throwable failure) {
    OperationLogScope scope = OperationLogScope.currentOrNull();
    if (scope != null) {
      scope.markHandled(failure);
    }
  }

  private static Throwable originalFailure(Throwable failure) {
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    while (seen.add(failure) && failure.getCause() != null
        && (failure instanceof PartnerValidationException
            || failure instanceof AppException || failure instanceof DeadLetterAccessException
            || failure instanceof ResponseStatusException
            || (failure instanceof IllegalStateException && failure.getCause() instanceof IOException))) {
      failure = failure.getCause();
    }
    return failure;
  }

  private static Integer downstreamStatus(Throwable failure) {
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable current = failure; current != null && seen.add(current);
        current = current.getCause()) {
      if (current instanceof FeignException feign && feign.status() > 0) {
        return feign.status();
      }
      if (current instanceof HttpResponseException azure && azure.getResponse() != null) {
        return azure.getResponse().getStatusCode();
      }
      if (current instanceof RestClientResponseException http) {
        return http.getRawStatusCode();
      }
    }
    return null;
  }
}
