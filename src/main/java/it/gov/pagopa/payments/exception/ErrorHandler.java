package it.gov.pagopa.payments.exception;

import it.gov.pagopa.payments.config.LoggingAspect;
import it.gov.pagopa.payments.model.ProblemJson;
import it.gov.pagopa.payments.utils.LogMasker;
import java.util.ArrayList;
import java.util.List;
import javax.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** All Exceptions are handled by this class */
@ControllerAdvice
@Slf4j
public class ErrorHandler extends ResponseEntityExceptionHandler {

  public static final String INTERNAL_SERVER_ERROR = "INTERNAL SERVER ERROR";
  public static final String BAD_REQUEST = "BAD REQUEST";
  public static final String FOREIGN_KEY_VIOLATION = "23503";

  private static final String API_OPERATION_FAILED = "Failed API operation";

  /**
   * Logs the failure milestone of an API operation. The message is static: the reason of the
   * failure is published as ECS {@code error.*} fields, redacted from any PII or financial data.
   */
  private void logMilestoneFailure(Exception ex, HttpStatus status) {
    MDC.put(LoggingAspect.ERROR_TYPE, ex.getClass().getName());
    logMilestoneFailure(ex.getMessage(), status, ex);
  }

  private void logMilestoneFailure(String message, HttpStatus status) {
    logMilestoneFailure(message, status, null);
  }

  private void logMilestoneFailure(String message, HttpStatus status, Exception ex) {
    MDC.put(LoggingAspect.EVENT_OUTCOME, LoggingAspect.OUTCOME_FAILURE);
    MDC.put(LoggingAspect.CTX_DETAILS_HTTP_CODE, String.valueOf(status.value()));
    String executionTime = LoggingAspect.getExecutionTime();
    if (executionTime != null && !"-".equals(executionTime)) {
      MDC.put(LoggingAspect.CTX_DETAILS_RESPONSE_TIME, executionTime);
    }
    String redactedMessage = LogMasker.redact(message);
    if (redactedMessage != null) {
      MDC.put(LoggingAspect.ERROR_MESSAGE, redactedMessage);
    }

    if (status.is4xxClientError()) {
      // client side KO: the milestone outcome carries the failure, no stack trace needed
      log.info(API_OPERATION_FAILED);
    } else if (ex != null) {
      log.error(API_OPERATION_FAILED, ex);
    } else {
      log.error(API_OPERATION_FAILED);
    }

    MDC.remove(LoggingAspect.EVENT_OUTCOME);
    MDC.remove(LoggingAspect.CTX_DETAILS_HTTP_CODE);
    MDC.remove(LoggingAspect.CTX_DETAILS_RESPONSE_TIME);
    MDC.remove(LoggingAspect.ERROR_TYPE);
    MDC.remove(LoggingAspect.ERROR_MESSAGE);
  }

  @Override
  public ResponseEntity<Object> handleHttpMessageNotReadable(
      HttpMessageNotReadableException ex,
      HttpHeaders headers,
      HttpStatus status,
      WebRequest request) {
    logMilestoneFailure(ex, HttpStatus.BAD_REQUEST);
    return generateErrorResponse(ex.getMessage());
  }

  @Override
  public ResponseEntity<Object> handleMissingServletRequestParameter(
      MissingServletRequestParameterException ex,
      HttpHeaders headers,
      HttpStatus status,
      WebRequest request) {
    logMilestoneFailure(ex, HttpStatus.BAD_REQUEST);
    return generateErrorResponse(ex.getMessage());
  }

  @Override
  protected ResponseEntity<Object> handleTypeMismatch(
      TypeMismatchException ex, HttpHeaders headers, HttpStatus status, WebRequest request) {
    logMilestoneFailure(ex, HttpStatus.BAD_REQUEST);
    return generateErrorResponse(
        String.format(
            "Invalid value %s for property %s",
            ex.getValue(), ((MethodArgumentTypeMismatchException) ex).getName()));
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatus status,
      WebRequest request) {
    List<String> details = new ArrayList<>();
    for (FieldError error : ex.getBindingResult().getFieldErrors()) {
      details.add(error.getField() + ": " + error.getDefaultMessage());
    }
    var detailsMessage = String.join(", ", details);
    logMilestoneFailure("Input not valid: " + detailsMessage, HttpStatus.BAD_REQUEST);
    return generateErrorResponse(detailsMessage);
  }

  @ExceptionHandler({DataIntegrityViolationException.class})
  public ResponseEntity<ProblemJson> handleDataIntegrityViolationException(
      final DataIntegrityViolationException ex, final WebRequest request) {
    ProblemJson errorResponse = null;

    if (ex.getCause() instanceof ConstraintViolationException) {
      logMilestoneFailure(ex, HttpStatus.CONFLICT);
      errorResponse =
          ProblemJson.builder()
              .status(HttpStatus.CONFLICT.value())
              .title("Conflict with the current state of the resource")
              .detail("There is a relation with other resource.")
              .build();
    }

    if (errorResponse == null) {
      logMilestoneFailure(ex, HttpStatus.INTERNAL_SERVER_ERROR);
      errorResponse =
          ProblemJson.builder()
              .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
              .title(INTERNAL_SERVER_ERROR)
              .detail("A persistence error occurred.")
              .build();
    }

    return new ResponseEntity<>(errorResponse, HttpStatus.valueOf(errorResponse.getStatus()));
  }

  @ExceptionHandler({AppException.class})
  public ResponseEntity<ProblemJson> handleAppException(
      final AppException ex, final WebRequest request) {

    logMilestoneFailure(ex, ex.getHttpStatus());

    var errorResponse =
        ProblemJson.builder()
            .status(ex.getHttpStatus().value())
            .title(ex.getTitle())
            .detail(ex.getMessage())
            .build();
    return new ResponseEntity<>(errorResponse, ex.getHttpStatus());
  }

  @ExceptionHandler({Exception.class})
  public ResponseEntity<ProblemJson> handleGenericException(
      final Exception ex, final WebRequest request) {
    logMilestoneFailure(ex, HttpStatus.INTERNAL_SERVER_ERROR);
    var errorResponse =
        ProblemJson.builder()
            .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
            .title(INTERNAL_SERVER_ERROR)
            .detail("An unexpected error occurred.")
            .build();
    return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
  }

  @ExceptionHandler(ConstraintViolationException.class)
  protected ResponseEntity<String> handleConstraintViolationError(final ConstraintViolationException exception) {
    logMilestoneFailure(exception, HttpStatus.BAD_REQUEST);
    return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
  }

  private ResponseEntity<Object> generateErrorResponse(String errorMsg) {
    var errorResponse =
        ProblemJson.builder()
            .status(HttpStatus.BAD_REQUEST.value())
            .title(BAD_REQUEST)
            .detail(errorMsg)
            .build();
    return new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
  }
}
