package com.emailfilter.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * Resumen priorizado para el Dashboard — reduce carga cognitiva mostrando
 * tres números grandes en vez de una lista cruda.
 *
 * <p>Los tres conteos usan PROXIES sobre campos existentes (no hay columna
 * {@code urgency} en {@code email_messages}):
 * <ul>
 *   <li><b>urgentes</b> = sin leer + etiqueta IMPORTANT (Gmail la marca automáticamente)</li>
 *   <li><b>destacadosSinLeer</b> = (destacado OR IMPORTANT) sin leer — misma semántica que el filtro "destacados"</li>
 *   <li><b>pendientesRespuesta</b> = recibidos sin leer (los enviados NO se sincronizan:
 *       solo inbox+spam, así que un conteo por hilo "sin reply" contaría todo)</li>
 * </ul>
 */
@Data
@Builder
@AllArgsConstructor
public class PrioritySummaryDTO {
    private long urgentes;
    private long destacadosSinLeer;
    private long pendientesRespuesta;
}