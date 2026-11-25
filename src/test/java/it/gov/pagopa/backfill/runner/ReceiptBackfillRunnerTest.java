
package it.gov.pagopa.backfill.runner;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import it.gov.pagopa.backfill.model.MigrationSummary;
import it.gov.pagopa.backfill.service.ReceiptBackfillService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

class ReceiptBackfillRunnerTest {

  private ReceiptBackfillService receiptBackfillService;
  private ApplicationContext applicationContext;
  private ApplicationArguments args;
  private ReceiptBackfillRunner runner;

  @BeforeEach
  void setUp() {
    receiptBackfillService = mock(ReceiptBackfillService.class);
    applicationContext = mock(ApplicationContext.class);
    args = mock(ApplicationArguments.class);

    runner = spy(new ReceiptBackfillRunner(
        receiptBackfillService,
        applicationContext));

    // Impedisce la chiamata reale a System.exit()
    doNothing().when(runner).exit(anyInt());
  }

  @Test
  void shouldCompleteDryRunSuccessfully() {
    ReflectionTestUtils.setField(runner, "dryRun", true);

    MigrationSummary summary = mock(MigrationSummary.class);
    when(receiptBackfillService.migrateAll(true)).thenReturn(summary);

    try (MockedStatic<SpringApplication> spring =
        mockStatic(SpringApplication.class)) {

      spring.when(() ->
              SpringApplication.exit(
                  eq(applicationContext),
                  any()))
          .thenReturn(0);

      runner.run(args);

      verify(receiptBackfillService).migrateAll(true);
      verify(runner).exit(0);
    }
  }

  @Test
  void shouldCompleteMigrationSuccessfully() {
    ReflectionTestUtils.setField(runner, "dryRun", false);

    MigrationSummary summary = mock(MigrationSummary.class);
    when(receiptBackfillService.migrateAll(false)).thenReturn(summary);

    try (MockedStatic<SpringApplication> spring =
        mockStatic(SpringApplication.class)) {

      spring.when(() ->
              SpringApplication.exit(
                  eq(applicationContext),
                  any()))
          .thenReturn(0);

      runner.run(args);

      verify(receiptBackfillService).migrateAll(false);
      verify(runner).exit(0);
    }
  }

  @Test
  void shouldExitWithCodeOneWhenMigrationFails() {
    ReflectionTestUtils.setField(runner, "dryRun", false);

    when(receiptBackfillService.migrateAll(false))
        .thenThrow(new RuntimeException("Migration failed"));

    try (MockedStatic<SpringApplication> spring =
        mockStatic(SpringApplication.class)) {

      spring.when(() ->
              SpringApplication.exit(
                  eq(applicationContext),
                  any()))
          .thenReturn(0);

      runner.run(args);

      verify(receiptBackfillService).migrateAll(false);
      verify(runner).exit(1);
    }
  }

  @Test
  void shouldUseSpringExitCodeWhenMigrationSucceeds() {
    ReflectionTestUtils.setField(runner, "dryRun", true);

    MigrationSummary summary = mock(MigrationSummary.class);
    when(receiptBackfillService.migrateAll(true)).thenReturn(summary);

    try (MockedStatic<SpringApplication> spring =
        mockStatic(SpringApplication.class)) {

      spring.when(() ->
              SpringApplication.exit(
                  eq(applicationContext),
                  any()))
          .thenReturn(2);

      runner.run(args);

      verify(receiptBackfillService).migrateAll(true);
      verify(runner).exit(2);
    }
  }
}
