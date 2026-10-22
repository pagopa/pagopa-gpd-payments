package it.gov.pagopa.payments.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import it.gov.pagopa.payments.logging.HttpRequestLogContext;
import it.gov.pagopa.payments.utils.CommonUtil;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import javax.servlet.AsyncContext;
import javax.servlet.DispatcherType;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.ErrorPage;
import org.springframework.boot.web.server.WebServer;
import org.springframework.http.HttpStatus;

class RequestLoggingContainerTest {

  private static WebServer server;
  private static final HttpClient CLIENT = HttpClient.newHttpClient();
  private RequestLogCapture capture;

  @BeforeAll
  static void startContainer() {
    RequestFilter filter = new RequestFilter();
    RequestLoggingConfiguration configuration =
        new RequestLoggingConfiguration(new RequestLoggingInterceptor());
    TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory(0);
    factory.addErrorPages(
        new ErrorPage(HttpStatus.NOT_FOUND, "/finished-error"),
        new ErrorPage(HttpStatus.INTERNAL_SERVER_ERROR, "/finished-error"));
    server = factory.getWebServer(context -> {
      configuration.requestLoggingFilter(filter).onStartup(context);
      configuration.requestLoggingCompletion(filter).onStartup(context);
      javax.servlet.ServletRegistration.Dynamic servlet =
          context.addServlet("test", new TestServlet());
      servlet.addMapping("/");
      servlet.setAsyncSupported(true);
    });
    server.start();
  }

  @AfterAll
  static void stopContainer() {
    if (server != null) {
      server.stop();
    }
  }

  @BeforeEach
  void setUp() {
    capture = new RequestLogCapture();
  }

  @AfterEach
  void tearDown() {
    capture.close();
  }

  @Test
  void containerInvokesCompletionExactlyOnceOnSuccess() throws Exception {
    HttpResponse<String> response = get("/ok");

    assertEquals(201, response.statusCode());
    assertEquals("request-container", response.headers().firstValue("X-Request-Id").orElseThrow());
    JsonNode record = capture.singleSummary();
    assertEquals("request-container", record.path("correlation_id").asText());
    assertEquals("testOperation", record.path("method").asText());
    assertEquals(201, record.path("http.response.status_code").intValue());
    assertEquals("ACA", record.path("args").path("serviceType").textValue());
    assertEquals("OK", record.path("status").asText());
  }

  @Test
  void escapedExceptionIsLoggedAfterTheActual500ErrorPage() throws Exception {
    HttpResponse<String> response = get("/throw");

    assertEquals(500, response.statusCode());
    assertEquals("error response", response.body());
    JsonNode record = capture.singleSummary();
    assertEquals(500, record.path("http.response.status_code").intValue());
    assertEquals("ACA", record.path("args").path("serviceType").textValue());
    assertEquals("KO", record.path("status").asText());
    assertEquals("testOperation", record.path("method").asText());
  }

  @Test
  void sendErrorUsesTheFinalErrorPageStatusNotTheProvisional404() throws Exception {
    HttpResponse<String> response = get("/send-error");

    assertEquals(422, response.statusCode());
    JsonNode record = capture.singleSummary();
    assertEquals(422, record.path("http.response.status_code").intValue());
    assertEquals("ACA", record.path("args").path("serviceType").textValue());
    assertEquals("KO", record.path("status").asText());
    assertEquals("testOperation", record.path("method").asText());
  }

  @Test
  void asyncCompletionProducesOneSummaryWithTheFinalStatus() throws Exception {
    HttpResponse<String> response = get("/async");

    assertEquals(202, response.statusCode());
    assertEquals("accepted", response.body());
    JsonNode record = capture.singleSummary();
    assertEquals(202, record.path("http.response.status_code").intValue());
    assertEquals("ACA", record.path("args").path("serviceType").textValue());
    assertEquals("OK", record.path("status").asText());
    assertEquals("request-container", record.path("correlation_id").asText());
  }

  private HttpResponse<String> get(String path) throws Exception {
    return CLIENT.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + server.getPort() + path))
            .header("X-Request-Id", "request-container")
            .header(CommonUtil.SERVICE_TYPE_HEADER, "ACA")
            .GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static class TestServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
        throws IOException, ServletException {
      if (request.getDispatcherType() == DispatcherType.REQUEST) {
        HttpRequestLogContext.from(request).setMethod("testOperation");
      }
      switch (request.getRequestURI()) {
        case "/throw" -> throw new ServletException("test failure");
        case "/send-error" -> response.sendError(404);
        case "/finished-error" -> {
          int originalStatus = (Integer) request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
          response.setStatus(originalStatus == 404 ? 422 : 500);
          response.getWriter().write("error response");
        }
        case "/async" -> {
          AsyncContext async = request.startAsync();
          async.start(() -> {
            try {
              response.setStatus(202);
              response.getWriter().write("accepted");
            } catch (IOException failure) {
              throw new IllegalStateException(failure);
            } finally {
              async.complete();
            }
          });
        }
        default -> {
          response.setStatus(201);
          response.getWriter().write("created");
        }
      }
    }
  }
}
