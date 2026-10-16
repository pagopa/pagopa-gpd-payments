package it.gov.pagopa.payments.logging;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Approved projections for {@code ctx_details}, not arbitrary request or response objects.
 * Adding a projection requires an explicit JSON mapping and event association.
 * Null optional values are omitted. Identifier strings must come from the named
 * identifier, never from payload dumps, URLs or exception text.
 */
public sealed interface EventDetails {
  /** Logical integrations, independent of environment-specific hosts or account names. */
  enum Dependency {
    GPD_CORE("gpd-core"),
    VERTICAL_SERVICE("vertical-service"),
    API_CONFIG_CACHE("api-config-cache"),
    AZURE_TABLE_STORAGE("azure-table-storage"),
    AZURE_BLOB_STORAGE("azure-blob-storage"),
    AZURE_QUEUE_STORAGE("azure-queue-storage");

    private final String value;

    Dependency(String value) {
      this.value = value;
    }

    public String value() {
      return value;
    }
  }

  enum DependencyOperation {
    GET_PAYMENT_OPTION("getPaymentOption"),
    SEND_PAYMENT_OPTION_RECEIPT("sendPaymentOptionReceipt"),
    DEMAND_PAYMENT_NOTICE("demandPaymentNotice"),
    CREATE_RECEIPT("createReceipt"),
    GET_RECEIPT("getReceipt"),
    LIST_RECEIPTS("listReceipts"),
    UPDATE_RECEIPT_STATUS("updateReceiptStatus"),
    LIST_DEAD_LETTERS("listDeadLetters"),
    GET_DEAD_LETTER("getDeadLetter"),
    PERSIST_DEAD_LETTER("persistDeadLetter"),
    ENQUEUE_RECEIPT_RETRY("enqueueReceiptRetry"),
    RECEIVE_RECEIPT_RETRIES("receiveReceiptRetries"),
    RESCHEDULE_RECEIPT_RETRY("rescheduleReceiptRetry"),
    DELETE_RECEIPT_RETRY("deleteReceiptRetry"),
    REFRESH_CONFIGURATION("refreshConfiguration");

    private final String value;

    DependencyOperation(String value) {
      this.value = value;
    }

    public String value() {
      return value;
    }
  }

  enum Outcome {
    OK, KO
  }

  enum EligibilityReason {
    STATION_MAINTENANCE_WITHOUT_STAND_IN,
    STATION_ACA_DISABLED,
    PAYMENT_NOT_ACA,
    STATION_STAND_IN_DISABLED,
    PAYMENT_STAND_IN_DISABLED
  }

  enum Reason {
    RETRYABLE_FAILURE,
    RETRY_LIMIT_EXCEEDED,
    BUSINESS_REJECTION,
    DEAD_LETTER_PERSISTED_SOURCE_DELETE_FAILED,
    USABLE_CACHE_RETAINED
  }

  record Eligibility(EligibilityReason reason) implements EventDetails {
    public Eligibility {
      Objects.requireNonNull(reason, "reason");
    }
  }

  /** Describes one downstream call; its outcome and timing are not the overall operation result. */
  record DependencyCall(
      Dependency dependency,
      DependencyOperation dependencyOperation,
      Outcome dependencyOutcome,
      Integer dependencyHttpCode,
      Long durationMs,
      Integer attempt,
      Reason reason,
      String messageId) implements EventDetails {
    public DependencyCall {
      Objects.requireNonNull(dependency, "dependency");
      Objects.requireNonNull(dependencyOperation, "dependencyOperation");
      Objects.requireNonNull(dependencyOutcome, "dependencyOutcome");
    }
  }

  /** Describes a retry only after enqueue or visibility update has succeeded. */
  record Retry(
      Dependency dependency, Integer attempt, Long retryDelayMs, Reason reason, String messageId)
      implements EventDetails {}

  record ReceiptDisposition(String messageId, Integer attempt, Reason reason)
      implements EventDetails {}

  /** Aggregate message dispositions; callers are responsible for mutually exclusive counting. */
  record JobSummary(
      Long processedCount,
      Long retryScheduledCount,
      Long discardedCount,
      Long deadLetteredCount,
      Long failedCount) implements EventDetails {}

  record Validation(List<ValidationError> errors) implements EventDetails {
    public Validation {
      errors = List.copyOf(Objects.requireNonNull(errors, "errors"));
    }
  }

  /** Retains a controlled XSD diagnostic key instead of messages containing rejected input. */
  record ValidationError(Integer line, Integer column, String key) {
    private static final Pattern SAFE_KEY =
        Pattern.compile("(?:cvc|s4s|src|cos|sch-props|schema_reference|wf)-[A-Za-z0-9_.-]+");

    public ValidationError {
      key = key != null && SAFE_KEY.matcher(key).matches() ? key : "invalid-xml";
    }
  }
}
