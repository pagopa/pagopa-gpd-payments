package it.gov.pagopa.payments.controller.receipt;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.gov.pagopa.payments.model.PaymentsResult;
import it.gov.pagopa.payments.model.ProblemJson;
import it.gov.pagopa.payments.model.ReceiptModelResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.Pattern;
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
                            responseCode = "403",
                            description = "Forbidden.",
                            content = @Content(schema = @Schema(), examples = {@ExampleObject(name = "forbidden", value = """
                                    {
                                      "statusCode": 403,
                                      "message": "You are not allowed to access this resource."
                                    }""")}, mediaType = MediaType.APPLICATION_JSON_VALUE)),
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
            String iuv,
            @Valid @Parameter(description = "Segregation codes for which broker is authorized") @Pattern(regexp = "\\d{2}(,\\d{2})*")
            @RequestParam(required = false) String segregationCodes);

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
                                    schema = @Schema(implementation = PaymentsResult.class))),
                    @ApiResponse(
                            responseCode = "401",
                            description = "Wrong or missing function key.",
                            content = @Content(schema = @Schema())),
                    @ApiResponse(
                            responseCode = "403",
                            description = "Forbidden.",
                            content = @Content(schema = @Schema(), examples = {@ExampleObject(name = "forbidden", value = """
                                    {
                                      "statusCode": 403,
                                      "message": "You are not allowed to access this resource."
                                    }""")}, mediaType = MediaType.APPLICATION_JSON_VALUE)),
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
    ResponseEntity<PaymentsResult<ReceiptModelResponse>> getOrganizationReceipts(
            @Parameter(
                    description = "Organization fiscal code, the fiscal code of the Organization.",
                    required = true)
            @PathVariable("organizationfiscalcode")
            String organizationFiscalCode,
            @Parameter(description = "Page number, starts from 0") @RequestParam(required = false, defaultValue = "0") @PositiveOrZero int pageNum,
            @Valid @Parameter(description = "Number of elements per page. Default = 20") @RequestParam(required = false, defaultValue = "20") @Max(100) @Positive int pageSize,
            @Parameter(description = "Filter by debtor") @RequestParam(required = false) String debtor,
            @Parameter(description = "Filter by service") @RequestParam(required = false) String service,
            @Parameter(description = "Filter by date, from this date") @RequestParam(required = false) String from,
            @Parameter(description = "Filter by date, to this date") @RequestParam(required = false) String to,
            @Valid @Parameter(description = "Segregation codes for which broker is authorized") @Pattern(regexp = "\\d{2}(,\\d{2})*")
            @RequestParam(required = false) String segregationCodes,
            @Parameter(description = "Filter start of debtor or IUV") @RequestParam(required = false) String debtorOrIuv);
}
