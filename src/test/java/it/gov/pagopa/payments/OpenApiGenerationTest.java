package it.gov.pagopa.payments;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

@SpringBootTest
@AutoConfigureMockMvc
class OpenApiGenerationTest {

  @Autowired ObjectMapper objectMapper;

  @Autowired private MockMvc mvc;

  @Test
  void generateExternalOpenApi() throws Exception {
    JsonNode openApi = saveOpenAPI("/v3/api-docs/external", "openapi.json");

    JsonNode paths = openApi.path("paths");

    assertTrue(paths.has("/info"));
    assertTrue(paths.has("/payments/{organizationfiscalcode}/receipts"));
    assertTrue(paths.has("/payments/{organizationfiscalcode}/receipts/{iuv}"));

    assertFalse(paths.has("/error-messages"));
    assertFalse(paths.has("/error-messages/detail"));
  }

  @Test
  void generateHelpdeskOpenApi() throws Exception {
    JsonNode openApi = saveOpenAPI("/v3/api-docs/helpdesk", "helpdesk/openapi.json");

    JsonNode paths = openApi.path("paths");

    assertTrue(paths.has("/error-messages"));
    assertTrue(paths.has("/error-messages/detail"));

    assertFalse(paths.has("/info"));
    assertFalse(paths.has("/payments/{organizationfiscalcode}/receipts"));
    assertFalse(paths.has("/payments/{organizationfiscalcode}/receipts/{iuv}"));
  }

  private JsonNode saveOpenAPI(String fromUri, String toFile) throws Exception {
    final JsonNode[] generatedOpenApi = new JsonNode[1];

    mvc.perform(MockMvcRequestBuilders.get(fromUri).accept(MediaType.APPLICATION_JSON))
        .andExpect(MockMvcResultMatchers.status().is2xxSuccessful())
        .andDo(
            result -> {
              assertNotNull(result);
              assertNotNull(result.getResponse());

              String content = result.getResponse().getContentAsString();

              assertFalse(content.isBlank());
              assertFalse(content.contains("${"), "Generated swagger contains placeholders");

              JsonNode swagger = objectMapper.readTree(content);
              generatedOpenApi[0] = swagger;

              String formatted =
                  objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(swagger);

              Path outputPath = Paths.get("openapi").resolve(toFile);
              Files.createDirectories(outputPath.getParent());
              Files.write(outputPath, formatted.getBytes());
            });

    assertNotNull(generatedOpenApi[0]);
    return generatedOpenApi[0];
  }
}