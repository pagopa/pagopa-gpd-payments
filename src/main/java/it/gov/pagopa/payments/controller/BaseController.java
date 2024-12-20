package it.gov.pagopa.payments.controller;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import it.gov.pagopa.payments.model.AppInfo;
import it.gov.pagopa.payments.model.ProblemJson;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.result.view.RedirectView;

@RestController()
public class BaseController {
  @Value("${info.application.name}")
  private String name;

  @Value("${info.application.version}")
  private String version;

  @Value("${info.properties.environment}")
  private String environment;

  @Value("${server.servlet.context-path:/}")
  private String basePath;

  /**
   * @return
   * @return 200 OK
   */
  @Hidden
  @GetMapping("")
  @ResponseStatus(HttpStatus.OK)
  public RedirectView home() {
    if (!basePath.endsWith("/")) {
      basePath += "/";
    }
    return new RedirectView(basePath + "swagger-ui/index.html");
  }

  @Operation(
    summary = "health check",
    description = "Return OK if application is started",
    security = {
      @SecurityRequirement(name = "ApiKey"),
      @SecurityRequirement(name = "Authorization")
    },
    tags = {"Home"}
  )
  @ApiResponses(
    value = {
      @ApiResponse(
        responseCode = "200",
        description = "OK",
        content =
            @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = AppInfo.class)
            )
      ),
      @ApiResponse(
        responseCode = "400",
        description = "Bad Request",
        content =
            @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ProblemJson.class)
            )
      ),
      @ApiResponse(
        responseCode = "401",
        description = "Unauthorized",
        content = @Content(schema = @Schema())
      ),
      @ApiResponse(
        responseCode = "403",
        description = "Forbidden",
        content = @Content(schema = @Schema())
      ),
      @ApiResponse(
        responseCode = "429",
        description = "Too many requests",
        content = @Content(schema = @Schema())
      ),
      @ApiResponse(
        responseCode = "500",
        description = "Service unavailable",
        content =
            @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ProblemJson.class)
            )
      )
    }
  )
  @GetMapping(
    value = "/info",
    produces = {MediaType.APPLICATION_JSON_VALUE}
  )
  @ResponseStatus(HttpStatus.OK)
  public ResponseEntity<AppInfo> healthCheck() {
    // Used just for health checking
    AppInfo info = AppInfo.builder().name(name).version(version).environment(environment).build();
    return ResponseEntity.status(HttpStatus.OK).body(info);
  }
}
