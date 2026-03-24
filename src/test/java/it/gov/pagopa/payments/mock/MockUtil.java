package it.gov.pagopa.payments.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

public class MockUtil {

  public static <T> T readModelFromFile(String relativePath, Class<T> clazz) throws IOException {
    ObjectMapper objectMapper = new ObjectMapper();
    objectMapper.registerModule(new JavaTimeModule());
    try (InputStream inputStream =
            Objects.requireNonNull(
                MockUtil.class.getClassLoader().getResourceAsStream(relativePath),
                "Resource not found: " + relativePath
            )) {
     return objectMapper.readValue(inputStream, clazz);
    }
  }
}
