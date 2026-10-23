package it.gov.pagopa.payments.logging;

import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpPipelineCallContext;
import com.azure.core.http.HttpPipelineNextPolicy;
import com.azure.core.http.HttpPipelineNextSyncPolicy;
import com.azure.core.http.HttpPipelinePosition;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.azure.core.http.policy.HttpPipelinePolicy;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.Map;
import org.slf4j.MDC;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Builder-installed observer, positioned inside the SDK retry policy. Does not consume bodies. */
public final class AzureStorageLoggingPolicy implements HttpPipelinePolicy {
  private static final String CALLER_CONTEXT_KEY = AzureStorageLoggingPolicy.class.getName() + ".mdc";
  private static final String AZURE_TABLES = "azure-tables";
  private static final String AZURE_QUEUE = "azure-queue";
  private static final String AZURE_BLOB = "azure-blob";
  private static final String DELETE = "DELETE";
  private static final String DEFAULT_OPERATION = "request";
  private final String dependency;

  public AzureStorageLoggingPolicy(String dependency) {
    switch (dependency) {
      case AZURE_TABLES:
        this.dependency = AZURE_TABLES;
        break;
      case AZURE_QUEUE, "azure-storage-queue":
        this.dependency = AZURE_QUEUE;
        break;
      case AZURE_BLOB, "azure-storage-blob":
        this.dependency = AZURE_BLOB;
        break;
      default:
        throw new IllegalArgumentException("Unsupported Azure logging dependency");
    }
  }

  @Override
  public HttpPipelinePosition getPipelinePosition() {
    return HttpPipelinePosition.PER_RETRY;
  }

  @Override
  public Mono<HttpResponse> process(
      HttpPipelineCallContext context, HttpPipelineNextPolicy next) {
    Map<String, String> caller = callerContext(context);
    return Mono.defer(() -> {
      DependencyLogCall call = newCall(context.getHttpRequest(), caller);
      return Mono.defer(next::process)
          .map(response -> observe(response, call))
          .doOnSuccess(response -> {
            if (response == null) {
              call.complete(true);
            }
          })
          .doOnError(error -> call.complete(true))
          .doOnCancel(() -> call.complete(true));
    });
  }

  @Override
  public HttpResponse processSync(
      HttpPipelineCallContext context, HttpPipelineNextSyncPolicy next) {
    DependencyLogCall call = newCall(context.getHttpRequest(), callerContext(context));
    try {
      return observe(next.processSync(), call);
    } catch (RuntimeException error) {
      call.complete(true);
      throw error;
    }
  }

  private static Map<String, String> callerContext(HttpPipelineCallContext context) {
    Object existing = context.getData(CALLER_CONTEXT_KEY).orElse(null);
    if (existing instanceof CallerContext caller) {
      return caller.values();
    }
    Map<String, String> current = MDC.getCopyOfContextMap();
    CallerContext caller = new CallerContext(current == null ? Map.of() : Map.copyOf(current));
    // Retry clones share the call context even when their delay resumes on another thread.
    context.setData(CALLER_CONTEXT_KEY, caller);
    return caller.values();
  }

  private record CallerContext(Map<String, String> values) {}

  private DependencyLogCall newCall(HttpRequest request, Map<String, String> caller) {
    String operation = switch (dependency) {
      case AZURE_TABLES -> tableOperation(request);
      case AZURE_QUEUE -> queueOperation(request);
      default -> blobOperation(request);
    };
    return new DependencyLogCall(dependency, dependency + "." + operation, operation, caller);
  }

  private static String tableOperation(HttpRequest request) {
    String verb = request.getHttpMethod().name();
    String path = request.getUrl().getPath().toLowerCase(Locale.ROOT);
    return switch (verb) {
      case "GET" -> path.contains("(") || path.contains("%28") ? "getEntity" : "listEntities";
      case "POST" -> "createEntity";
      case "PUT", "MERGE", "PATCH" -> "updateEntity";
      case DELETE -> "deleteEntity";
      default -> DEFAULT_OPERATION;
    };
  }

  private static String queueOperation(HttpRequest request) {
    return switch (request.getHttpMethod().name()) {
      case "POST" -> "sendMessage";
      case "GET" -> "receiveMessage";
      case "PUT" -> "updateMessage";
      case DELETE -> "deleteMessage";
      default -> DEFAULT_OPERATION;
    };
  }

  private static String blobOperation(HttpRequest request) {
    return switch (request.getHttpMethod().name()) {
      case "GET" -> {
        String query = request.getUrl().getQuery();
        yield query != null && hasListComponent(query) ? "list" : "download";
      }
      case "PUT", "POST" -> "upload";
      case DELETE -> "delete";
      default -> DEFAULT_OPERATION;
    };
  }

  private static boolean hasListComponent(String query) {
    for (String parameter : query.split("&")) {
      if ("comp=list".equalsIgnoreCase(parameter)) {
        return true;
      }
    }
    return false;
  }

  private static HttpResponse observe(HttpResponse response, DependencyLogCall call) {
    call.response(response.getStatusCode());
    return new HttpResponse(response.getRequest()) {
      @Override
      public int getStatusCode() {
        return response.getStatusCode();
      }

      @Override
      public String getHeaderValue(String name) {
        return response.getHeaderValue(name);
      }

      @Override
      public HttpHeaders getHeaders() {
        return response.getHeaders();
      }

      @Override
      public Flux<ByteBuffer> getBody() {
        return Flux.defer(response::getBody)
            .doOnComplete(() -> call.complete(false))
            .doOnError(error -> call.complete(true))
            .doOnCancel(() -> call.complete(true));
      }

      @Override
      public Mono<byte[]> getBodyAsByteArray() {
        return observeBody(Mono.defer(response::getBodyAsByteArray), call);
      }

      @Override
      public Mono<String> getBodyAsString() {
        return observeBody(Mono.defer(response::getBodyAsString), call);
      }

      @Override
      public Mono<String> getBodyAsString(Charset charset) {
        return observeBody(Mono.defer(() -> response.getBodyAsString(charset)), call);
      }

      @Override
      public Mono<InputStream> getBodyAsInputStream() {
        return Mono.defer(response::getBodyAsInputStream)
            .map(call::observe)
            .doOnSuccess(stream -> {
              if (stream == null) {
                call.complete(false);
              }
            })
            .doOnError(error -> call.complete(true))
            .doOnCancel(() -> call.complete(true));
      }

      @Override
      public void close() {
        try {
          response.close();
          call.complete(false);
        } catch (RuntimeException error) {
          call.complete(true);
          throw error;
        }
      }
    };
  }

  private static <T> Mono<T> observeBody(Mono<T> body, DependencyLogCall call) {
    return body.doOnSuccess(ignored -> call.complete(false))
        .doOnError(error -> call.complete(true))
        .doOnCancel(() -> call.complete(true));
  }
}
