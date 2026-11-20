package it.gov.pagopa.backfill;

import it.gov.pagopa.payments.config.ReceiptBlobConfig;
import it.gov.pagopa.payments.config.TableServiceConfiguration;
import it.gov.pagopa.payments.config.logging.AzureLoggingPolicy;
import it.gov.pagopa.payments.service.ReceiptBlobStore;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({
    ReceiptBlobConfig.class,
    TableServiceConfiguration.class,
    AzureLoggingPolicy.Factory.class,
    ReceiptBlobStore.class
})
public class ReceiptBackfillApplication {

  public static void main(String[] args) {

    SpringApplication application =
        new SpringApplication(ReceiptBackfillApplication.class);

    application.setWebApplicationType(WebApplicationType.NONE);

    application.run(args);
  }
}