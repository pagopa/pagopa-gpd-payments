package it.gov.pagopa.payments.config;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.LoggerFactory;

/** Captures the actual encoded output without changing the root logger. */
public final class LogCapture implements AutoCloseable {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final Logger logger;
  private final Level previousLevel;
  private final boolean previousAdditive;
  private final JsonLogEncoder encoder = new JsonLogEncoder();
  private final List<JsonNode> events = new CopyOnWriteArrayList<>();
  private final List<String> lines = new CopyOnWriteArrayList<>();
  private final AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
    @Override
    protected void append(ILoggingEvent event) {
      String line = new String(encoder.encode(event), StandardCharsets.UTF_8);
      try {
        events.add(JSON.readTree(line));
        lines.add(line);
      } catch (IOException failure) {
        throw new AssertionError("Invalid JSON log", failure);
      }
    }
  };

  public LogCapture(Class<?> source) {
    logger = (Logger) LoggerFactory.getLogger(source);
    previousLevel = logger.getLevel();
    previousAdditive = logger.isAdditive();
    encoder.setContext(logger.getLoggerContext());
    encoder.setServiceName("logging-test");
    encoder.start();
    appender.setContext(logger.getLoggerContext());
    appender.start();
    logger.setLevel(Level.TRACE);
    logger.setAdditive(false);
    logger.addAppender(appender);
  }

  public List<JsonNode> events() {
    return List.copyOf(events);
  }

  public List<String> lines() {
    return List.copyOf(lines);
  }

  public static JsonNode dependency(JsonNode event) {
    JsonNode value = event.path(LogContext.CTX_DETAILS).path(LogContext.DEPENDENCY);
    assertTrue(value.isTextual(), "Dependency must be a JSON string");
    JsonNode decoded = assertDoesNotThrow(() -> JSON.readTree(value.textValue()));
    assertTrue(decoded.isObject(), "Dependency string must contain a JSON object");
    assertFalse(event.path(LogContext.CTX_DETAILS).has(LogContext.PATH));
    return decoded;
  }

  @Override
  public void close() {
    logger.detachAppender(appender);
    appender.stop();
    encoder.stop();
    logger.setLevel(previousLevel);
    logger.setAdditive(previousAdditive);
  }
}
