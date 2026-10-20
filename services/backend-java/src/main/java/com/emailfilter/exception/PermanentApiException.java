package com.emailfilter.exception;

/**
 * Error permanente de una API externa (Gmail/Graph) — 401/403 (token vencido,
 * permisos revocados), 400 (payload inválido), etc. Reintentar NUNCA lo
 * arregla, por eso NO debe ser reintentado por el {@code RetryTemplate}.
 */
public class PermanentApiException extends RuntimeException {

    private final int statusCode;

    public PermanentApiException(String message) {
        this(message, null, 0);
    }

    public PermanentApiException(String message, Throwable cause) {
        this(message, cause, 0);
    }

    public PermanentApiException(String message, Throwable cause, int statusCode) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /** HTTP status del proveedor, o 0 si no se conoce. */
    public int getStatusCode() {
        return statusCode;
    }
}