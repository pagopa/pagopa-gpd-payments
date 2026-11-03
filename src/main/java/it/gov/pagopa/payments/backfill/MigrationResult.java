package it.gov.pagopa.payments.backfill;

public enum MigrationResult {
  MIGRATED,
  ALREADY_MIGRATED,
  INCONSISTENT,
  HASH_MISMATCH,
  RETRYABLE_ERROR
}
