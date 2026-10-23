package it.gov.pagopa.payments.config;

import static it.gov.pagopa.payments.config.LogContext.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;

class LogContextTest {
  private Map<String, String> previousMdc;

  @BeforeEach
  void isolateMdc() {
    previousMdc = MDC.getCopyOfContextMap();
    MDC.clear();
  }

  @AfterEach
  void restoreMdc() {
    LogContext.restore(previousMdc);
  }

  @Test
  void jsonKeepsTypesSingleLineAndScalarAllowlist() {
    ObjectNode fields = LogContext.event();
    LogContext.putScalar(fields, HTTP_CODE, 201);
    LogContext.putScalar(fields, RESPONSE_TIME, 17L);
    LogContext.putScalar(fields, STATUS, STATUS_OK);
    LogContext.putScalar(fields, "unsupported", Map.of("secret", "not-allowed"));
    fields.put(ARGS, "{\"pageNum\":2}");
    fields.put(ERROR_MESSAGE, "first error\nsecond error");
    fields.putObject(CTX_DETAILS).put("count", 2);
    MDC.put(CORRELATION_ID, "request-123");

    try (LogCapture logs = new LogCapture(LogContextTest.class)) {
      LogContext.log(LoggerFactory.getLogger(LogContextTest.class), Level.INFO, "Completed", fields);
      fields.put(HTTP_CODE, 500);
      JsonNode event = logs.events().get(0);
      assertEquals("request-123", event.path(CORRELATION_ID).asText());
      assertEquals(201, event.path(HTTP_CODE).intValue());
      assertTrue(event.path(HTTP_CODE).isIntegralNumber());
      assertTrue(event.path(RESPONSE_TIME).isIntegralNumber());
      assertTrue(event.path(ARGS).isTextual());
      assertEquals("{\"pageNum\":2}", event.path(ARGS).textValue());
      assertEquals(2, event.path(CTX_DETAILS).path("count").intValue());
      assertEquals("first error\nsecond error", event.path(ERROR_MESSAGE).textValue());
      assertFalse(event.has("unsupported"));
      String line = logs.lines().get(0);
      assertTrue(line.endsWith("\n"));
      assertFalse(line.substring(0, line.length() - 1).contains("\n"));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void diagnosticsKeepOriginalMessageAndBoundedRootOrCycle(boolean cyclic) {
    IllegalArgumentException root = new IllegalArgumentException("Root failure");
    IllegalStateException failure = new IllegalStateException("Original failure", root);
    StackTraceElement[] frames = new StackTraceElement[5];
    for (int i = 0; i < frames.length; i++) {
      frames[i] = new StackTraceElement("Example", "frame" + i, "Example.java", i + 1);
    }
    failure.setStackTrace(frames);
    root.setStackTrace(frames);
    if (cyclic) {
      root.initCause(failure);
    }
    try (LogCapture logs = new LogCapture(LogContextTest.class)) {
      LoggerFactory.getLogger(LogContextTest.class).error("Failed", failure);
      JsonNode event = logs.events().get(0);
      assertEquals(IllegalStateException.class.getName(), event.path(ERROR_TYPE).asText());
      assertEquals("Original failure", event.path(ERROR_MESSAGE).asText());
      String stack = event.path(ERROR_STACK_TRACE).asText();
      assertTrue(stack.contains("frame2"));
      assertFalse(stack.contains("frame3"));
      assertTrue(stack.contains("2 frames omitted"));
      assertEquals(cyclic, stack.contains("[circular cause chain]"));
      assertEquals(!cyclic, stack.contains("Root cause:"));
    }
  }
}
