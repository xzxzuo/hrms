package com.hrms.shared;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.springframework.dao.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class Errors {
  @ExceptionHandler(Problem.class)
  ResponseEntity<?> business(Problem e, HttpServletRequest r) {
    return response(e.status, e.code, r);
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    org.springframework.web.method.annotation.HandlerMethodValidationException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    IllegalArgumentException.class,
    org.springframework.web.bind.ServletRequestBindingException.class
  })
  ResponseEntity<?> invalid(Exception e, HttpServletRequest r) {
    return response(400, "INVALID_ARGUMENT", r);
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<?> forbidden(Exception e, HttpServletRequest r) {
    return response(403, "FORBIDDEN", r);
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<?> conflict(Exception e, HttpServletRequest r) {
    return response(409, "CONSTRAINT_CONFLICT", r);
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<?> unavailable(Exception e, HttpServletRequest r) {
    return response(503, "DATABASE_UNAVAILABLE", r);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unknown(Exception e, HttpServletRequest r) {
    LoggerFactory.getLogger(Errors.class)
        .error(
            "Unhandled error type={} traceId={}",
            e.getClass().getSimpleName(),
            r.getAttribute("traceId"));
    return response(500, "INTERNAL_ERROR", r);
  }

  private ResponseEntity<?> response(int status, String code, HttpServletRequest r) {
    return ResponseEntity.status(status)
        .body(Map.of("code", code, "traceId", String.valueOf(r.getAttribute("traceId"))));
  }
}
