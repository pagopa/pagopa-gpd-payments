package it.gov.pagopa.payments.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.MDC;

/**
 * OER MDC keys: {@code ctx_*} are searchable identifiers, {@code ctx_details} is one JSON string of
 * context only (not indexed by ELK, as in the other pagoPA domains).
 */
public final class LogContext {

  public static final String CTX_NAV = "ctx_nav";
  public static final String CTX_IUV = "ctx_iuv";
  public static final String CTX_ORGANIZATION_FISCAL_CODE = "ctx_organization_fiscal_code";
  public static final String CTX_TRANSACTION_ID = "ctx_transaction_id";
  public static final String CTX_STATION = "ctx_station";
  public static final String CTX_DETAILS = "ctx_details";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private LogContext() {}

  /** Adds an entry to the {@code ctx_details} JSON in the MDC; a null value is skipped. */
  public static void putDetail(String key, Object value) {
    if (value == null) {
      return;
    }
    ObjectNode details = currentDetails();
    details.set(key, MAPPER.valueToTree(value));
    MDC.put(CTX_DETAILS, details.toString());
  }

  private static ObjectNode currentDetails() {
    String current = MDC.get(CTX_DETAILS);
    if (current != null) {
      try {
        JsonNode node = MAPPER.readTree(current);
        if (node instanceof ObjectNode details) {
          return details;
        }
      } catch (JsonProcessingException e) {
        // not written by putDetail: start a new map
      }
    }
    return MAPPER.createObjectNode();
  }
}
