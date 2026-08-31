package it.gov.pagopa.payments;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        properties = {
            "cron.job.schedule.retry.enabled=false"
        })
@AutoConfigureMockMvc
class OpenApiDeadLetterTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void openApiShouldExposeDeadLetterEndpoints() throws Exception {

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())

                // List endpoint
                .andExpect(
                        jsonPath(
                                "$.paths['/error-messages'].get")
                                .exists())
                .andExpect(
                        jsonPath(
                                "$.paths['/error-messages'].get.responses['200']")
                                .exists())
                .andExpect(
                        jsonPath(
                                "$.paths['/error-messages'].get.responses['400']")
                                .exists())
                .andExpect(
                        jsonPath(
                                "$.paths['/error-messages'].get.responses['500']")
                                .exists())
                .andExpect(
                        jsonPath(
                                "$.paths['/error-messages'].get.security[0].ApiKey")
                                .exists())

                // Detail endpoint
                .andExpect(
                        jsonPath(
                                "$.paths['/error-messages/detail'].get")
                                .exists())
                .andExpect(
                        jsonPath(
                                "$.paths['/error-messages/detail'].get.responses['200']")
                                .exists())
                .andExpect(
                        jsonPath(
                                "$.paths['/error-messages/detail'].get.responses['400']")
                                .exists())
                .andExpect(
                        jsonPath(
                                "$.paths['/error-messages/detail'].get.responses['404']")
                                .exists())
                .andExpect(
                        jsonPath(
                                "$.paths['/error-messages/detail'].get.responses['500']")
                                .exists())
                .andExpect(
                        jsonPath(
                                "$.paths['/error-messages/detail'].get.security[0].ApiKey")
                                .exists());
    }

    @Test
    void openApiShouldExposeDeadLetterSchemas() throws Exception {

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())

                .andExpect(
                        jsonPath(
                                "$.components.schemas.DeadLetterMessageSummary")
                                .exists())
                .andExpect(
                        jsonPath(
                                "$.components.schemas.DeadLetterMessage")
                                .exists())

                // Summary must never expose the original XML payload
                .andExpect(
                        jsonPath(
                                "$.components.schemas.DeadLetterMessageSummary"
                                        + ".properties.originalMessage")
                                .doesNotExist())

                // Detail explicitly exposes it
                .andExpect(
                        jsonPath(
                                "$.components.schemas.DeadLetterMessage"
                                        + ".properties.originalMessage")
                                .exists());
    }
}