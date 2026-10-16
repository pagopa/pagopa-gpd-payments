package it.gov.pagopa.payments.logging.helper;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.gov.pagopa.payments.logging.EventDetails;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EventDetailsJsonTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void dependencyProjectionUsesOnlyApprovedKeysAndNumericTypes() throws Exception {
    JsonNode json = json(new EventDetails.DependencyCall(
        EventDetails.Dependency.GPD_CORE,
        EventDetails.DependencyOperation.GET_PAYMENT_OPTION,
        EventDetails.Outcome.KO, 503, 42L, 2, null, "0000123"));

    assertEquals(7, json.size());
    assertEquals("gpd-core", json.get("dependency").textValue());
    assertEquals("getPaymentOption", json.get("dependencyOperation").textValue());
    assertEquals("KO", json.get("dependencyOutcome").textValue());
    assertTrue(json.get("dependencyHttpCode").isIntegralNumber());
    assertEquals(503, json.get("dependencyHttpCode").intValue());
    assertTrue(json.get("durationMs").isIntegralNumber());
    assertEquals(42, json.get("durationMs").longValue());
    assertTrue(json.get("attempt").isIntegralNumber());
    assertEquals("0000123", json.get("messageId").textValue());
    assertFalse(json.has("reason"));
  }

  @Test
  void optionalDependencyValuesAreAbsentRatherThanNullOrPlaceholders() throws Exception {
    JsonNode json = json(new EventDetails.DependencyCall(
        EventDetails.Dependency.VERTICAL_SERVICE,
        EventDetails.DependencyOperation.DEMAND_PAYMENT_NOTICE,
        EventDetails.Outcome.OK, null, null, null, null, null));
    assertEquals(3, json.size());
    assertEquals("vertical-service", json.get("dependency").textValue());
    assertEquals("demandPaymentNotice", json.get("dependencyOperation").textValue());
    assertEquals("OK", json.get("dependencyOutcome").textValue());
  }

  @Test
  void retryAndDispositionHaveDistinctProjections() throws Exception {
    JsonNode retry = json(new EventDetails.Retry(
        EventDetails.Dependency.GPD_CORE, 3, 1000L,
        EventDetails.Reason.RETRYABLE_FAILURE, "message-id"));
    assertEquals(5, retry.size());
    assertEquals(1000, retry.get("retryDelayMs").longValue());
    assertTrue(retry.get("retryDelayMs").isIntegralNumber());
    assertEquals("RETRYABLE_FAILURE", retry.get("reason").textValue());

    JsonNode disposition = json(new EventDetails.ReceiptDisposition(
        "message-id", 3, EventDetails.Reason.RETRY_LIMIT_EXCEEDED));
    assertEquals(3, disposition.size());
    assertFalse(disposition.has("dependency"));
    assertFalse(disposition.has("retryDelayMs"));
  }

  @Test
  void jobCountersRetainZeroAndOmitUnavailableValues() throws Exception {
    JsonNode json = json(new EventDetails.JobSummary(0L, 2L, null, 1L, null));
    assertEquals(3, json.size());
    assertEquals(0, json.get("processedCount").longValue());
    assertEquals(2, json.get("retryScheduledCount").longValue());
    assertEquals(1, json.get("deadLetteredCount").longValue());
    assertTrue(json.get("processedCount").isIntegralNumber());
    assertFalse(json.has("discardedCount"));
    assertFalse(json.has("failedCount"));
  }

  @Test
  void eligibilityUsesControlledReason() throws Exception {
    JsonNode json = json(new EventDetails.Eligibility(
        EventDetails.EligibilityReason.PAYMENT_STAND_IN_DISABLED));
    assertEquals(1, json.size());
    assertEquals("PAYMENT_STAND_IN_DISABLED", json.get("reason").textValue());
  }

  @Test
  void validationKeysAreSanitizedAndInputListIsCopied() throws Exception {
    var errors = new ArrayList<>(List.of(
        new EventDetails.ValidationError(12, 34, "cvc-pattern-valid"),
        new EventDetails.ValidationError(null, null, "cvc-pattern-valid: secret rejected value"),
        new EventDetails.ValidationError(1, 2, null)));
    var details = new EventDetails.Validation(errors);
    errors.clear();
    JsonNode json = json(details);
    assertEquals(1, json.size());
    assertEquals(3, json.get("xsd_errors").size());
    assertEquals(12, json.at("/xsd_errors/0/line").intValue());
    assertEquals(34, json.at("/xsd_errors/0/column").intValue());
    assertEquals("cvc-pattern-valid", json.at("/xsd_errors/0/key").textValue());
    assertEquals("invalid-xml", json.at("/xsd_errors/1/key").textValue());
    assertEquals(1, json.at("/xsd_errors/1").size());
    assertEquals("invalid-xml", json.at("/xsd_errors/2/key").textValue());
    assertFalse(json.toString().contains("secret"));
    assertThrows(UnsupportedOperationException.class, () -> details.errors().clear());
  }

  @Test
  void emptyProjectionsAreOmitted() {
    assertNull(EventDetailsJson.serialize(null));
    assertNull(EventDetailsJson.serialize(new EventDetails.Retry(null, null, null, null, null)));
    assertNull(EventDetailsJson.serialize(new EventDetails.ReceiptDisposition(null, null, null)));
    assertNull(EventDetailsJson.serialize(new EventDetails.JobSummary(null, null, null, null, null)));
    assertNull(EventDetailsJson.serialize(new EventDetails.Validation(List.of())));
  }

  @Test
  void identifiersAreEscapedWithoutNewSizeLimits() throws Exception {
    String id = "0000\"\\\n" + "x".repeat(20000);
    JsonNode json = json(new EventDetails.ReceiptDisposition(id, null, null));
    assertEquals(id, json.get("messageId").textValue());
    assertEquals(1, json.size());
  }

  private JsonNode json(EventDetails details) throws Exception {
    return mapper.readTree(EventDetailsJson.serialize(details));
  }
}
