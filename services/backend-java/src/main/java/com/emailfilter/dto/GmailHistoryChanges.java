package com.emailfilter.dto;

import java.util.Set;

/**
 * Cambios del buzón de Gmail desde un {@code historyId}:
 * {@code addedIds} son mensajes nuevos, {@code deletedIds} son los borrados de verdad,
 * {@code trashedIds} / {@code untrashedIds} se marcan o desmarcan como papelera local (la fila se conserva),
 * {@code labelsChanged} indica que hay que refrescar estrellas/spam y
 * {@code latestHistoryId} es el cursor para el próximo sync.
 */
public record GmailHistoryChanges(Set<String> addedIds, Set<String> deletedIds, Set<String> trashedIds,
                                  Set<String> untrashedIds, boolean labelsChanged, String latestHistoryId) {
}
