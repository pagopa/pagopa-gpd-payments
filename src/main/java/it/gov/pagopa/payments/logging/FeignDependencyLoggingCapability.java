package it.gov.pagopa.payments.logging;

import feign.Capability;
import feign.Client;
import feign.MethodMetadata;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.Charset;

/** Decorates the configured client, leaving authentication, retries and decoding to Feign. */
public final class FeignDependencyLoggingCapability implements Capability {
  @Override
  public Client enrich(Client client) {
    return (request, options) -> {
      DependencyLogCall call = newCall(request);
      try {
        Response response = client.execute(request, options);
        call.response(response.status());
        if (response.body() == null) {
          call.complete(false);
          return response;
        }
        return response.toBuilder().body(new LoggingBody(response.body(), call)).build();
      } catch (IOException | RuntimeException error) {
        call.complete(true);
        throw error;
      }
    };
  }

  private static DependencyLogCall newCall(Request request) {
    RequestTemplate template = request.requestTemplate();
    MethodMetadata metadata = template == null ? null : template.methodMetadata();
    if (metadata != null && template.feignTarget() != null && metadata.method() != null) {
      String type = template.feignTarget().type().getSimpleName();
      String method = metadata.method().getName();
      if (("GpdClient".equals(type)
              && ("getPaymentOption".equals(method) || "sendPaymentOptionReceipt".equals(method)))
          || ("ApiConfigCacheClient".equals(type) && "getCacheByKeys".equals(method))) {
        return new DependencyLogCall(
            "GpdClient".equals(type) ? "gpd" : "api-config-cache",
            type + "#" + method,
            metadata.template().path());
      }
    }
    return new DependencyLogCall("feign", "feign.request", "unknown");
  }

  private static final class LoggingBody implements Response.Body {
    private final Response.Body delegate;
    private final DependencyLogCall call;

    private LoggingBody(Response.Body delegate, DependencyLogCall call) {
      this.delegate = delegate;
      this.call = call;
    }

    @Override
    public Integer length() {
      return delegate.length();
    }

    @Override
    public boolean isRepeatable() {
      return delegate.isRepeatable();
    }

    @Override
    public InputStream asInputStream() throws IOException {
      try {
        return call.observe(delegate.asInputStream());
      } catch (IOException | RuntimeException error) {
        call.complete(true);
        throw error;
      }
    }

    @Override
    public Reader asReader(Charset charset) throws IOException {
      try {
        return call.observe(delegate.asReader(charset));
      } catch (IOException | RuntimeException error) {
        call.complete(true);
        throw error;
      }
    }

    @Override
    public void close() throws IOException {
      try {
        delegate.close();
        call.complete(false);
      } catch (IOException | RuntimeException error) {
        call.complete(true);
        throw error;
      }
    }
  }
}
