package it.gov.pagopa.payments.controller;

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
public class DeadLetterController {

    static final int DEFAULT_MAX_MESSAGES = 50;
    static final int MAX_MESSAGES = 100;

    private final DeadLetterService deadLetterService;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<DeadLetterMessageSummary>> getDeadLetters(
            @RequestParam(
                    name = "maxMessages",
                    defaultValue = "50")
            int maxMessages) {

        validateMaxMessages(maxMessages);

        return ResponseEntity.ok(
                deadLetterService.getDeadLetters(maxMessages));
    }

    @GetMapping(
            value = "/detail",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DeadLetterMessage> getDeadLetter(
            @RequestParam(name = "filename") String fileName) {

        validateFileName(fileName);

        return ResponseEntity.ok(
                deadLetterService.getDeadLetter(fileName));
    }

    private void validateMaxMessages(int maxMessages) {

        if (maxMessages < 1 || maxMessages > MAX_MESSAGES) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "'maxMessages' must be between 1 and " + MAX_MESSAGES);
        }
    }

    private void validateFileName(String fileName) {

        if (fileName == null
                || fileName.isBlank()
                || !fileName.endsWith(".json")) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "'filename' must identify a JSON dead-letter message");
        }
    }
}