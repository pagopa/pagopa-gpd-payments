package it.gov.pagopa.payments.config;

/** OER MDC keys: {@code ctx_*} are searchable identifiers, {@code ctx_details.*} context only. */
public final class LogContext {

  public static final String CTX_NAV = "ctx_nav";
  public static final String CTX_IUV = "ctx_iuv";
  public static final String CTX_ORGANIZATION_FISCAL_CODE = "ctx_organization_fiscal_code";
  public static final String CTX_TRANSACTION_ID = "ctx_transaction_id";
  public static final String CTX_DETAILS_STATION = "ctx_details.station";
  public static final String CTX_DETAILS_PREFIX = "ctx_details.";

  private LogContext() {}
}
