package com.betchu.backend.common;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
  private final HttpStatus status;
  private final String errorCode;

  public ApiException(HttpStatus status, String errorCode, String detail) {
    super(detail);
    this.status = status;
    this.errorCode = errorCode;
  }

  public HttpStatus status() {
    return status;
  }

  public String errorCode() {
    return errorCode;
  }
}
