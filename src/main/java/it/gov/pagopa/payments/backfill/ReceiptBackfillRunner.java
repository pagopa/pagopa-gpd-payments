package it.gov.pagopa.payments.backfill;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "receipts.backfill.enabled",
    havingValue = "true")
public class ReceiptBackfillRunner implements ApplicationRunner {

  private final ReceiptBackfillService receiptBackfillService;
  private final ApplicationContext applicationContext;

  @Value("${receipts.backfill.dry-run:true}")
  private boolean dryRun;

  @Override
  public void run(ApplicationArguments args) {

    int exitCode;

    try {
      log.info("Starting receipts backfill. dryRun={}", dryRun);

      MigrationSummary summary =
          receiptBackfillService.migrateAll(dryRun);

      log.info(
          "Receipts backfill completed. dryRun={}, summary={}",
          dryRun,
          summary);

      exitCode = 0;

    } catch (Exception e) {

      log.error(
          "Receipts backfill failed. dryRun={}",
          dryRun,
          e);

      exitCode = 1;
    }

    final int finalExitCode = exitCode;

    int springExitCode =
        SpringApplication.exit(
            applicationContext,
            () -> finalExitCode);

    System.exit(
        finalExitCode != 0 ? finalExitCode : springExitCode);
  }
}