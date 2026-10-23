package it.gov.pagopa.payments.exception;

import it.gov.pagopa.payments.model.ProblemJson;
import it.gov.pagopa.payments.logging.TechnicalFailureLogging;
import java.util.ArrayList;
import java.util.List;
import javax.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
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

  /**
   * Handle if the input request is not a valid JSON
   *
   * @param ex {@link HttpMessageNotReadableException} exception raised
   * @param headers of the response
   * @param status of the response
   * @param request from frontend
   * @return a {@link ProblemJson} as response with the cause and with a 400 as HTTP status
   */
  @Override
  public ResponseEntity<Object> handleHttpMessageNotReadable(
      HttpMessageNotReadableException ex,
      HttpHeaders headers,
      HttpStatus status,
      WebRequest request) {
    log.warn("HTTP request rejected: unreadable input");
    return generateErrorResponse(ex.getMessage());
  }

  /**
   * Handle if missing some request parameters in the request
   *
   * @param ex {@link MissingServletRequestParameterException} exception raised
   * @param headers of the response
   * @param status of the response
   * @param request from frontend
   * @return a {@link ProblemJson} as response with the cause and with a 400 as HTTP status
   */
  @Override
  public ResponseEntity<Object> handleMissingServletRequestParameter(
      MissingServletRequestParameterException ex,
      HttpHeaders headers,
      HttpStatus status,
      WebRequest request) {
    log.warn("HTTP request rejected: missing parameter");
    return generateErrorResponse(ex.getMessage());
  }

  /**
   * Customize the response for TypeMismatchException.
   *
   * @param ex the exception
   * @param headers the headers to be written to the response
   * @param status the selected response status
   * @param request the current request
   * @return a {@code ResponseEntity} instance
   */
  @Override
  protected ResponseEntity<Object> handleTypeMismatch(
      TypeMismatchException ex, HttpHeaders headers, HttpStatus status, WebRequest request) {
    log.warn("HTTP request rejected: parameter type mismatch");
    return generateErrorResponse(
        String.format(
            "Invalid value %s for property %s",
            ex.getValue(), ((MethodArgumentTypeMismatchException) ex).getName()));
  }

  /**
   * Handle if validation constraints are unsatisfied
   *
   * @param ex {@link MethodArgumentNotValidException} exception raised
   * @param headers of the response
   * @param status of the response
   * @param request from frontend
   * @return a {@link ProblemJson} as response with the cause and with a 400 as HTTP status
   */
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
    log.warn("HTTP request rejected: invalid input");
    return generateErrorResponse(detailsMessage);
  }

  /**
   * @param ex {@link DataIntegrityViolationException} exception raised when the SQL statement
   *     cannot be executed
   * @param request from frontend
   * @return a {@link ProblemJson} as response with the cause and with an appropriated HTTP status
   */
  @ExceptionHandler({DataIntegrityViolationException.class})
  public ResponseEntity<ProblemJson> handleDataIntegrityViolationException(
      final DataIntegrityViolationException ex, final WebRequest request) {
    ProblemJson errorResponse = null;

    if (ex.getCause() instanceof ConstraintViolationException) {
      log.warn("HTTP request rejected: conflicting resource");
      errorResponse =
          ProblemJson.builder()
              .status(HttpStatus.CONFLICT.value())
              .title("Conflict with the current state of the resource")
              .detail("There is a relation with other resource.")
              .build();
    }

    // default response
    if (errorResponse == null) {
      TechnicalFailureLogging.logTechnicalFailure(log, "Persistence operation failed", ex);
      errorResponse =
          ProblemJson.builder()
              .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
              .title(INTERNAL_SERVER_ERROR)
              .detail("A persistence error occurred.")
              .build();
    }

    return new ResponseEntity<>(errorResponse, HttpStatus.valueOf(errorResponse.getStatus()));
  }

  /**
   * Handle if a {@link AppException} is raised
   *
   * @param ex {@link AppException} exception raised
   * @param request from frontend
   * @return a {@link ProblemJson} as response with the cause and with an appropriated HTTP status
   */
  @ExceptionHandler({AppException.class})
  public ResponseEntity<ProblemJson> handleAppException(
      final AppException ex, final WebRequest request) {

    if (ex.getHttpStatus().is5xxServerError()) {
      TechnicalFailureLogging.logTechnicalFailure(log, "HTTP request failed", ex);
    }

    var errorResponse =
        ProblemJson.builder()
            .status(ex.getHttpStatus().value())
            .title(ex.getTitle())
            .detail(ex.getMessage())
            .build();
    return new ResponseEntity<>(errorResponse, ex.getHttpStatus());
  }

  /**
   * Handle if a {@link Exception} is raised
   *
   * @param ex {@link Exception} exception raised
   * @param request from frontend
   * @return a {@link ProblemJson} as response with the cause and with 500 as HTTP status
   */
  @ExceptionHandler({Exception.class})
  public ResponseEntity<ProblemJson> handleGenericException(
      final Exception ex, final WebRequest request) {
    TechnicalFailureLogging.logTechnicalFailure(log, "HTTP request failed", ex);
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
