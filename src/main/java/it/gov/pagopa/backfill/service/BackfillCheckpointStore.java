package it.gov.pagopa.backfill.service;

import it.gov.pagopa.backfill.model.BackfillCheckpoint;
import java.util.Optional;

public interface BackfillCheckpointStore {

  Optional<BackfillCheckpoint> load(boolean dryRun);

  void save(
      BackfillCheckpoint checkpoint,
      boolean dryRun);

  void delete(boolean dryRun);
}