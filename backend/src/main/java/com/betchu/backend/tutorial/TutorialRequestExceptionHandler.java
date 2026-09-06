package com.betchu.backend.tutorial;

import com.betchu.backend.common.ApiException;
import com.betchu.backend.common.ApiExceptionHandler;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = TutorialController.class)
public class TutorialRequestExceptionHandler {
  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class
  })
  public ResponseEntity<?> malformed(Exception failure) {
    ApiException error = TutorialModels.invalid();
    return ResponseEntity.status(error.status())
        .header("Content-Type", "application/problem+json")
        .body(ApiExceptionHandler.problem(error));
  }
}
