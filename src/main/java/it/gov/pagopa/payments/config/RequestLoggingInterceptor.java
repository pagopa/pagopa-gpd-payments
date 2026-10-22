package it.gov.pagopa.payments.config;

import it.gov.pagopa.payments.logging.HttpRequestLogContext;
import it.gov.pagopa.payments.logging.RequestLogProjection;
import javax.servlet.DispatcherType;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.transform.dom.DOMSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.server.EndpointInterceptor;
import org.springframework.ws.server.endpoint.MethodEndpoint;
import org.springframework.ws.soap.server.endpoint.annotation.SoapAction;
import org.springframework.ws.soap.SoapMessage;
import org.springframework.ws.transport.context.TransportContext;
import org.springframework.ws.transport.context.TransportContextHolder;
import org.springframework.ws.transport.http.HttpServletConnection;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Records the resolved API method before binding or XSD validation, never trusting a raw URL/header. */
@Component
@Slf4j
public class RequestLoggingInterceptor implements HandlerInterceptor, EndpointInterceptor {

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (request.getDispatcherType() != DispatcherType.ERROR
        && handler instanceof HandlerMethod method) {
      recordMethod(request, method.getMethod().getName());
      HttpRequestLogContext context = HttpRequestLogContext.from(request);
      if (context != null) {
        context.recordRequestProjectionLog(RequestLogProjection.restRequest(method, request));
      }
    }
    return true;
  }

  @Override
  public boolean handleRequest(MessageContext messageContext, Object endpoint) {
    TransportContext transport = TransportContextHolder.getTransportContext();
    if (endpoint instanceof MethodEndpoint method
        && transport != null
        && transport.getConnection() instanceof HttpServletConnection connection) {
      SoapAction annotation = method.getMethod().getAnnotation(SoapAction.class);
      String operation = annotation == null ? method.getMethod().getName() : annotation.value();
      HttpServletRequest request = connection.getHttpServletRequest();
      recordMethod(request, operation);
      HttpRequestLogContext context = HttpRequestLogContext.from(request);
      if (context != null && messageContext.getRequest() instanceof SoapMessage message
          && message.getSoapBody().getPayloadSource() instanceof DOMSource source
          && source.getNode() instanceof Element payload) {
        context.recordRequestProjectionLog(RequestLogProjection.soapRequest(operation, payload));
      }
    }
    return true;
  }

  private void recordMethod(HttpServletRequest request, String method) {
    HttpRequestLogContext context = HttpRequestLogContext.from(request);
    if (context != null) {
      context.setMethod(method);
    }
  }

  @Override
  public boolean handleResponse(MessageContext messageContext, Object endpoint) {
    return true;
  }

  @Override
  public boolean handleFault(MessageContext messageContext, Object endpoint) {
    return true;
  }

  @Override
  public void afterCompletion(MessageContext messageContext, Object endpoint, Exception exception) {
    TransportContext transport = TransportContextHolder.getTransportContext();
    if (exception != null || !messageContext.hasResponse() || transport == null
        || !(transport.getConnection() instanceof HttpServletConnection connection)) {
      return;
    }
    HttpRequestLogContext context =
        HttpRequestLogContext.from(connection.getHttpServletRequest());
    if (context == null || !(messageContext.getResponse() instanceof SoapMessage response)) {
      return;
    }
    if (response.getSoapBody().hasFault()) {
      context.recordSoapResponseLog(
          null, response.getSoapBody().getFault().getFaultCode().toString());
    } else if (response.getSoapBody().getPayloadSource() instanceof DOMSource source
        && source.getNode() instanceof Element payload) {
      String outcome = childText(payload, "outcome");
      Element fault = child(payload, "fault");
      context.recordSoapResponseLog(
          "OK".equals(outcome) || "KO".equals(outcome) ? outcome : null,
          fault == null ? null : childText(fault, "faultCode"));
      context.recordResponseIuvLog(RequestLogProjection.soapResponseIuv(payload));
    } else {
      log.warn("SOAP response logging requires a DOM payload");
    }
  }

  private static Element child(Element parent, String name) {
    for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (node instanceof Element element && name.equals(element.getLocalName())) {
        return element;
      }
    }
    return null;
  }

  private static String childText(Element parent, String name) {
    Element element = child(parent, name);
    return element == null ? null : element.getTextContent();
  }
}
