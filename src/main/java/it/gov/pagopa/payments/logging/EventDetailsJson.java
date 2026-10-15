package it.gov.pagopa.payments.logging;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Explicit allowlist serializer for event details. Jackson nodes preserve numeric types
 * without reflectively exposing fields added to domain objects.
 */
final class EventDetailsJson {
  private EventDetailsJson() {}

  /** Returns the JSON string used by MDC, or null to omit absent/empty details entirely. */
  static String serialize(EventDetails details) {
    ObjectNode json = JsonNodeFactory.instance.objectNode();
    if (details instanceof EventDetails.Eligibility value) {
      put(json, "reason", value.reason().name());
    } else if (details instanceof EventDetails.DependencyCall value) {
      put(json, "dependency", value.dependency().value());
      put(json, "dependencyOperation", value.dependencyOperation().value());
      put(json, "dependencyOutcome", value.dependencyOutcome().name());
      put(json, "dependencyHttpCode", value.dependencyHttpCode());
      put(json, "durationMs", value.durationMs());
      put(json, "attempt", value.attempt());
      put(json, "reason", value.reason() == null ? null : value.reason().name());
      put(json, "messageId", value.messageId());
    } else if (details instanceof EventDetails.Retry value) {
      put(json, "dependency", value.dependency() == null ? null : value.dependency().value());
      put(json, "attempt", value.attempt());
      put(json, "retryDelayMs", value.retryDelayMs());
      put(json, "reason", value.reason() == null ? null : value.reason().name());
      put(json, "messageId", value.messageId());
    } else if (details instanceof EventDetails.ReceiptDisposition value) {
      put(json, "messageId", value.messageId());
      put(json, "attempt", value.attempt());
      put(json, "reason", value.reason() == null ? null : value.reason().name());
    } else if (details instanceof EventDetails.JobSummary value) {
      put(json, "processedCount", value.processedCount());
      put(json, "retryScheduledCount", value.retryScheduledCount());
      put(json, "discardedCount", value.discardedCount());
      put(json, "deadLetteredCount", value.deadLetteredCount());
      put(json, "failedCount", value.failedCount());
    } else if (details instanceof EventDetails.Validation value && !value.errors().isEmpty()) {
      var errors = json.putArray("xsd_errors");
      for (var error : value.errors()) {
        var entry = errors.addObject();
        put(entry, "line", error.line());
        put(entry, "column", error.column());
        put(entry, "key", error.key());
      }
    }
    return json.isEmpty() ? null : json.toString();
  }

  private static void put(ObjectNode json, String key, String value) {
    if (value != null) {
      json.put(key, value);
    }
  }

  private static void put(ObjectNode json, String key, Number value) {
    if (value != null) {
      json.put(key, value.longValue());
    }
  }
}
