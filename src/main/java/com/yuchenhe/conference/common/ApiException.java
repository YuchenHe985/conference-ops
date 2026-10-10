package com.yuchenhe.conference.common;

import org.springframework.http.HttpStatus;

/**
 * A failure the client can act on; mapped to an RFC 9457 problem response by ApiExceptionHandler.
 */
public class ApiException extends RuntimeException {
  private final HttpStatus status;
  private final String code;

  public ApiException(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public HttpStatus status() {
    return status;
  }

  /** Stable machine-readable reason, e.g. "schedule_conflict"; the message is for people. */
  public String code() {
    return code;
  }

  public static ApiException notFound(String what, long id) {
    return new ApiException(HttpStatus.NOT_FOUND, "not_found", what + " " + id + " does not exist");
  }

  public static ApiException conflict(String code, String message) {
    return new ApiException(HttpStatus.CONFLICT, code, message);
  }

  public static ApiException rule(String code, String message) {
    return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
  }

  public static ApiException forbidden(String message) {
    return new ApiException(HttpStatus.FORBIDDEN, "forbidden", message);
  }
}
