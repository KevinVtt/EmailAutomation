package com.emailfilter.exception;

/**
 * Error transitorio de una API externa (Gmail/Graph) — rate limit (429),
 * sobrecarga temporal, etc. Es SEGURO reintentar: el error puede resolverse
 * solo. El {@code RetryTemplate} de {@code RetryConfig} reintenta estas
 * excepciones con backoff exponencial + jitter.
 */
public class TransientApiException extends RuntimeException {

    public TransientApiException(String message) {
        super(message);
    }

    public TransientApiException(String message, Throwable cause) {
        super(message, cause);
    }
}