package it.gov.pagopa.payments.logging;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * Thread-confined, LIFO operation boundary. Installs typed context, then restores the exact
 * previous MDC on close; unrelated legacy fields are never inherited by a new operation.
 */
public final class OperationScope implements AutoCloseable {
  private static final ThreadLocal<OperationScope> CURRENT = new ThreadLocal<>();

  private final Thread thread = Thread.currentThread();
  private final Map<String, String> previousMdc;
  private final OperationScope previousScope;
  private final DiagnosticOwnership ownership;
  private final boolean ownsDiagnostics;
  private boolean closed;

  private OperationScope(
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

  public static OperationScope open(String requestId) {
    return open(requestId, null);
  }

  /** Returns the explicit boundary for lower-layer diagnostics; never creates an implicit scope. */
  public static OperationScope current() {
    OperationScope scope = CURRENT.get();
    if (scope == null) {
      throw new IllegalStateException("No active operation scope");
    }
    return scope;
  }

  /** Nonempty caller IDs are preserved verbatim; every execution receives a fresh operation UUID. */
  public static OperationScope open(String requestId, String method) {
    return start(new ContextSnapshot(requestId, null, null, null, null, null), method);
  }

  static OperationScope start(ContextSnapshot context, String method) {
    return new OperationScope(new ContextSnapshot(
        requestId(context.requestId()), UUID.randomUUID().toString(),
        context.jobRunId(), context.messageId(), context.noticeNumber(),
        context.creditorFiscalCode()), method, null);
  }

  static OperationScope resume(ContextSnapshot context) {
    return new OperationScope(new ContextSnapshot(
        requestId(context.requestId()),
        context.operationId() == null || context.operationId().isEmpty()
            ? UUID.randomUUID().toString() : context.operationId(),
        context.jobRunId(), context.messageId(), context.noticeNumber(),
        context.creditorFiscalCode()), null, null);
  }

  /** Temporarily replaces payment identifiers in this operation, sharing its diagnostic ownership. */
  public OperationScope payment(String noticeNumber, String creditorFiscalCode) {
    requireActiveOperation();
    ContextSnapshot current = ContextSnapshot.capture();
    return new OperationScope(new ContextSnapshot(
        current.requestId(), current.operationId(), current.jobRunId(), current.messageId(),
        noticeNumber, creditorFiscalCode), MDC.get("method"), ownership);
  }

  /**
   * Explicit opt-in around a writer call; EventWriter itself never deduplicates.
   * Ownership is recorded only after the callback returns normally, not when emission fails.
   */
  public boolean diagnoseOnce(Throwable failure, Runnable diagnostic) {
    requireActiveOperation();
    Objects.requireNonNull(failure, "failure");
    Objects.requireNonNull(diagnostic, "diagnostic");
    Set<Throwable> related = ownership.related(failure);
    if (related.stream().anyMatch(ownership.diagnosed::contains)) {
      return false;
    }
    diagnostic.run();
    ownership.diagnosed.addAll(related);
    return true;
  }

  /**
   * Associates a conversion that discarded its original cause. Call only for the same failure,
   * not merely equal messages or separate retry attempts; links work before or after diagnosis.
   */
  public void linkFailure(Throwable converted, Throwable original) {
    requireActiveOperation();
    Objects.requireNonNull(converted, "converted");
    Objects.requireNonNull(original, "original");
    ownership.links.computeIfAbsent(converted, ignored -> identitySet()).add(original);
    ownership.links.computeIfAbsent(original, ignored -> identitySet()).add(converted);
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
      ownership.diagnosed.clear();
      ownership.links.clear();
    }
    closed = true;
  }

  private void requireActiveOperation() {
    OperationScope active = CURRENT.get();
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
    private final Set<Throwable> diagnosed = identitySet();
    private final Map<Throwable, Set<Throwable>> links = new IdentityHashMap<>();

    /**
     * Follow the complete primary chain and explicit conversions, independently of rendering
     * budgets. Suppressed exceptions are not aliases: a cleanup failure may need its own event.
     */
    private Set<Throwable> related(Throwable failure) {
      Set<Throwable> seen = identitySet();
      ArrayDeque<Throwable> pending = new ArrayDeque<>();
      pending.add(failure);
      while (!pending.isEmpty()) {
        Throwable next = pending.removeFirst();
        if (!seen.add(next)) {
          continue;
        }
        Throwable cause = next.getCause();
        if (cause != null) {
          pending.addLast(cause);
        }
        Set<Throwable> aliases = links.get(next);
        if (aliases != null) {
          pending.addAll(aliases);
        }
      }
      return seen;
    }
  }
}
