package com.emailfilter.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * Errores esperables del API: un id que no existe es 404 y una entrada inválida
 * es 400. Antes ambos terminaban como 500 con un stacktrace.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(ResourceNotFoundException e) {
        return ResponseEntity.status(404).body(error(e));
    }

    @ExceptionHandler({IllegalArgumentException.class, DateTimeParseException.class})
    public ResponseEntity<Map<String, String>> handleBadRequest(Exception e) {
        return ResponseEntity.badRequest().body(error(e));
    }

    private static Map<String, String> error(Exception e) {
        return Map.of("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
    }
}
