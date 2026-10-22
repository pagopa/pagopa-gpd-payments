package it.gov.pagopa.payments.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.LoggerFactory;

/** Freezes application events across server threads and decodes the actual ECS encoder output. */
public final class ApplicationLogCapture implements AutoCloseable {
  public final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();
  private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
  private final Level previous = root.getLevel();
  private final AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
    @Override
    protected void append(ILoggingEvent event) {
      if (event.getLoggerName().startsWith("it.gov.pagopa.payments")) {
        event.prepareForDeferredProcessing();
        events.add(event);
      }
    }
  };

  public ApplicationLogCapture() {
    appender.start();
    root.addAppender(appender);
    root.setLevel(Level.DEBUG);
  }

  public JsonNode record(ILoggingEvent event) throws Exception {
    StructuredEcsEncoder encoder = new StructuredEcsEncoder();
    encoder.setContext(root.getLoggerContext());
    encoder.start();
    try {
      return new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .readTree(encoder.encode(event));
    } finally {
      encoder.stop();
    }
  }

  @Override
  public void close() {
    root.detachAppender(appender);
    appender.stop();
    root.setLevel(previous);
  }
}
