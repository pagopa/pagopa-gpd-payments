package it.gov.pagopa.payments.logging;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * ECS diagnostics preserving original types and messages without mutating the throwable.
 * Safety here means controlled traversal and rendering, not message sanitization.
 * The original exception is not passed to a logger/encoder.
 */
record SafeDiagnostic(String type, String message, String stackTrace) {
  /** Application frames take precedence over library frames, even below a library-only head. */
  private static final String APPLICATION_PREFIX = "it.gov.pagopa.payments.";
  /** Output budgets, not traversal limits: the actual primary root must remain discoverable. */
  private static final int MAX_FRAMES = 5;
  private static final int MAX_EXCEPTIONS = 3;

  /**
   * Reserves slots for the initial exception, first intermediate cause and deepest unique
   * primary cause. A cycle has no root, so its last unique cause occupies the root slot.
   * Remaining slots use cause-first traversal of suppressed diagnostics. Both walks use
   * identity and no recursion; the full graph is scanned for omission counts, not rendered.
   */
  static SafeDiagnostic from(Throwable failure) {
    String type = failure.getClass().getName();
    String message = failure.getMessage();
    Set<Throwable> primary = Collections.newSetFromMap(new IdentityHashMap<>());
    List<Throwable> chain = new ArrayList<>();
    for (Throwable current = failure; current != null && primary.add(current);
        current = current.getCause()) {
      chain.add(current);
    }
    List<Trace> selected = new ArrayList<>();
    selected.add(new Trace(failure, ""));
    if (chain.size() > 1) {
      selected.add(new Trace(chain.get(1), "Caused by: "));
    }
    if (chain.size() > 2) {
      selected.add(new Trace(chain.get(chain.size() - 1), "Caused by: "));
    }

    Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    var pending = new ArrayDeque<Trace>();
    pending.push(new Trace(failure, ""));
    boolean repeated = false;
    while (!pending.isEmpty()) {
      Trace trace = pending.pop();
      Throwable current = trace.failure();
      if (!visited.add(current)) {
        repeated = true;
        continue;
      }
      if (!primary.contains(current) && selected.size() < MAX_EXCEPTIONS) {
        selected.add(trace);
      }
      Throwable[] suppressed = current.getSuppressed();
      for (int i = suppressed.length - 1; i >= 0; i--) {
        pending.push(new Trace(suppressed[i], "Suppressed: "));
      }
      Throwable cause = current.getCause();
      if (cause != null) {
        pending.push(new Trace(cause, "Caused by: "));
      }
    }

    StringBuilder stack = new StringBuilder();
    for (Trace trace : selected) {
      stack.append(trace.caption()).append(trace.failure().getClass().getName());
      // Only getMessage text is retained; toString and arbitrary exception internals are excluded.
      String selectedMessage = trace.failure() == failure ? message : trace.failure().getMessage();
      if (selectedMessage != null) {
        stack.append(": ").append(selectedMessage);
      }
      stack.append('\n');
      appendFrames(stack, trace.failure().getStackTrace());
    }
    int omitted = visited.size() - selected.size();
    if (omitted > 0) {
      stack.append("\t... ").append(omitted).append(" exceptions omitted\n");
    }
    if (repeated) {
      stack.append("\t[CIRCULAR/SHARED REFERENCE omitted]\n");
    }
    return new SafeDiagnostic(type, message, stack.toString());
  }

  /** Keeps up to five application frames in source order, or original frames if none apply. */
  private static void appendFrames(StringBuilder stack, StackTraceElement[] frames) {
    boolean applicationOnly = false;
    for (StackTraceElement frame : frames) {
      if (frame.getClassName().startsWith(APPLICATION_PREFIX)) {
        applicationOnly = true;
        break;
      }
    }
    int rendered = 0;
    for (StackTraceElement frame : frames) {
      if ((!applicationOnly || frame.getClassName().startsWith(APPLICATION_PREFIX))
          && rendered < MAX_FRAMES) {
        stack.append("\tat ").append(frame).append('\n');
        rendered++;
      }
    }
    if (frames.length > rendered) {
      stack.append("\t... ").append(frames.length - rendered).append(" frames omitted\n");
    }
  }

  private record Trace(Throwable failure, String caption) {}
}
