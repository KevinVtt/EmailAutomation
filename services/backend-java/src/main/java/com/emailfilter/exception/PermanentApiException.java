package com.emailfilter.exception;

/**
 * Error permanente de una API externa (Gmail/Graph) — 401/403 (token vencido,
 * permisos revocados), 400 (payload inválido), etc. Reintentar NUNCA lo
 * arregla, por eso NO debe ser reintentado por el {@code RetryTemplate}.
 */
public class PermanentApiException extends RuntimeException {

    public PermanentApiException(String message) {
        super(message);
    }

    public PermanentApiException(String message, Throwable cause) {
        super(message, cause);
    }
}