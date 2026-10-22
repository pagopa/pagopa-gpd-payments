package it.gov.pagopa.payments.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.gov.pagopa.payments.logging.StructuredEcsEncoder;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.LoggerFactory;

/** Captures frozen request summaries, including those emitted on container completion threads. */
public final class RequestLogCapture implements AutoCloseable {

  private final Logger logger = (Logger) LoggerFactory.getLogger(RequestFilter.class);
  private final Level previousLevel = logger.getLevel();
  private final CountDownLatch completed = new CountDownLatch(1);
  private final ListAppender<ILoggingEvent> appender =
      new ListAppender<>() {
        @Override
        protected void append(ILoggingEvent event) {
          event.prepareForDeferredProcessing();
          super.append(event);
          if ("HTTP request completed".equals(event.getMessage())) {
            completed.countDown();
          }
        }
      };

  public RequestLogCapture() {
    appender.list = new CopyOnWriteArrayList<>();
    appender.start();
    logger.setLevel(Level.INFO);
    logger.addAppender(appender);
  }

  public List<ILoggingEvent> summaries() {
    return appender.list.stream()
        .filter(event -> "HTTP request completed".equals(event.getMessage()))
        .collect(Collectors.toList());
  }

  public JsonNode singleSummary() throws Exception {
    assertTrue(completed.await(5, TimeUnit.SECONDS), "Missing request completion log");
    assertEquals(1, summaries().size());
    return record(summaries().get(0));
  }

  JsonNode record(ILoggingEvent event) throws Exception {
    LoggerContext context = new LoggerContext();
    StructuredEcsEncoder encoder = new StructuredEcsEncoder();
    encoder.setContext(context);
    encoder.start();
    try {
      return new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .readTree(encoder.encode(event));
    } finally {
      encoder.stop();
      context.stop();
    }
  }

  @Override
  public void close() {
    logger.detachAppender(appender);
    logger.setLevel(previousLevel);
    appender.stop();
  }
}
