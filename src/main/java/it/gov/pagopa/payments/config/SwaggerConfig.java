package it.gov.pagopa.payments.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.servers.ServerVariable;
import io.swagger.v3.oas.models.servers.ServerVariables;
import java.util.List;
import java.util.Map;
import org.springdoc.core.GroupedOpenApi;
import org.springdoc.core.customizers.OpenApiCustomiser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;

@Configuration
public class SwaggerConfig {

  public static final String HEADER_REQUEST_ID = "X-Request-Id";

  @Bean
  public OpenAPI customOpenAPI(
      @Value("${info.application.description}") String appDescription,
      @Value("${info.application.version}") String appVersion) {
    return new OpenAPI()
        .components(
            new Components()
                .addSecuritySchemes(
                    "ApiKey",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .description("The API key to access this function app.")
                        .name("Ocp-Apim-Subscription-Key")
                        .in(SecurityScheme.In.HEADER)))
        .info(
            new Info()
                .title("PagoPA API Payments")
                .version(appVersion)
                .description(appDescription)
                .termsOfService("https://www.pagopa.gov.it/"));
  }

  @Bean
  public OpenApiCustomiser sortOperationsAlphabetically() {
    return openApi -> {
      Paths paths =
          openApi.getPaths().entrySet().stream()
              .sorted(Map.Entry.comparingByKey())
              .collect(
                  Paths::new,
                  (map, item) -> map.addPathItem(item.getKey(), item.getValue()),
                  Paths::putAll);
      paths.forEach(
          (key, value) ->
              value
                  .readOperations()
                  .forEach(
                      operation -> {
                        var responses =
                            operation.getResponses().entrySet().stream()
                                .sorted(Map.Entry.comparingByKey())
                                .collect(
                                    ApiResponses::new,
                                    (map, item) ->
                                        map.addApiResponse(item.getKey(), item.getValue()),
                                    ApiResponses::putAll);
                        operation.setResponses(responses);
                      }));
      openApi.setPaths(paths);
    };
  }

  @Bean
  public OpenApiCustomiser addCommonHeaders() {
    return openApi ->
        openApi
            .getPaths()
            .forEach(
                (key, value) -> {

                  // add Request-ID as request header
                  value.addParametersItem(
                      new Parameter()
                          .in("header")
                          .name(HEADER_REQUEST_ID)
                          .schema(new StringSchema())
                          .description(
                              "This header identifies the call, if not passed it is self-generated."
                                  + " This ID is returned in the response."));

                  // add Request-ID as response header
                  value
                      .readOperations()
                      .forEach(
                          operation ->
                              operation
                                  .getResponses()
                                  .values()
                                  .forEach(
                                      response ->
                                          response.addHeaderObject(
                                              HEADER_REQUEST_ID,
                                              new Header()
                                                  .schema(new StringSchema())
                                                  .description(
                                                      "This header identifies the call"))));
                });
  }

  @Bean
  GroupedOpenApi externalOpenApi() {
    return GroupedOpenApi.builder()
        .group("external")
        .pathsToMatch("/info", "/payments/**")
        .addOpenApiCustomiser(customizeServer(createServers("gpd/payments-receipts-service")))
        .addOpenApiCustomiser(addCommonHeaders())
        .addOpenApiCustomiser(sortOperationsAlphabetically())
        .build();
  }

  @Bean
  GroupedOpenApi helpdeskOpenApi() {
    return GroupedOpenApi.builder()
        .group("helpdesk")
        .pathsToMatch("/error-messages", "/error-messages/**")
        .addOpenApiCustomiser(customizeServer(createServers("gpd-payments-helpdesk")))
        .addOpenApiCustomiser(addCommonHeaders())
        .addOpenApiCustomiser(sortOperationsAlphabetically())
        .build();
  }

  private OpenApiCustomiser customizeServer(List<Server> serverInfo) {
    return openApi -> {
      if (openApi.getPaths() == null) return;

      // set servers
      openApi.setServers(serverInfo);
    };
  }

  private @NonNull List<Server> createServers(String service) {
    String localPath = String.format("%s://%s:%s", "http", "localhost", 8080);
    return List.of(
        new Server().url(localPath),
        new Server()
            .url("https://{host}/{basePath}/{version}")
            .variables(
                new ServerVariables()
                    .addServerVariable(
                        "host",
                        new ServerVariable()
                            ._enum(
                                List.of(
                                    "api.dev.platform.pagopa.it",
                                    "api.uat.platform.pagopa.it",
                                    "api.platform.pagopa.it"))
                            ._default("api.dev.platform.pagopa.it"))
                    .addServerVariable("basePath", new ServerVariable()._default(service))
                    .addServerVariable("version", new ServerVariable()._default("v1"))));
  }
}
