package it.gov.pagopa.payments.backfill;

import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class BackfillCheckpoint {

  private String organizationFiscalCode;
  private String continuationToken;

  private long scanned;
  private long migrated;
  private long alreadyMigrated;
  private long inconsistent;
  private long hashMismatch;
  private long failed;

  private OffsetDateTime updatedAt;
}