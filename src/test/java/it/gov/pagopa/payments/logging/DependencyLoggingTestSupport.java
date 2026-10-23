package it.gov.pagopa.payments.logging;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

abstract class DependencyLoggingTestSupport {
  final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();
  private final Logger logger = (Logger) LoggerFactory.getLogger(DependencyLogCall.class);
  private final AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
    @Override
    protected void append(ILoggingEvent event) {
      event.prepareForDeferredProcessing();
      events.add(event);
    }
  };
  private Map<String, String> previous;

  @BeforeEach
  void capture() {
    previous = MDC.getCopyOfContextMap();
    MDC.clear();
    MDC.put("method", "request-summary");
    MDC.put("status", "OK");
    MDC.put("correlation_id", "caller-correlation");
    MDC.put("ctx_creditor_id", "creditor");
    MDC.put("ctx_notice_number", "notice");
    MDC.put("ctx_iuv", "iuv");
    MDC.put("args", "SECRET legacy arguments");
    MDC.put("ctx_soap_outcome", "KO");
    MDC.put("httpCode", "500");
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void cleanupCapture() {
    logger.detachAppender(appender);
    appender.stop();
    if (previous == null) {
      MDC.clear();
    } else {
      MDC.setContextMap(previous);
    }
  }

  JsonNode assertEvent(int index, Integer code, String outcome, String level) {
    ILoggingEvent event = events.get(index);
    assertEquals("Dependency interaction completed", event.getFormattedMessage());
    assertEquals(level, event.getLevel().toString());
    assertNull(event.getThrowableProxy());
    assertEquals(outcome, event.getMDCPropertyMap().get("status"));
    assertEquals("caller-correlation", event.getMDCPropertyMap().get("correlation_id"));
    assertEquals("creditor", event.getMDCPropertyMap().get("ctx_creditor_id"));
    assertEquals("notice", event.getMDCPropertyMap().get("ctx_notice_number"));
    assertEquals("iuv", event.getMDCPropertyMap().get("ctx_iuv"));
    JsonNode fields = encodedRecord(event);
    assertFalse(fields.has("args"));
    assertFalse(fields.has("ctx_soap_outcome"));
    assertFalse(fields.has("httpCode"));
    assertEquals(2, fields.path("ctx_details").size());
    assertFalse(fields.has("event.duration"));
    assertFalse(fields.has("http.response.status_code"));
    assertFalse(fields.has("ctx_soap_fault_code"));
    assertTrue(fields.path("response_time").isIntegralNumber());
    assertTrue(fields.path("response_time").longValue() >= 0);
    assertTrue(fields.get("ctx_details").isObject());
    if (code == null) {
      assertFalse(fields.has("http_code"));
    } else {
      assertTrue(fields.path("http_code").isIntegralNumber());
      assertEquals(code.intValue(), fields.path("http_code").intValue());
    }
    String output = fields + event.getFormattedMessage();
    assertFalse(output.contains("SECRET"));
    assertFalse(output.contains("https://"));
    assertFalse(output.contains("credential"));
    return fields;
  }

  private JsonNode encodedRecord(ILoggingEvent event) {
    StructuredEcsEncoder encoder = new StructuredEcsEncoder();
    encoder.setContext(logger.getLoggerContext());
    encoder.start();
    try {
      return new ObjectMapper().readTree(encoder.encode(event));
    } catch (IOException error) {
      throw new AssertionError("Dependency log must be valid ECS JSON", error);
    } finally {
      encoder.stop();
    }
  }

  void assertCallerUnchanged() {
    assertEquals("request-summary", MDC.get("method"));
    assertEquals("OK", MDC.get("status"));
    assertEquals("SECRET legacy arguments", MDC.get("args"));
    assertEquals("KO", MDC.get("ctx_soap_outcome"));
    assertEquals("500", MDC.get("httpCode"));
  }
}
