package it.gov.pagopa.payments.logging;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class SafeDiagnosticTest {
  @Test
  void retainsOriginalMessagesTypesAndSelectedFramesWithoutCallingToString() {
    String rootMessage = "root \"quoted\"\n" + "root-secret".repeat(10_000);
    String causeMessage = "cause \"quoted\"\r\n" + "cause-secret".repeat(10_000);
    String suppressedMessage = "suppressed \"quoted\"\n" + "suppressed-secret".repeat(10_000);
    Throwable root = new ToStringForbiddenException(rootMessage);
    Throwable cause = new IllegalArgumentException(causeMessage);
    Throwable suppressed = new IOException(suppressedMessage);
    Throwable nested = new IllegalStateException("nested-secret");
    root.initCause(cause);
    root.addSuppressed(suppressed);
    suppressed.initCause(nested);
    root.setStackTrace(frames("root"));
    cause.setStackTrace(frames("cause"));
    suppressed.setStackTrace(frames("suppressed"));
    nested.setStackTrace(frames("nested"));

    SafeDiagnostic diagnostic = SafeDiagnostic.from(root);
    assertEquals(ToStringForbiddenException.class.getName(), diagnostic.type());
    assertEquals(rootMessage, diagnostic.message());
    assertTrue(diagnostic.stackTrace().startsWith(root.getClass().getName() + ": " + rootMessage + "\n"));
    assertTrue(diagnostic.stackTrace().contains(
        "Caused by: java.lang.IllegalArgumentException: " + causeMessage + "\n"));
    assertTrue(diagnostic.stackTrace().contains(
        "Suppressed: java.io.IOException: " + suppressedMessage + "\n"));
    assertFalse(diagnostic.stackTrace().contains("java.lang.IllegalStateException"));
    assertTrue(diagnostic.stackTrace().contains("1 exceptions omitted"));
    for (String name : new String[] {"root", "cause", "suppressed"}) {
      assertTrue(diagnostic.stackTrace().contains("example." + name + ".call(Source.java:17)"));
    }
    assertFalse(diagnostic.stackTrace().contains("nested-secret"));
    assertSame(cause, root.getCause());
    assertSame(suppressed, root.getSuppressed()[0]);
    assertArrayEquals(frames("root"), root.getStackTrace());
    assertEquals(rootMessage, root.getMessage());
    assertEquals(causeMessage, cause.getMessage());
    assertEquals(suppressedMessage, suppressed.getMessage());
    assertArrayEquals(frames("cause"), cause.getStackTrace());
    assertArrayEquals(frames("suppressed"), suppressed.getStackTrace());
  }

  @Test
  void preservesNullAndEmptyMessagesOnInitialCauseAndSuppressedHeaders() {
    for (String message : new String[] {null, ""}) {
      Throwable root = new ToStringForbiddenException(message);
      Throwable cause = new IOException(message);
      Throwable suppressed = new IllegalArgumentException(message);
      root.initCause(cause);
      root.addSuppressed(suppressed);
      for (Throwable node : new Throwable[] {root, cause, suppressed}) {
        node.setStackTrace(new StackTraceElement[0]);
      }
      SafeDiagnostic diagnostic = SafeDiagnostic.from(root);
      assertEquals(message, diagnostic.message());
      String suffix = message == null ? "\n" : ": \n";
      assertEquals(root.getClass().getName() + suffix
          + "Caused by: " + cause.getClass().getName() + suffix
          + "Suppressed: " + suppressed.getClass().getName() + suffix,
          diagnostic.stackTrace());
    }
  }

  @Test
  void cyclicAndSharedGraphsTerminateWithoutUsingThrowableToString() {
    Throwable root = new ToStringForbiddenException();
    Throwable cause = new ToStringForbiddenException();
    root.initCause(cause);
    cause.initCause(root);
    root.addSuppressed(cause);

    SafeDiagnostic diagnostic = SafeDiagnostic.from(root);
    assertTrue(diagnostic.stackTrace().contains("[CIRCULAR/SHARED REFERENCE omitted]"));
    assertEquals(2, exceptionCount(diagnostic.stackTrace()));
    assertFalse(diagnostic.stackTrace().contains("exceptions omitted"));
    assertNull(diagnostic.message());
  }

  @Test
  void deepCauseGraphsPreserveInitialFirstCauseAndActualRootWithoutRecursiveRendering() {
    Throwable root = new RuntimeException("secret");
    Throwable current = root;
    root.setStackTrace(new StackTraceElement[0]);
    Throwable firstCause = null;
    for (int i = 0; i < 2000; i++) {
      Throwable next = new RuntimeException("secret");
      next.setStackTrace(new StackTraceElement[0]);
      current.initCause(next);
      current = next;
      if (i == 0) {
        firstCause = next;
        next.setStackTrace(frames("firstCause"));
      }
    }
    current.setStackTrace(frames("last"));
    SafeDiagnostic diagnostic = SafeDiagnostic.from(root);
    assertEquals(2, diagnostic.stackTrace().lines()
        .filter(line -> line.startsWith("Caused by: ")).count());
    assertEquals(3, exceptionCount(diagnostic.stackTrace()));
    assertTrue(diagnostic.stackTrace().startsWith(RuntimeException.class.getName() + ": secret\n"));
    assertTrue(diagnostic.stackTrace().contains("example.firstCause.call(Source.java:17)"));
    assertTrue(diagnostic.stackTrace().contains("example.last.call(Source.java:17)"));
    assertTrue(diagnostic.stackTrace().contains("1998 exceptions omitted"));
    assertSame(firstCause, root.getCause());
    assertEquals(3, diagnostic.stackTrace().lines()
        .filter(line -> line.endsWith(": secret")).count());
  }

  @Test
  void prioritizesFirstThreeApplicationFramesInOriginalOrderAndReportsAllOmissions() {
    Throwable failure = new ToStringForbiddenException();
    StackTraceElement[] original = new StackTraceElement[9];
    original[0] = frames("libraryHead")[0];
    for (int i = 1; i < 8; i++) {
      original[i] = frames("it.gov.pagopa.payments.Service" + i, i)[0];
    }
    original[8] = frames("libraryTail")[0];
    failure.setStackTrace(original);

    String trace = SafeDiagnostic.from(failure).stackTrace();
    assertEquals(3, frameCount(trace));
    int previous = -1;
    for (int i = 1; i <= 3; i++) {
      int position = trace.indexOf("it.gov.pagopa.payments.Service" + i + ".call");
      assertTrue(position > previous);
      previous = position;
    }
    assertFalse(trace.contains("library"));
    assertFalse(trace.contains("Service4"));
    assertFalse(trace.contains("Service5"));
    assertFalse(trace.contains("Service6"));
    assertFalse(trace.contains("Service7"));
    assertTrue(trace.contains("6 frames omitted"));
    assertArrayEquals(original, failure.getStackTrace());
  }

  @Test
  void applicationFramesBelowLongLibraryHeadAreNotLostOrFilledWithLibraryFrames() {
    Throwable failure = new ToStringForbiddenException();
    StackTraceElement[] original = new StackTraceElement[12];
    for (int i = 0; i < 11; i++) {
      original[i] = frames("library" + i)[0];
    }
    original[11] = frames("it.gov.pagopa.payments.Service", 42)[0];
    failure.setStackTrace(original);

    String trace = SafeDiagnostic.from(failure).stackTrace();
    assertEquals(1, frameCount(trace));
    assertTrue(trace.contains("it.gov.pagopa.payments.Service.call(Source.java:42)"));
    assertFalse(trace.contains("library"));
    assertTrue(trace.contains("11 frames omitted"));
  }

  @Test
  void fallsBackToFirstThreeOriginalFramesWithoutMatchingNeighborPackages() {
    Throwable failure = new ToStringForbiddenException();
    StackTraceElement[] original = new StackTraceElement[7];
    for (int i = 0; i < original.length; i++) {
      original[i] = frames("it.gov.pagopa.paymentsOther.Library" + i, i)[0];
    }
    failure.setStackTrace(original);

    String trace = SafeDiagnostic.from(failure).stackTrace();
    assertEquals(3, frameCount(trace));
    for (int i = 0; i < 3; i++) {
      assertTrue(trace.contains(original[i].toString()));
    }
    for (int i = 3; i < original.length; i++) {
      assertFalse(trace.contains("Library" + i));
    }
    assertTrue(trace.contains("4 frames omitted"));
  }

  @Test
  void primaryChainUsesEntireBudgetBeforeSuppressedEvenOnSkippedCauses() {
    Throwable root = new ToStringForbiddenException();
    Throwable first = new ToStringForbiddenException();
    Throwable skipped = new ToStringForbiddenException();
    Throwable deepest = new ToStringForbiddenException();
    root.initCause(first);
    first.initCause(skipped);
    skipped.initCause(deepest);
    deepest.setStackTrace(frames("deepest"));
    for (Throwable node : new Throwable[] {root, first, skipped, deepest}) {
      node.addSuppressed(new ToStringForbiddenException());
    }

    String trace = SafeDiagnostic.from(root).stackTrace();
    assertEquals(3, exceptionCount(trace));
    assertTrue(trace.contains("example.deepest.call"));
    assertFalse(trace.contains("Suppressed: "));
    assertTrue(trace.contains("5 exceptions omitted"));
    assertTrue(frameCount(trace) <= 9);
  }

  @Test
  void shortPrimaryChainUsesRemainingSlotsForSuppressedThenItsCause() {
    Throwable root = new ToStringForbiddenException();
    Throwable suppressed = new ToStringForbiddenException();
    Throwable nested = new ToStringForbiddenException();
    root.addSuppressed(suppressed);
    suppressed.initCause(nested);
    root.addSuppressed(new ToStringForbiddenException());
    suppressed.setStackTrace(frames("suppressed"));
    nested.setStackTrace(frames("nested"));

    String trace = SafeDiagnostic.from(root).stackTrace();
    assertEquals(3, exceptionCount(trace));
    assertTrue(trace.contains("Suppressed: " + ToStringForbiddenException.class.getName()));
    assertTrue(trace.contains("Caused by: " + ToStringForbiddenException.class.getName()));
    assertTrue(trace.contains("example.suppressed.call"));
    assertTrue(trace.contains("example.nested.call"));
    assertTrue(trace.contains("1 exceptions omitted"));
  }

  @Test
  void deepSuppressedCycleHasConstantIndentationAndBoundedRenderedNodes() {
    Throwable root = new ToStringForbiddenException();
    Throwable current = root;
    for (int i = 0; i < 2000; i++) {
      Throwable next = new ToStringForbiddenException();
      current.addSuppressed(next);
      current = next;
    }
    current.addSuppressed(root);

    String trace = SafeDiagnostic.from(root).stackTrace();
    assertEquals(3, exceptionCount(trace));
    assertTrue(trace.contains("1998 exceptions omitted"));
    assertTrue(trace.contains("[CIRCULAR/SHARED REFERENCE omitted]"));
    assertFalse(trace.contains("\t\t"));
    assertTrue(frameCount(trace) <= 9);
    assertTrue(trace.lines().count() <= 17);
  }

  @Test
  void deepPrimaryCycleKeepsLastUniqueCauseAndDoesNotCountSharedNodesTwice() {
    Throwable root = new ToStringForbiddenException();
    Throwable first = new ToStringForbiddenException();
    Throwable middle = new ToStringForbiddenException();
    Throwable last = new ToStringForbiddenException();
    root.initCause(first);
    first.initCause(middle);
    middle.initCause(last);
    last.initCause(first);
    root.addSuppressed(last);
    last.setStackTrace(frames("lastUnique"));

    String trace = SafeDiagnostic.from(root).stackTrace();
    assertEquals(3, exceptionCount(trace));
    assertTrue(trace.contains("example.lastUnique.call"));
    assertTrue(trace.contains("1 exceptions omitted"));
    assertTrue(trace.contains("[CIRCULAR/SHARED REFERENCE omitted]"));
  }

  @Test
  void emptyAndExactlyThreeFramesDoNotClaimOmissions() {
    Throwable failure = new ToStringForbiddenException();
    failure.setStackTrace(new StackTraceElement[0]);
    assertEquals(failure.getClass().getName() + "\n",
        SafeDiagnostic.from(failure).stackTrace());
    StackTraceElement[] three = new StackTraceElement[3];
    for (int i = 0; i < three.length; i++) {
      three[i] = frames("it.gov.pagopa.payments.Service", i)[0];
    }
    failure.setStackTrace(three);
    String trace = SafeDiagnostic.from(failure).stackTrace();
    assertEquals(3, frameCount(trace));
    assertFalse(trace.contains("omitted"));
  }

  private static long exceptionCount(String trace) {
    return trace.lines().filter(line -> !line.startsWith("\t")).count();
  }

  private static long frameCount(String trace) {
    return trace.lines().filter(line -> line.startsWith("\tat ")).count();
  }

  private static StackTraceElement[] frames(String name) {
    return frames("example." + name, 17);
  }

  private static StackTraceElement[] frames(String className, int line) {
    return new StackTraceElement[] {new StackTraceElement(className, "call", "Source.java", line)};
  }

  private static final class ToStringForbiddenException extends RuntimeException {
    private ToStringForbiddenException() {
      this(null);
    }

    private ToStringForbiddenException(String message) {
      super(message);
    }

    @Override
    public String getLocalizedMessage() {
      throw new AssertionError("Must not read getLocalizedMessage");
    }

    @Override
    public String toString() {
      throw new AssertionError("Must not read toString");
    }
  }
}
