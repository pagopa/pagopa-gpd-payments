
package it.gov.pagopa.backfill;

import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReceiptBackfillApplicationTest {

  @Test
  void mainShouldStartSpringBootInNonWebMode() {
    String[] args = {"--receipts.backfill.enabled=false"};

    try (MockedConstruction<SpringApplication> mocked =
        mockConstruction(SpringApplication.class)) {

      ReceiptBackfillApplication.main(args);

      assertEquals(1, mocked.constructed().size());

      SpringApplication application = mocked.constructed().get(0);

      verify(application)
          .setWebApplicationType(WebApplicationType.NONE);

      verify(application).run(args);
    }
  }
}
