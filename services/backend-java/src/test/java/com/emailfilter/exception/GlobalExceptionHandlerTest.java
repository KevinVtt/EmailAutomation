package com.emailfilter.exception;

import org.junit.jupiter.api.Test;

import java.time.format.DateTimeParseException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void notFound_is404() {
        var response = handler.handleNotFound(new ResourceNotFoundException("no existe"));

        assertEquals(404, response.getStatusCode().value());
        assertEquals("no existe", response.getBody().get("error"));
    }

    @Test
    void badInput_is400() {
        assertEquals(400, handler.handleBadRequest(new IllegalArgumentException("acción inválida"))
                .getStatusCode().value());
        assertEquals(400, handler.handleBadRequest(new DateTimeParseException("fecha", "x", 0))
                .getStatusCode().value());
    }
}
