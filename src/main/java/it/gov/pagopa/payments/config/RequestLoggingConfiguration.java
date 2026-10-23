package it.gov.pagopa.payments.config;

import javax.servlet.DispatcherType;
import javax.servlet.ServletRequestEvent;
import javax.servlet.ServletRequestListener;
import javax.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

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
