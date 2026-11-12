package it.gov.pagopa.payments.backfill;

public class MigrationSummary {

  private long scanned;
  private long migrated;
  private long alreadyMigrated;
  private long inconsistent;
  private long hashMismatch;
  private long failed;

  public MigrationSummary() {
  }

  public MigrationSummary(BackfillCheckpoint checkpoint) {
    this.scanned = checkpoint.getScanned();
    this.migrated = checkpoint.getMigrated();
    this.alreadyMigrated = checkpoint.getAlreadyMigrated();
    this.inconsistent = checkpoint.getInconsistent();
    this.hashMismatch = checkpoint.getHashMismatch();
    this.failed = checkpoint.getFailed();
  }

  public void incrementScanned() {
    scanned++;
  }

  public void increment(MigrationResult result) {
    switch (result) {
      case MIGRATED -> migrated++;
      case ALREADY_MIGRATED -> alreadyMigrated++;
      case INCONSISTENT -> inconsistent++;
      case HASH_MISMATCH -> hashMismatch++;
      case RETRYABLE_ERROR -> failed++;
    }
  }

  public long getScanned() {
    return scanned;
  }

  public long getMigrated() {
    return migrated;
  }

  public long getAlreadyMigrated() {
    return alreadyMigrated;
  }

  public long getInconsistent() {
    return inconsistent;
  }

  public long getHashMismatch() {
    return hashMismatch;
  }

  public long getFailed() {
    return failed;
  }

  public void add(MigrationSummary other) {
    this.scanned += other.scanned;
    this.migrated += other.migrated;
    this.alreadyMigrated += other.alreadyMigrated;
    this.inconsistent += other.inconsistent;
    this.hashMismatch += other.hashMismatch;
    this.failed += other.failed;
  }

  @Override
  public String toString() {
    return "MigrationSummary{"
        + "scanned=" + scanned
        + ", migrated=" + migrated
        + ", alreadyMigrated=" + alreadyMigrated
        + ", inconsistent=" + inconsistent
        + ", hashMismatch=" + hashMismatch
        + ", failed=" + failed
        + '}';
  }
}