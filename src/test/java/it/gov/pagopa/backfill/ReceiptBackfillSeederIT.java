package it.gov.pagopa.backfill;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("local")
@TestPropertySource(properties = {
    "receipts.backfill.enabled=false",
    "receipts.backfill.seed.enabled=true",
    "receipts.backfill.seed.dry-run=false",
    "cron.job.schedule.retry.enabled=false"
})
class ReceiptBackfillSeederIT {

  @Autowired
  private ReceiptBackfillTestSeeder seeder;

  @Test
  void seedReceipts() {
    seeder.seed();
  }
}