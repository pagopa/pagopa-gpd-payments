package it.gov.pagopa.payments.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.gov.pagopa.payments.exception.DeadLetterAccessException;
import it.gov.pagopa.payments.exception.DeadLetterNotFoundException;
import it.gov.pagopa.payments.model.DeadLetterMessage;
import it.gov.pagopa.payments.model.DeadLetterMessageSummary;
import it.gov.pagopa.payments.service.DeadLetterService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/error-messages")
@RequiredArgsConstructor
@Tag(name = "Dead Letter", description = "Read-only operations for inspecting terminally failed retry messages.")
@SecurityRequirement(name = "ApiKey")
public class DeadLetterController {

	static final int DEFAULT_MAX_MESSAGES = 50;
	static final int MAX_MESSAGES = 100;

	private final DeadLetterService deadLetterService;

	@Operation(summary = "List dead-letter messages", description = "Returns dead-letter message metadata without exposing "
			+ "the original paSendRT payload.")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Dead-letter messages retrieved successfully.", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = DeadLetterMessageSummary.class)))),
			@ApiResponse(responseCode = "400", description = "Invalid maxMessages parameter.", content = @Content),
			@ApiResponse(responseCode = "500", description = "Dead-letter storage unavailable.", content = @Content) })
	@GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<List<DeadLetterMessageSummary>> getDeadLetters(
			@Parameter(description = "Maximum number of dead-letter messages to return.", schema = @Schema(defaultValue = "50", minimum = "1", maximum = "100")) 
			@RequestParam(name = "maxMessages", defaultValue = "50") int maxMessages) {

		validateMaxMessages(maxMessages);

		try {
			return ResponseEntity.ok(deadLetterService.getDeadLetters(maxMessages));

		} catch (DeadLetterAccessException e) {
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to access dead-letter storage",
					e);
		}
	}

	@Operation(summary = "Get dead-letter message detail", description = "Returns a complete dead-letter message, including the original "
			+ "paSendRT XML payload.")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Dead-letter message retrieved successfully.", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = DeadLetterMessage.class))),
			@ApiResponse(responseCode = "400", description = "Invalid or missing filename.", content = @Content),
			@ApiResponse(responseCode = "404", description = "Dead-letter message not found.", content = @Content),
			@ApiResponse(responseCode = "500", description = "Dead-letter storage unavailable.", content = @Content) })
	@GetMapping(value = "/detail", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<DeadLetterMessage> getDeadLetter(
			@Parameter(description = "Full Blob Storage file name returned by the list operation.", example = "2026/08/31/10/message-1/"
					+ "MAX_RETRY_ATTEMPTS_REACHED_1000.json", required = true) @RequestParam(name = "filename") String fileName) {

		validateFileName(fileName);

		try {
			return ResponseEntity.ok(deadLetterService.getDeadLetter(fileName));

		} catch (DeadLetterNotFoundException e) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dead-letter message not found", e);

		} catch (DeadLetterAccessException e) {
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to access dead-letter storage",
					e);
		}
	}

	private void validateMaxMessages(int maxMessages) {

		if (maxMessages < 1 || maxMessages > MAX_MESSAGES) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"'maxMessages' must be between 1 and " + MAX_MESSAGES);
		}
	}

	private void validateFileName(String fileName) {

		if (fileName == null || fileName.isBlank() || !fileName.endsWith(".json")) {

			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"'filename' must identify a JSON dead-letter message");
		}
	}
}