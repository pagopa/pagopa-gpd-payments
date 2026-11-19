package it.gov.pagopa.payments.backfill;

import lombok.Getter;

@Getter
public class BackfillSizeStats {

  private long entityCount;
  private long currentBytes;
  private long projectedBytes;
  private long xmlBytes;
  private long receiptsToMigrate;

  public void add(long current, long projected, long xml, boolean toMigrate) {
    entityCount++;
    currentBytes += current;
    projectedBytes += projected;
    xmlBytes += xml;

    if (toMigrate) {
      receiptsToMigrate++;
    }
  }

  public void merge(BackfillSizeStats other) {
    entityCount += other.entityCount;
    currentBytes += other.currentBytes;
    projectedBytes += other.projectedBytes;
    xmlBytes += other.xmlBytes;
    receiptsToMigrate += other.receiptsToMigrate;
  }

  public long getSavedBytes() {
    return currentBytes - projectedBytes;
  }

  public double getReductionPercentage() {
    return currentBytes == 0
        ? 0
        : getSavedBytes() * 100.0 / currentBytes;
  }
}