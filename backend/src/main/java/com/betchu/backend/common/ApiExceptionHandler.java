package com.betchu.backend.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
  public record Problem(String type, String title, int status, String detail, String errorCode) {}

  public static Problem problem(ApiException error) {
    return new Problem(
        "about:blank",
        error.status().getReasonPhrase(),
        error.status().value(),
        error.getMessage(),
        error.errorCode());
  }

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<Problem> handle(ApiException error) {
    return ResponseEntity.status(error.status())
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(problem(error));
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentNotValidException.class,
    MethodArgumentTypeMismatchException.class
  })
  public ResponseEntity<Problem> invalidRequest() {
    return handle(new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 형식을 확인해 주세요."));
  }
}
