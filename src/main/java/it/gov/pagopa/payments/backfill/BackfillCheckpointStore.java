package it.gov.pagopa.payments.backfill;

import java.util.Optional;

public interface BackfillCheckpointStore {

  Optional<BackfillCheckpoint> load(boolean dryRun);

  void save(
      BackfillCheckpoint checkpoint,
      boolean dryRun);

  void delete(boolean dryRun);
}