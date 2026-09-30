package com.vicinity24.core.linkedstore.api.returns.controller;

import com.vicinity24.core.linkedstore.api.returns.dto.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@RestControllerAdvice(basePackages = {"com.vicinity24.core.linkedstore.api.returns.controller"})
public class AdminReturnsExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
        String reason = ex.getReason() != null ? ex.getReason() : status.getReasonPhrase();
        int value = status.value();
        if (value >= 400 && value < 500) {
            log.warn("Returns ResponseStatus {}: {}", value, reason);
        } else {
            log.error("Returns ResponseStatus {}: {}", value, reason, ex);
        }
        ErrorResponse body = new ErrorResponse(reason, value, status.getReasonPhrase());
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        String message = ex.getMessage() != null ? ex.getMessage() : "Bad request";
        log.warn("Returns IllegalArgumentException: {}", message);
        ErrorResponse body = new ErrorResponse(message, HttpStatus.BAD_REQUEST.value(), HttpStatus.BAD_REQUEST.getReasonPhrase());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getMessage() != null ? ex.getMessage() : "Validation failed";
        log.warn("Returns ConstraintViolation: {}", message);
        ErrorResponse body = new ErrorResponse(message, HttpStatus.BAD_REQUEST.value(), HttpStatus.BAD_REQUEST.getReasonPhrase());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
