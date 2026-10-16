package it.gov.pagopa.payments.logging;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * Thread-confined, LIFO operation boundary. Installs typed context, then restores the exact
 * previous MDC on close; unrelated legacy fields are never inherited by a new operation.
 */
public final class OperationLogScope implements AutoCloseable {
  private static final ThreadLocal<OperationLogScope> CURRENT = new ThreadLocal<>();

  private final Thread thread = Thread.currentThread();
  private final Map<String, String> previousMdc;
  private final OperationLogScope previousScope;
  private final DiagnosticOwnership ownership;
  private final boolean ownsDiagnostics;
  private boolean closed;
  private String summaryArgs;

  private OperationLogScope(
      ContextSnapshot context, String method, DiagnosticOwnership sharedOwnership) {
    previousMdc = MDC.getCopyOfContextMap();
    previousScope = CURRENT.get();
    ownsDiagnostics = sharedOwnership == null;
    ownership = ownsDiagnostics ? new DiagnosticOwnership() : sharedOwnership;
    Map<String, String> fields = context.fields();
    if (!ownsDiagnostics && previousMdc != null) {
      // Payment enrichment belongs to the same execution, including its timing and outcome.
      Map<String, String> enriched = new HashMap<>(previousMdc);
      enriched.remove("noticeNumber");
      enriched.remove("creditorFiscalCode");
      enriched.putAll(fields);
      fields = enriched;
    }
    if (method != null) {
      fields.put("method", method);
    }
    MDC.setContextMap(fields);
    CURRENT.set(this);
  }

  public static OperationLogScope open(String requestId) {
    return open(requestId, null);
  }

  /** Returns the active operation for context/failure facts; never creates an implicit scope. */
  public static OperationLogScope current() {
    OperationLogScope scope = CURRENT.get();
    if (scope == null) {
      throw new IllegalStateException("No active operation scope");
    }
    return scope;
  }

  public static OperationLogScope currentOrNull() {
    return CURRENT.get();
  }

  /** Enriches this execution through completion, rather than opening a shorter payment scope. */
  public void setPaymentLogContext(String noticeNumber, String creditorFiscalCode) {
    requireActiveOperation();
    putOptional("noticeNumber", noticeNumber);
    putOptional("creditorFiscalCode", creditorFiscalCode);
  }

  public void summaryArgs(String args) {
    requireActiveOperation();
    summaryArgs = args;
  }

  public String summaryArgs() {
    requireActiveOperation();
    return summaryArgs;
  }

  /** Used by message counts and returned-fault fallback, not throwable deduplication. */
  public boolean hasDiagnosis() {
    requireActiveOperation();
    return ownership.diagnosed;
  }

  /** Retains the original failure when a framework resolver may consume it without rethrowing. */
  public void rememberBoundaryFailure(Throwable failure) {
    requireActiveOperation();
    ownership.pendingBoundaryFailure = java.util.Objects.requireNonNull(failure);
  }

  public Throwable takeBoundaryFailure() {
    requireActiveOperation();
    Throwable failure = ownership.pendingBoundaryFailure;
    ownership.pendingBoundaryFailure = null;
    return failure;
  }

  private static void putOptional(String key, String value) {
    if (value == null || value.isEmpty()) {
      MDC.remove(key);
    } else {
      MDC.put(key, value);
    }
  }

  /** Nonempty caller IDs are preserved verbatim; every execution receives a fresh operation UUID. */
  public static OperationLogScope open(String requestId, String method) {
    return start(new ContextSnapshot(requestId, null, null, null, null, null), method);
  }

  static OperationLogScope start(ContextSnapshot context, String method) {
    return new OperationLogScope(new ContextSnapshot(
        requestId(context.requestId()), UUID.randomUUID().toString(),
        context.jobRunId(), context.messageId(), context.noticeNumber(),
        context.creditorFiscalCode()), method, null);
  }

  static OperationLogScope resume(ContextSnapshot context) {
    return new OperationLogScope(new ContextSnapshot(
        requestId(context.requestId()),
        context.operationId() == null || context.operationId().isEmpty()
            ? UUID.randomUUID().toString() : context.operationId(),
        context.jobRunId(), context.messageId(), context.noticeNumber(),
        context.creditorFiscalCode()), null, null);
  }

  /** Temporarily replaces payment identifiers in this operation, sharing its diagnostic ownership. */
  public OperationLogScope payment(String noticeNumber, String creditorFiscalCode) {
    requireActiveOperation();
    ContextSnapshot current = ContextSnapshot.capture();
    return new OperationLogScope(new ContextSnapshot(
        current.requestId(), current.operationId(), current.jobRunId(), current.messageId(),
        noticeNumber, creditorFiscalCode), MDC.get("method"), ownership);
  }

  /** Captured at the actual call, not recomputed when the operation eventually completes. */
  public record DependencyFailure(Throwable failure, EventDetails.DependencyCall details) {}

  public void rememberDependency(DependencyFailure context) {
    requireActiveOperation();
    ownership.dependencies.put(context.failure(), context);
  }

  public DependencyFailure dependencyFailure(Throwable failure) {
    requireActiveOperation();
    Set<Throwable> seen = identitySet();
    for (Throwable next = failure; next != null && seen.add(next); next = next.getCause()) {
      DependencyFailure context = ownership.dependencies.get(next);
      if (context != null) {
        return context;
      }
    }
    return null;
  }

  /** Only absorbed/recovered failures can be handled before reaching a final boundary. */
  public void markHandled(Throwable failure) {
    requireActiveOperation();
    ownership.handled.add(java.util.Objects.requireNonNull(failure));
  }

  public boolean isHandled(Throwable failure) {
    requireActiveOperation();
    Set<Throwable> seen = identitySet();
    for (Throwable next = failure; next != null && seen.add(next); next = next.getCause()) {
      if (ownership.handled.contains(next)) {
        return true;
      }
    }
    return false;
  }

  public void recordDiagnosis() {
    requireActiveOperation();
    ownership.diagnosed = true;
  }

  @Override
  public void close() {
    if (Thread.currentThread() != thread) {
      throw new IllegalStateException("Scope must be closed on its owning thread");
    }
    if (closed) {
      return;
    }
    if (CURRENT.get() != this) {
      throw new IllegalStateException("Scopes must be closed in reverse opening order");
    }
    if (previousMdc == null) {
      MDC.clear();
    } else {
      MDC.setContextMap(previousMdc);
    }
    if (previousScope == null) {
      CURRENT.remove();
    } else {
      CURRENT.set(previousScope);
    }
    if (ownsDiagnostics) {
      ownership.dependencies.clear();
      ownership.handled.clear();
      ownership.pendingBoundaryFailure = null;
    }
    summaryArgs = null;
    closed = true;
  }

  private void requireActiveOperation() {
    OperationLogScope active = CURRENT.get();
    if (Thread.currentThread() != thread || closed || active == null
        || active.ownership != ownership) {
      throw new IllegalStateException("Scope is not the active operation on this thread");
    }
  }

  private static String requestId(String supplied) {
    return supplied == null || supplied.isEmpty() ? UUID.randomUUID().toString() : supplied;
  }

  private static Set<Throwable> identitySet() {
    return Collections.newSetFromMap(new IdentityHashMap<>());
  }

  private static final class DiagnosticOwnership {
    private final Map<Throwable, DependencyFailure> dependencies = new IdentityHashMap<>();
    private final Set<Throwable> handled = identitySet();
    private Throwable pendingBoundaryFailure;
    private boolean diagnosed;
  }
}
