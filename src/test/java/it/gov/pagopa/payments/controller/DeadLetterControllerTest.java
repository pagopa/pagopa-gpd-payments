package it.gov.pagopa.payments.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.gov.pagopa.payments.model.DeadLetterMessage;
import it.gov.pagopa.payments.model.DeadLetterMessageSummary;
import it.gov.pagopa.payments.model.enumeration.DeadLetterReason;
import it.gov.pagopa.payments.service.DeadLetterService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DeadLetterControllerTest {

    private DeadLetterService deadLetterService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {

        deadLetterService =
                mock(DeadLetterService.class);

        DeadLetterController controller =
                new DeadLetterController(deadLetterService);

        mockMvc =
                MockMvcBuilders
                        .standaloneSetup(controller)
                        .build();
    }

    @Test
    void getDeadLettersShouldUseDefaultLimit() throws Exception {

        String fileName =
                "2026/08/31/10/message-1/"
                        + "MAX_RETRY_ATTEMPTS_REACHED_1000.json";

        DeadLetterMessageSummary summary =
                DeadLetterMessageSummary.builder()
                        .fileName(fileName)
                        .messageId("message-1")
                        .dequeueCount(11L)
                        .reason(
                                DeadLetterReason
                                        .MAX_RETRY_ATTEMPTS_REACHED)
                        .deadLetteredAt(
                                Instant.parse(
                                        "2026-08-31T10:00:00Z"))
                        .build();

        when(deadLetterService.getDeadLetters(50))
                .thenReturn(List.of(summary));

        mockMvc.perform(
                        get("/error-messages"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$[0].fileName")
                                .value(fileName))
                .andExpect(
                        jsonPath("$[0].messageId")
                                .value("message-1"))
                .andExpect(
                        jsonPath("$[0].dequeueCount")
                                .value(11))
                .andExpect(
                        jsonPath("$[0].reason")
                                .value(
                                        "MAX_RETRY_ATTEMPTS_REACHED"))
                .andExpect(
                        jsonPath("$[0].originalMessage")
                                .doesNotExist());

        verify(deadLetterService)
                .getDeadLetters(50);
    }

    @Test
    void getDeadLettersShouldUseRequestedLimit() throws Exception {

        when(deadLetterService.getDeadLetters(10))
                .thenReturn(List.of());

        mockMvc.perform(
                        get("/error-messages")
                                .param("maxMessages", "10"))
                .andExpect(status().isOk());

        verify(deadLetterService)
                .getDeadLetters(10);
    }

    @Test
    void getDeadLettersShouldRejectLimitBelowMinimum()
            throws Exception {

        mockMvc.perform(
                        get("/error-messages")
                                .param("maxMessages", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getDeadLettersShouldRejectLimitAboveMaximum()
            throws Exception {

        mockMvc.perform(
                        get("/error-messages")
                                .param("maxMessages", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getDeadLetterShouldReturnCompleteMessage()
            throws Exception {

        String fileName =
                "2026/08/31/10/message-1/"
                        + "MAX_RETRY_ATTEMPTS_REACHED_1000.json";

        DeadLetterMessage message =
                DeadLetterMessage.builder()
                        .messageId("message-1")
                        .dequeueCount(11L)
                        .reason(
                                DeadLetterReason
                                        .MAX_RETRY_ATTEMPTS_REACHED)
                        .deadLetteredAt(
                                Instant.parse(
                                        "2026-08-31T10:00:00Z"))
                        .originalMessage(
                                "<paSendRT>payload</paSendRT>")
                        .build();

        when(deadLetterService.getDeadLetter(fileName))
                .thenReturn(message);

        mockMvc.perform(
                        get("/error-messages/detail")
                                .param("filename", fileName))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.messageId")
                                .value("message-1"))
                .andExpect(
                        jsonPath("$.dequeueCount")
                                .value(11))
                .andExpect(
                        jsonPath("$.reason")
                                .value(
                                        "MAX_RETRY_ATTEMPTS_REACHED"))
                .andExpect(
                        jsonPath("$.originalMessage")
                                .value(
                                        "<paSendRT>payload</paSendRT>"));

        verify(deadLetterService)
                .getDeadLetter(fileName);
    }

    @Test
    void getDeadLetterShouldRejectInvalidFileName()
            throws Exception {

        mockMvc.perform(
                        get("/error-messages/detail")
                                .param(
                                        "filename",
                                        "invalid-file.txt"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getDeadLetterShouldRejectMissingFileName()
            throws Exception {

        mockMvc.perform(
                        get("/error-messages/detail"))
                .andExpect(status().isBadRequest());
    }
}