package it.gov.pagopa.payments.config;

/**
 * ECS field names published as MDC keys. First level {@code ctx_*} fields are searchable business
 * identifiers; keys under {@code ctx_details.} are contextual only, and Elasticsearch expands the
 * dot into a nested object.
 */
public final class LogContext {

  public static final String CTX_NAV = "ctx_nav";
  public static final String CTX_IUV = "ctx_iuv";
  public static final String CTX_ORGANIZATION_FISCAL_CODE = "ctx_organization_fiscal_code";
  public static final String CTX_TRANSACTION_ID = "ctx_transaction_id";
  public static final String CTX_DETAILS_STATION = "ctx_details.station";
  public static final String CTX_DETAILS_PREFIX = "ctx_details.";

  private LogContext() {}
}
