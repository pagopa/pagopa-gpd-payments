package it.gov.pagopa.payments.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import it.gov.pagopa.payments.config.logging.LogDependencies;
import org.junit.jupiter.api.Test;

class LogDependenciesTest {
  @Test
  void dependencyNamesRemainStableForElkQueries() {
    assertEquals("gpd", LogDependencies.GPD);
    assertEquals("cache", LogDependencies.CACHE);
    assertEquals("vertical-service", LogDependencies.VERTICAL_SERVICE);
    assertEquals("azure-tables", LogDependencies.AZURE_TABLES);
    assertEquals("azure-queue", LogDependencies.AZURE_QUEUE);
    assertEquals("azure-blob", LogDependencies.AZURE_BLOB);
  }
}
