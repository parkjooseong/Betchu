package com.betchu.backend.starter;

import com.betchu.backend.starter.StarterModels.SelectionProblem;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = StarterController.class)
public class StarterExceptionHandler {

  @ExceptionHandler({InvalidStarterSelectionException.class, HttpMessageNotReadableException.class})
  public ResponseEntity<SelectionProblem> invalidSelection() {
    return ResponseEntity.badRequest()
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(
            new SelectionProblem(
                "about:blank",
                "Bad Request",
                400,
                new InvalidStarterSelectionException().getMessage(),
                "INVALID_STARTER_SELECTION"));
  }
}
