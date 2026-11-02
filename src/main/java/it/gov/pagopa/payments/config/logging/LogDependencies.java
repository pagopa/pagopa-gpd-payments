package it.gov.pagopa.payments.config.logging;

/** Stable dependency names used to filter outbound events independently of their operation. */
public final class LogDependencies {
  public static final String GPD = "gpd";
  public static final String CACHE = "cache";
  public static final String VERTICAL_SERVICE = "vertical-service";
  public static final String AZURE_TABLES = "azure-tables";
  public static final String AZURE_QUEUE = "azure-queue";
  public static final String AZURE_BLOB = "azure-blob";

  private LogDependencies() {}
}
