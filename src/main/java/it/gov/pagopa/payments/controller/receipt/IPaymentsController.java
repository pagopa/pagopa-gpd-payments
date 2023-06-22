package it.gov.pagopa.payments.controller.receipt;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.gov.pagopa.payments.model.ProblemJson;
import it.gov.pagopa.payments.model.ReceiptsInfo;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import javax.validation.constraints.Positive;
import javax.validation.constraints.PositiveOrZero;

@Tag(name = "Payments receipts API")
@RequestMapping
@Validated
public interface IPaymentsController {

    @Operation(
            summary = "Return the details of a specific receipt.",
            security = {
                    @SecurityRequirement(name = "ApiKey"),
                    @SecurityRequirement(name = "Authorization")
            },
            operationId = "getReceiptByIUV")
    @ApiResponses(
            value = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Obtained receipt details.",
                            content =
                            @Content(
                                    mediaType = MediaType.APPLICATION_XML_VALUE,
                                    schema = @Schema(name = "ReceiptResponse", implementation = String.class))),
                    @ApiResponse(
                            responseCode = "401",
                            description = "Wrong or missing function key.",
                            content = @Content(schema = @Schema())),
                    @ApiResponse(
                            responseCode = "404",
                            description = "No receipt found.",
                            content = @Content(schema = @Schema(implementation = ProblemJson.class))),
                    @ApiResponse(
                            responseCode = "422",
                            description = "Unable to process the request.",
                            content = @Content(schema = @Schema(implementation = ProblemJson.class))),
                    @ApiResponse(
                            responseCode = "500",
                            description = "Service unavailable.",
                            content =
                            @Content(
                                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                                    schema = @Schema(implementation = ProblemJson.class)))
            })
    @GetMapping(
            value = "/payments/{organizationfiscalcode}/receipts/{iuv}",
            produces = {MediaType.APPLICATION_XML_VALUE, MediaType.APPLICATION_JSON_VALUE})
    ResponseEntity<String> getReceiptByIUV(
            @Parameter(
                    description = "Organization fiscal code, the fiscal code of the Organization.",
                    required = true, example = "12345")
            @PathVariable("organizationfiscalcode")
            String organizationFiscalCode,
            @Parameter(
                    description =
                            "IUV (Unique Payment Identification). Alphanumeric code that uniquely associates"
                                    + " and identifies three key elements of a payment: reason, payer, amount",
                    required = true, example = "ABC123")
            @PathVariable("iuv")
            String iuv);

  @Operation(
      summary = "Return the list of the organization receipts.",
      security = {
        @SecurityRequirement(name = "ApiKey"),
        @SecurityRequirement(name = "Authorization")
      },
      operationId = "getOrganizationReceipts")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            description = "Obtained all organization payment positions.",
            content =
                @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ReceiptsInfo.class))),
        @ApiResponse(
            responseCode = "401",
            description = "Wrong or missing function key.",
            content = @Content(schema = @Schema())),
        @ApiResponse(
            responseCode = "404",
            description = "No receipts found.",
            content = @Content(schema = @Schema(implementation = ProblemJson.class))),
        @ApiResponse(
            responseCode = "500",
            description = "Service unavailable.",
            content =
                @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProblemJson.class)))
      })
  @GetMapping(
      value = "/payments/{organizationfiscalcode}/receipts",
      produces = {MediaType.APPLICATION_JSON_VALUE})
  ResponseEntity<ReceiptsInfo> getOrganizationReceipts(
      @Parameter(
              description = "Organization fiscal code, the fiscal code of the Organization.",
              required = true)
          @PathVariable("organizationfiscalcode")
          String organizationFiscalCode,
      @Parameter(description = "Page number") @RequestParam(required = true) @PositiveOrZero int pageNum,
      @Parameter(description = "Number of elements per page") @RequestParam(required = true) @Positive int pageSize,
      @Parameter(description = "Filter by debtor") @RequestParam(required = false) String debtor,
      @Parameter(description = "Filter by service") @RequestParam(required = false) String service,
      @Parameter(description = "Filter by date, from this date") @RequestParam(required = false) String from,
      @Parameter(description = "Filter by date, to this date") @RequestParam(required = false) String to);

}
