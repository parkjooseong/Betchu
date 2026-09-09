package com.betchu.backend.couples;

import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = CoupleController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CoupleRequestExceptionHandler {
  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class
  })
  public ResponseEntity<Map<String, Object>> malformedRequest() {
    return ResponseEntity.badRequest()
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(
            Map.of(
                "type",
                "about:blank",
                "title",
                "Bad Request",
                "status",
                400,
                "errorCode",
                "INVALID_COUPLE_REQUEST",
                "detail",
                "초대 코드와 연결 요청을 다시 확인해 주세요."));
  }
}
