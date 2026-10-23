package it.gov.pagopa.payments.logging;

import static org.junit.jupiter.api.Assertions.*;

import it.gov.pagopa.payments.endpoints.validation.exceptions.PartnerValidationException;
import it.gov.pagopa.payments.model.PaaErrorEnum;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class TechnicalFailureLoggingTest {
  @Test
  void diagnosticsRetainOriginalMessageWithoutRepeatingCauseMessagesInStackFrames() throws Exception {
    var failure = new IllegalStateException("Operation failed\nwith \"details\"", new IOException("SECRET URL/token"));
    failure.addSuppressed(new IllegalArgumentException("SECRET header"));
    Map<String, String> previous = MDC.getCopyOfContextMap();
    try (ApplicationLogCapture logs = new ApplicationLogCapture()) {
      MDC.clear();
      MDC.put("method", "paSendRTV2");
      MDC.put("status", "OK");
      TechnicalFailureLogging.logTechnicalFailure(
          LoggerFactory.getLogger(getClass()), "Technical operation failed", failure);
      assertEquals("OK", MDC.get("status"));
      assertEquals(1, logs.events.size());
      var event = logs.events.get(0);
      var logEntry = logs.encodedEvent(event);
      assertEquals(IllegalStateException.class.getName(), logEntry.path("error.type").asText());
      assertEquals(failure.getMessage(), logEntry.path("error.message").asText());
      assertEquals("KO", logEntry.path("status").asText());
      assertTrue(logEntry.path("error.stack_trace").asText().contains("Caused by: java.io.IOException"));
      assertFalse(logEntry.path("error.stack_trace").asText().contains("Suppressed:"));
      assertTrue(logEntry.path("error.stack_trace").asText().contains("TechnicalFailureLoggingTest"));
      assertFalse(logEntry.toString().contains("SECRET"));
      assertNull(event.getThrowableProxy());
      String local = new StructuredLogFieldsConverter().convert(event);
      assertTrue(local.contains("\n\tat "));
      assertTrue(local.contains("error.message=\"Operation failed\\nwith \\\"details\\\"\""));
      assertFalse(local.contains("SECRET"));
      assertEquals("Operation failed\nwith \"details\"", failure.getMessage());
    } finally {
      if (previous == null) MDC.clear();
      else MDC.setContextMap(previous);
    }
  }

  @Test
  void includesOnlyOuterAndRootExceptionsWithExactlyThreeFramesEach() throws Exception {
    var root = new IOException("Root message");
    root.setStackTrace(frames("Root", 7));
    var intermediate = new UnsupportedOperationException("Intermediate message", root);
    intermediate.setStackTrace(frames("Intermediate", 5));
    var failure = new IllegalStateException("Original message", intermediate);
    failure.setStackTrace(frames("Outer", 5));
    failure.addSuppressed(new IllegalArgumentException("Suppressed message"));

    try (ApplicationLogCapture logs = new ApplicationLogCapture()) {
      TechnicalFailureLogging.logTechnicalFailure(
          LoggerFactory.getLogger(getClass()), "Technical operation failed", failure);
      var logEntry = logs.encodedEvent(logs.events.get(0));
      String expected = """
          java.lang.IllegalStateException
          \tat example.Outer.frame0(Outer.java:1)
          \tat example.Outer.frame1(Outer.java:2)
          \tat example.Outer.frame2(Outer.java:3)
          \t... 2 frames omitted
          Caused by: java.io.IOException
          \tat example.Root.frame0(Root.java:1)
          \tat example.Root.frame1(Root.java:2)
          \tat example.Root.frame2(Root.java:3)
          \t... 4 frames omitted""";
      assertEquals(expected, logEntry.path("error.stack_trace").asText());
      assertEquals("Original message", logEntry.path("error.message").asText());
      assertSame(intermediate, failure.getCause());
      assertSame(root, intermediate.getCause());
      assertEquals(5, failure.getStackTrace().length);
      assertEquals(7, root.getStackTrace().length);
      assertEquals(1, failure.getSuppressed().length);
      assertTrue(new StructuredLogFieldsConverter().convert(logs.events.get(0)).contains(expected));
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 3, 4, 25})
  void singleExceptionIsNotDuplicatedAndHasAtMostThreeFrames(int frameCount) throws Exception {
    var failure = new IllegalStateException("Original message");
    failure.setStackTrace(frames("Outer", frameCount));
    try (ApplicationLogCapture logs = new ApplicationLogCapture()) {
      TechnicalFailureLogging.logTechnicalFailure(
          LoggerFactory.getLogger(getClass()), "Technical operation failed", failure);
      String trace = logs.encodedEvent(logs.events.get(0)).path("error.stack_trace").asText();
      assertEquals(Math.min(3, frameCount),
          trace.lines().filter(line -> line.startsWith("\tat ")).count());
      assertFalse(trace.contains("Caused by:"));
      assertEquals(frameCount > 3, trace.contains("frames omitted"));
      assertTrue(trace.startsWith(IllegalStateException.class.getName()));
    }
  }

  @Test
  void findsTheRealRootBeyondThePreviousCauseDepthLimit() throws Exception {
    var root = new IOException("Root message");
    root.setStackTrace(frames("Root", 1));
    Throwable cause = root;
    for (int depth = 0; depth < 100; depth++) {
      cause = new IllegalArgumentException("Intermediate message", cause);
    }
    var failure = new IllegalStateException("Original message", cause);
    failure.setStackTrace(frames("Outer", 1));
    try (ApplicationLogCapture logs = new ApplicationLogCapture()) {
      TechnicalFailureLogging.logTechnicalFailure(
          LoggerFactory.getLogger(getClass()), "Technical operation failed", failure);
      String trace = logs.encodedEvent(logs.events.get(0)).path("error.stack_trace").asText();
      assertTrue(trace.contains("Caused by: java.io.IOException"));
      assertFalse(trace.contains("IllegalArgumentException"));
      assertEquals(1, trace.lines().filter(line -> line.startsWith("Caused by:")).count());
      assertEquals(2, trace.lines().filter(line -> line.startsWith("\tat ")).count());
    }
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "Original exception message"})
  void preservesAbsentEmptyAndNonemptyMessagesWithoutAPlaceholder(String message) throws Exception {
    try (ApplicationLogCapture logs = new ApplicationLogCapture()) {
      TechnicalFailureLogging.logTechnicalFailure(LoggerFactory.getLogger(getClass()),
          "Technical operation failed", new IllegalStateException(message));
      var logEntry = logs.encodedEvent(logs.events.get(0));
      if (message == null) {
        assertFalse(logEntry.has("error.message"));
      } else {
        assertEquals(message, logEntry.path("error.message").asText());
      }
    }
  }

  @Test
  void expectedPartnerRejectionHasNoFabricatedTechnicalDiagnosis() {
    try (ApplicationLogCapture logs = new ApplicationLogCapture()) {
      TechnicalFailureLogging.logPartnerFailureIfTechnical(LoggerFactory.getLogger(getClass()),
          "SOAP request failed", new PartnerValidationException(
              PaaErrorEnum.PAA_RECEIPT_DUPLICATA, new IllegalArgumentException("SECRET")));
      assertTrue(logs.events.isEmpty());
    }
  }

  @Test
  void cyclicCauseChainsAreBoundedAndRemainPrivate() throws Exception {
    var first = new IllegalStateException("SECRET first");
    var second = new IllegalArgumentException("SECRET second", first);
    first.initCause(second);
    try (ApplicationLogCapture logs = new ApplicationLogCapture()) {
      TechnicalFailureLogging.logTechnicalFailure(
          LoggerFactory.getLogger(getClass()), "Technical operation failed", first);
      String trace = logs.encodedEvent(logs.events.get(0)).path("error.stack_trace").asText();
      assertTrue(trace.endsWith("Cyclic cause chain omitted"));
      assertFalse(trace.contains("Caused by:"));
      assertTrue(trace.lines().filter(line -> line.startsWith("\tat ")).count() <= 3);
      assertFalse(trace.contains("SECRET"));
    }
  }

  private static StackTraceElement[] frames(String owner, int count) {
    return java.util.stream.IntStream.range(0, count)
        .mapToObj(index -> new StackTraceElement(
            "example." + owner, "frame" + index, owner + ".java", index + 1))
        .toArray(StackTraceElement[]::new);
  }
}
