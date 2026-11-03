package it.gov.pagopa.payments.backfill;

import java.util.Optional;

public interface BackfillCheckpointStore {

  Optional<BackfillCheckpoint> load(
      String organizationFiscalCode);

  void save(
      BackfillCheckpoint checkpoint);

  void delete(
      String organizationFiscalCode);
}