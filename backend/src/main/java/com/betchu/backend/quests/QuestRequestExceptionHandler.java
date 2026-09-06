package com.betchu.backend.quests;

import com.betchu.backend.common.ApiException;
import com.betchu.backend.common.ApiExceptionHandler;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = QuestController.class)
public class QuestRequestExceptionHandler {
  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    MissingServletRequestParameterException.class
  })
  ResponseEntity<?> malformed(Exception error) {
    ApiException safe = QuestValidation.invalid();
    return ResponseEntity.status(safe.status())
        .header("Content-Type", "application/problem+json")
        .body(ApiExceptionHandler.problem(safe));
  }
}
