package it.gov.pagopa.payments.config;

import it.gov.pagopa.payments.logging.HttpRequestLogContext;
import javax.servlet.DispatcherType;
import javax.servlet.ServletRequestEvent;
import javax.servlet.ServletRequestListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.handler.MappedInterceptor;

/** Connects request metadata to MVC and the actual servlet completion boundary. */
@Configuration
public class RequestLoggingConfiguration implements WebMvcConfigurer {

  private final RequestLoggingInterceptor interceptor;

  public RequestLoggingConfiguration(RequestLoggingInterceptor interceptor) {
    this.interceptor = interceptor;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(interceptor);
  }

  @Bean
  public MappedInterceptor livenessProbeLogging() {
    return probeLogging("/actuator/health/liveness", "liveness");
  }

  @Bean
  public MappedInterceptor readinessProbeLogging() {
    return probeLogging("/actuator/health/readiness", "readiness");
  }

  private static MappedInterceptor probeLogging(String path, String method) {
    // MappedInterceptor beans also apply to Actuator's separate handler mapping.
    return new MappedInterceptor(
        new String[] {path},
        new HandlerInterceptor() {
          @Override
          public boolean preHandle(
              HttpServletRequest request, HttpServletResponse response, Object handler) {
            if (request.getDispatcherType() != DispatcherType.ERROR) {
              HttpRequestLogContext context = HttpRequestLogContext.from(request);
              if (context != null) {
                context.setMethod(method);
              }
            }
            return true;
          }
        });
  }

  @Bean
  public FilterRegistrationBean<RequestFilter> requestLoggingFilter(RequestFilter filter) {
    FilterRegistrationBean<RequestFilter> registration = new FilterRegistrationBean<>(filter);
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    registration.setDispatcherTypes(
        DispatcherType.REQUEST, DispatcherType.ERROR, DispatcherType.ASYNC);
    registration.setAsyncSupported(true);
    return registration;
  }

  @Bean
  public ServletListenerRegistrationBean<ServletRequestListener> requestLoggingCompletion(
      RequestFilter filter) {
    return new ServletListenerRegistrationBean<>(
        new ServletRequestListener() {
          @Override
          public void requestDestroyed(ServletRequestEvent event) {
            if (event.getServletRequest() instanceof HttpServletRequest request) {
              filter.logCompletedRequest(request);
            }
          }
        });
  }
}
