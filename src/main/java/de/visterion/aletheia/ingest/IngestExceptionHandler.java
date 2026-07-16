package de.visterion.aletheia.ingest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Global exception mapping (RFC 7807) for the ingest endpoint. {@code ResponseStatusException}
 * thrown by {@link IngestController} is already rendered as {@code ProblemDetail} by Spring Boot,
 * so this advice only covers the cases raised outside the controller: the framework's own
 * multipart-size guard and the parser's sanitized bad-input signal.
 */
@RestControllerAdvice
public class IngestExceptionHandler {

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ProblemDetail tooLarge(MaxUploadSizeExceededException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, "file too large");
  }

  @ExceptionHandler(InvalidExportException.class)
  public ProblemDetail invalidExport(InvalidExportException e) {
    // e.getMessage() must already be sanitized (no banking content).
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
  }
}
