package it.gov.pagopa.backfill.model;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BackfillMetricsReport {

  private String executionId;
  private boolean dryRun;
  private String status;

  // Contatori cumulativi del backfill
  private long scanned;
  private long migrated;
  private long alreadyMigrated;
  private long inconsistent;
  private long hashMismatch;
  private long failed;

  // Contatori della singola esecuzione
  private long executionScanned;
  private long executionMigrated;

  // Prestazioni
  private double durationSeconds;
  private double scanRate;
  private double migrationRate;

  private double blobUploadAvgMs;
  private double blobDownloadAvgMs;
  private double tableUpdateAvgMs;
  private double migrationAvgMs;

  // Stima della migrazione in produzione
  private Double estimatedProductionHours;
}
