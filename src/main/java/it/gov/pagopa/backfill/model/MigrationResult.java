package it.gov.pagopa.backfill.model;

public enum MigrationResult {
  MIGRATED,
  ALREADY_MIGRATED,
  INCONSISTENT,
  HASH_MISMATCH,
  RETRYABLE_ERROR
}
