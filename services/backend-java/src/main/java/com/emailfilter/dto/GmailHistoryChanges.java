package com.emailfilter.dto;

import java.util.Set;

/**
 * Cambios del buzón de Gmail desde un {@code historyId}:
 * {@code addedIds} son mensajes nuevos, {@code deletedIds} son los que salieron
 * del alcance sincronizado (borrados o movidos a la papelera) y
 * {@code latestHistoryId} es el cursor para el próximo sync.
 */
public record GmailHistoryChanges(Set<String> addedIds, Set<String> deletedIds, String latestHistoryId) {
}
