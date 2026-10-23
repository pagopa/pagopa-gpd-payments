package it.gov.pagopa.payments.logging;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.FilterInputStream;
import java.io.FilterReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** One HTTP attempt; never puts dependency outcome or duration into the caller's MDC. */
final class DependencyLogCall {
  private static final Logger LOG = LoggerFactory.getLogger(DependencyLogCall.class);
  private static final Set<String> CONTEXT_FIELDS = Set.of(
      "correlation_id", "ctx_creditor_id", "ctx_notice_number", "ctx_iuv");
  private final Map<String, String> caller;
  private final String dependency;
  private final String operation;
  private final String path;
  private final long started = System.nanoTime();
  private final AtomicBoolean completed = new AtomicBoolean();
  private Integer status;

  DependencyLogCall(String dependency, String operation, String path) {
    this(dependency, operation, path, MDC.getCopyOfContextMap());
  }

  DependencyLogCall(
      String dependency, String operation, String path, Map<String, String> caller) {
    this.dependency = dependency;
    this.operation = operation;
    this.path = path;
    this.caller = new HashMap<>();
    if (caller != null) {
      caller.forEach((field, value) -> {
        if (CONTEXT_FIELDS.contains(field) && value != null) {
          this.caller.put(field, value);
        }
      });
    }
  }

  void response(int code) {
    status = code >= 100 && code <= 599 ? code : null;
  }

  @SuppressWarnings("java:S3457") // Structured arguments belong to the encoder, not message formatting.
  void complete(boolean failed) {
    if (!completed.compareAndSet(false, true)) {
      return;
    }
    ObjectNode fields = JsonNodeFactory.instance.objectNode();
    fields.put("response_time",
        TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - started)));
    if (status != null) {
      fields.put("http_code", status);
    }
    fields.putObject("ctx_details")
        .put("dependency", dependency)
        .put("path", path);
    Map<String, String> previous = MDC.getCopyOfContextMap();
    try {
      MDC.setContextMap(caller);
      MDC.put("method", operation);
      MDC.put("status", failed || status == null || status >= 400 ? "KO" : "OK");
      StructuredLogFields structured = new StructuredLogFields(fields);
      if (failed || status == null || status >= 500 || status == 408 || status == 429) {
        LOG.warn("Dependency interaction completed", structured);
      } else {
        LOG.info("Dependency interaction completed", structured);
      }
    } finally {
      if (previous == null) {
        MDC.clear();
      } else {
        MDC.setContextMap(previous);
      }
    }
  }

  InputStream observe(InputStream body) {
    return new FilterInputStream(body) {
      @Override
      public int read() throws IOException {
        try {
          int value = in.read();
          if (value == -1) {
            complete(false);
          }
          return value;
        } catch (IOException | RuntimeException error) {
          complete(true);
          throw error;
        }
      }

      @Override
      public int read(byte[] bytes, int offset, int length) throws IOException {
        try {
          int count = in.read(bytes, offset, length);
          if (count == -1) {
            complete(false);
          }
          return count;
        } catch (IOException | RuntimeException error) {
          complete(true);
          throw error;
        }
      }

      @Override
      public long skip(long count) throws IOException {
        try {
          return in.skip(count);
        } catch (IOException | RuntimeException error) {
          complete(true);
          throw error;
        }
      }

      @Override
      public void close() throws IOException {
        try {
          super.close();
          complete(false);
        } catch (IOException | RuntimeException error) {
          complete(true);
          throw error;
        }
      }
    };
  }

  Reader observe(Reader body) {
    return new FilterReader(body) {
      @Override
      public int read() throws IOException {
        try {
          int value = in.read();
          if (value == -1) {
            complete(false);
          }
          return value;
        } catch (IOException | RuntimeException error) {
          complete(true);
          throw error;
        }
      }

      @Override
      public int read(char[] chars, int offset, int length) throws IOException {
        try {
          int count = in.read(chars, offset, length);
          if (count == -1) {
            complete(false);
          }
          return count;
        } catch (IOException | RuntimeException error) {
          complete(true);
          throw error;
        }
      }

      @Override
      public long skip(long count) throws IOException {
        try {
          return in.skip(count);
        } catch (IOException | RuntimeException error) {
          complete(true);
          throw error;
        }
      }

      @Override
      public void close() throws IOException {
        try {
          super.close();
          complete(false);
        } catch (IOException | RuntimeException error) {
          complete(true);
          throw error;
        }
      }
    };
  }
}
