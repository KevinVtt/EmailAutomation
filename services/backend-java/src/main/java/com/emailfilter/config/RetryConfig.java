package com.emailfilter.config;

import com.emailfilter.exception.TransientApiException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.ExponentialRandomBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

import java.util.Map;

/**
 * Retry con backoff exponencial + jitter para llamadas a APIs externas
 * (Gmail / Microsoft Graph). Respeta el header {@code Retry-After} en los
 * servicios que lo leen (GmailService / OutlookService).
 *
 * @see com.emailfilter.exception.TransientApiException
 * @see com.emailfilter.exception.PermanentApiException
 */
@Configuration
public class RetryConfig {

    @Bean
    public RetryTemplate externalApiRetryTemplate() {
        RetryTemplate template = new RetryTemplate();

        // Solo se reintenta TransientApiException (429/5xx/red). PermanentApiException
        // (401/403/4xx) no se arregla reintentando — no quemar los 5 intentos.
        // SimpleRetryPolicy no expone setRetryableExceptions: se pasa por constructor.
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(
                5, Map.<Class<? extends Throwable>, Boolean>of(TransientApiException.class, true));
        template.setRetryPolicy(retryPolicy);

        // Backoff exponencial con jitter (evita que todos los reintentos
        // caigan al mismo tiempo si varios usuarios syncean a la vez)
        ExponentialRandomBackOffPolicy backOff = new ExponentialRandomBackOffPolicy();
        backOff.setInitialInterval(1000);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(30000);
        template.setBackOffPolicy(backOff);

        return template;
    }
}