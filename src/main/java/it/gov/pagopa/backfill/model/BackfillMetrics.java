package it.gov.pagopa.backfill.model;

import java.util.concurrent.atomic.AtomicLong;

public class BackfillMetrics {

  private final AtomicLong uploadCount = new AtomicLong();
  private final AtomicLong downloadCount = new AtomicLong();
  private final AtomicLong tableUpdateCount = new AtomicLong();
  private final AtomicLong migrationCount = new AtomicLong();

  private final AtomicLong uploadNanos = new AtomicLong();
  private final AtomicLong downloadNanos = new AtomicLong();
  private final AtomicLong tableUpdateNanos = new AtomicLong();
  private final AtomicLong migrationNanos = new AtomicLong();

  public void recordBlobUpload(long nanos) {
    uploadNanos.addAndGet(nanos);
    uploadCount.incrementAndGet();
  }

  public void recordBlobDownload(long nanos) {
    downloadNanos.addAndGet(nanos);
    downloadCount.incrementAndGet();
  }

  public void recordTableUpdate(long nanos) {
    tableUpdateNanos.addAndGet(nanos);
    tableUpdateCount.incrementAndGet();
  }

  public void recordMigrationDuration(long nanos) {
    migrationNanos.addAndGet(nanos);
    migrationCount.incrementAndGet();
  }

  public double getAverageUploadMs() {
    return averageMs(uploadNanos, uploadCount);
  }

  public double getAverageDownloadMs() {
    return averageMs(downloadNanos, downloadCount);
  }

  public double getAverageTableUpdateMs() {
    return averageMs(tableUpdateNanos, tableUpdateCount);
  }

  public double getAverageMigrationMs() {
    return averageMs(migrationNanos, migrationCount);
  }

  public long getMigrationCount() {
    return migrationCount.get();
  }

  private double averageMs(
      AtomicLong totalNanos,
      AtomicLong count) {

    long operations = count.get();

    return operations == 0
        ? 0
        : (totalNanos.get() / 1_000_000.0) / operations;
  }
}