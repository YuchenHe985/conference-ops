package com.yuchenhe.conference.common;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error leaves the API as application/problem+json (RFC 9457) with a stable "code". Spring's
 * own MVC errors (unknown path, wrong method, unreadable body) keep their proper status through the
 * base class instead of falling into a 500.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
  private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(ApiException.class)
  ProblemDetail api(ApiException e) {
    return problem(e.status(), e.code(), e.getMessage());
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException e,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    Map<String, String> fields = new LinkedHashMap<>();
    e.getBindingResult()
        .getFieldErrors()
        .forEach(f -> fields.putIfAbsent(f.getField(), f.getDefaultMessage()));
    ProblemDetail p = problem(HttpStatus.BAD_REQUEST, "validation_failed", "Request is invalid");
    p.setProperty("fields", fields);
    return ResponseEntity.badRequest().body(p);
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ProblemDetail mismatch(MethodArgumentTypeMismatchException e) {
    return problem(
        HttpStatus.BAD_REQUEST, "malformed_request", "Parameter '" + e.getName() + "' is invalid");
  }

  @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
  ProblemDetail stale(ObjectOptimisticLockingFailureException e) {
    return problem(
        HttpStatus.CONFLICT,
        "stale_version",
        "Someone else changed this record; reload it and apply your change again");
  }

  @ExceptionHandler(AccessDeniedException.class)
  ProblemDetail denied(AccessDeniedException e) {
    return problem(HttpStatus.FORBIDDEN, "forbidden", "Not allowed for your role");
  }

  @ExceptionHandler(Exception.class)
  ProblemDetail unexpected(Exception e) {
    LOG.error("unhandled error", e);
    return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Unexpected error");
  }

  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception ex, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    ResponseEntity<Object> r = super.handleExceptionInternal(ex, body, headers, status, request);
    if (r != null && r.getBody() instanceof ProblemDetail p && p.getProperties() == null) {
      p.setProperty("code", "http_" + status.value());
    }
    return r;
  }

  public static ProblemDetail problem(HttpStatus status, String code, String detail) {
    ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
    p.setProperty("code", code);
    return p;
  }
}
