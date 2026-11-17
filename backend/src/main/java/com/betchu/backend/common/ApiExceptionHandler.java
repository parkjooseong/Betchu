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
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler.class);

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

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Problem> unexpected(
      Exception error, jakarta.servlet.http.HttpServletResponse response) {
    if (error instanceof org.springframework.web.ErrorResponse framework
        && framework.getStatusCode().is4xxClientError())
      return handle(
          new ApiException(
              HttpStatus.valueOf(framework.getStatusCode().value()),
              "INVALID_REQUEST",
              "요청 경로와 형식을 확인해 주세요."));
    // Exception messages and stack traces can contain SQL rows, tokens or provider payloads.
    LOG.error(
        "Request failed requestId={} type={}",
        response.getHeader("X-Request-Id"),
        error.getClass().getSimpleName());
    return handle(
        new ApiException(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "INTERNAL_ERROR",
            "요청을 처리하지 못했어요. 잠시 후 현재 상태를 다시 확인해 주세요."));
  }
}
