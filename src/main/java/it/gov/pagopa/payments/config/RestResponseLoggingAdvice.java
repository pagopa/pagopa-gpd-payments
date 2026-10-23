package it.gov.pagopa.payments.config;

import it.gov.pagopa.payments.logging.HttpRequestLogContext;
import it.gov.pagopa.payments.model.ProblemJson;
import java.util.Map;
import org.springframework.boot.autoconfigure.web.servlet.error.BasicErrorController;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Observes only approved error metadata without buffering or changing REST response bodies. */
@ControllerAdvice
public class RestResponseLoggingAdvice implements ResponseBodyAdvice<Object> {

  @Override
  public boolean supports(
      MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
    return true;
  }

  @Override
  public Object beforeBodyWrite(
      Object body,
      MethodParameter returnType,
      MediaType selectedContentType,
      Class<? extends HttpMessageConverter<?>> selectedConverterType,
      ServerHttpRequest request,
      ServerHttpResponse response) {
    if (!(request instanceof ServletServerHttpRequest servletRequest)
        || !(response instanceof ServletServerHttpResponse servletResponse)
        || servletResponse.getServletResponse().getStatus() < 400) {
      return body;
    }
    HttpRequestLogContext context =
        HttpRequestLogContext.from(servletRequest.getServletRequest());
    if (context == null) {
      return body;
    }
    if (body instanceof ProblemJson problem) {
      context.recordRestFaultLog(problem.getTitle(), problem.getDetail());
    } else if (BasicErrorController.class.isAssignableFrom(returnType.getContainingClass())
        && body instanceof Map<?, ?> error) {
      context.recordRestFaultLog(text(error.get("error")), text(error.get("message")));
    }
    return body;
  }

  private static String text(Object value) {
    return value instanceof String message && !message.isEmpty() ? message : null;
  }
}
