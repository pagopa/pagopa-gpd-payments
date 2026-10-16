package it.gov.pagopa.payments.logging;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import it.gov.pagopa.payments.endpoints.validation.exceptions.PartnerValidationException;
import it.gov.pagopa.payments.exception.AppError;
import it.gov.pagopa.payments.exception.AppException;
import it.gov.pagopa.payments.model.PaaErrorEnum;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class DiagnosticOwnershipTest {
  private Map<String, String> previous;
  private final LogEventWriter lower = mock(LogEventWriter.class);
  private final LogEventWriter boundary = mock(LogEventWriter.class);

  @BeforeEach void setUp() {
    previous = MDC.getCopyOfContextMap();
    MDC.clear();
  }

  @AfterEach void tearDown() {
    OperationLogScopeTest.restore(previous);
  }

  @Test void dependencyFactsTravelThroughRealCausesWithoutEarlyEmission() {
    IOException original = new IOException("original complete message");
    try (OperationLogScope scope = OperationLogScope.open("request")) {
      capture(original);
      var converted = new PartnerValidationException(PaaErrorEnum.PAA_SYSTEM_ERROR,
          new AppException(AppError.DB_ERROR, original));
      assertEquals(new PartnerValidationException(PaaErrorEnum.PAA_SYSTEM_ERROR).getMessage(),
          converted.getMessage());
      assertEquals(new AppException(AppError.DB_ERROR).getMessage(),
          converted.getCause().getMessage());
      var context = scope.dependencyFailure(converted);
      assertSame(original, context.failure());
      assertTrue(context.details().durationMs() >= 20);
      verifyNoInteractions(lower, boundary);
      assertFalse(scope.hasDiagnosis());
      FailureDiagnostics.technicalFailure(boundary, converted);
      verify(boundary).dependencyFailed(context.details(),
          LogEventWriter.FailureDisposition.TERMINAL, original);
      assertTrue(scope.hasDiagnosis());
      assertNull(MDC.get("error.message"));
    }
  }

  @Test void absorbedFailureIsEmittedAtItsCatchAndRecoveredFailureIsNotAnError() {
    Exception original = new Exception("absorbed");
    Exception retry = new Exception("retry");
    try (OperationLogScope scope = OperationLogScope.open("request")) {
      capture(original);
      FailureDiagnostics.absorbedFailure(lower, original);
      FailureDiagnostics.technicalFailure(boundary, new AppException(AppError.DB_ERROR, original));
      verify(lower).dependencyFailed(scope.dependencyFailure(original).details(),
          LogEventWriter.FailureDisposition.TERMINAL, original);
      capture(retry);
      FailureDiagnostics.recovered(retry);
      assertTrue(scope.isHandled(new PartnerValidationException(PaaErrorEnum.PAA_SYSTEM_ERROR, retry)));
      FailureDiagnostics.technicalFailure(boundary,
          new PartnerValidationException(PaaErrorEnum.PAA_SYSTEM_ERROR, retry));
      verifyNoInteractions(boundary);
    }
  }

  @Test void paymentEnrichmentSharesFactsButNewOperationsDoNot() {
    Exception original = new Exception();
    try (OperationLogScope scope = OperationLogScope.open("request")) {
      try (OperationLogScope payment = scope.payment("nav", "creditor")) {
        capture(original);
        payment.markHandled(original);
      }
      assertNotNull(scope.dependencyFailure(original));
      assertTrue(scope.isHandled(original));
      try (OperationLogScope retry = OperationLogScope.open("retry")) {
        assertNull(retry.dependencyFailure(original));
        assertFalse(retry.isHandled(original));
        assertFalse(retry.hasDiagnosis());
      }
      assertTrue(scope.isHandled(original));
    }
  }

  @Test void distinctFailuresAndSuppressedFailuresAreNotAliases() {
    Exception original = new Exception("same");
    Exception other = new Exception("same");
    original.addSuppressed(other);
    try (OperationLogScope scope = OperationLogScope.open("request")) {
      capture(original);
      scope.markHandled(original);
      assertFalse(scope.isHandled(other));
      assertNull(scope.dependencyFailure(other));
      FailureDiagnostics.technicalFailure(boundary, other);
      verify(boundary).technicalFailure(other);
    }
  }

  @Test void deepAndCyclicCauseChainsTerminateWithoutRenderingLimits() {
    Exception first = new Exception("first");
    Exception second = new Exception("second", first);
    first.initCause(second);
    Throwable outer = second;
    for (int i = 0; i < 10000; i++) {
      outer = new Exception("converted", outer);
    }
    try (OperationLogScope scope = OperationLogScope.open("request")) {
      capture(first);
      scope.markHandled(first);
      assertSame(first, scope.dependencyFailure(outer).failure());
      assertTrue(scope.isHandled(outer));
    }
  }

  @Test void unscopedPropagationIsCallerResponsibilityWithoutInventingIds() {
    Exception failure = new Exception("standalone");
    capture(failure);
    verifyNoInteractions(lower);
    FailureDiagnostics.technicalFailure(boundary, failure);
    verify(boundary).technicalFailure(failure);
    assertNull(OperationLogScope.currentOrNull());
    assertNull(MDC.getCopyOfContextMap());
  }

  @Test void localConversionSelectsOriginalMessageWithoutChangingResponseException() {
    IOException original = new IOException("complete original JSON message ".repeat(300));
    IllegalStateException converted = new IllegalStateException("Unable to serialize", original);
    var response = new org.springframework.web.server.ResponseStatusException(
        org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "Unable to access", converted);
    FailureDiagnostics.technicalFailure(boundary, response);
    verify(boundary).technicalFailure(original);
    assertEquals(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, response.getStatus());
    assertEquals("Unable to access", response.getReason());
    assertSame(original, response.getCause().getCause());
  }

  @Test void failedAbsorbingEmissionDoesNotClaimHandledStateOrDiagnosis() {
    Exception original = new Exception("absorbed");
    try (OperationLogScope scope = OperationLogScope.open("request")) {
      capture(original);
      doThrow(new IllegalStateException("logger failed")).when(lower)
          .dependencyFailed(any(), any(), same(original));
      assertThrows(IllegalStateException.class,
          () -> FailureDiagnostics.absorbedFailure(lower, original));
      assertFalse(scope.isHandled(original));
      assertFalse(scope.hasDiagnosis());
      FailureDiagnostics.technicalFailure(boundary, original);
      verify(boundary).dependencyFailed(scope.dependencyFailure(original).details(),
          LogEventWriter.FailureDisposition.TERMINAL, original);
    }
  }

  private void capture(Throwable failure) {
    FailureDiagnostics.dependencyFailure(EventDetails.Dependency.AZURE_TABLE_STORAGE,
        EventDetails.DependencyOperation.GET_RECEIPT, System.nanoTime() - 25_000_000, failure);
  }
}
