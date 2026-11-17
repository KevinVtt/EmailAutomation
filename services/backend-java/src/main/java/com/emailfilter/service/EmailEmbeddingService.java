package com.emailfilter.service;

import com.emailfilter.model.User;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Búsqueda por contenido (RAG). Cada mail se divide en fragmentos (cabecera con asunto, remitente,
 * adjuntos y cuerpo; y el texto de adjuntos en trozos). Cada fragmento tiene un vector local y texto
 * en español. La búsqueda usa coincidencia de palabras y, si no hay ninguna, similitud vectorial.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailEmbeddingService {

    private static final int EMAILS_PER_RUN = 1000;
    private static final int EMAILS_PER_GROUP = 20;
    private static final int TEXTS_PER_CALL = 64;
    private static final int BODY_CHARS = 1500;
    private static final int CHUNK_WORDS = 500;
    private static final int OVERLAP_WORDS = 50;
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String HIT_COLUMNS = """
            SELECT c.id, c.email_id, COALESCE(e.subject, '') AS subject, COALESCE(e.from_name, '') AS from_name,
                   COALESCE(e.from_address, '') AS from_address,
                   COALESCE(to_char(e.received_at, 'YYYY-MM-DD'), '') AS received_day, c.content
            FROM email_chunk c JOIN email_messages e ON e.id = c.email_id
            WHERE c.user_id = ?""";
    private static final String VECTOR_SQL = HIT_COLUMNS + " ORDER BY c.embedding <=> CAST(? AS vector) LIMIT ?";
    private static final String TEXT_SQL = HIT_COLUMNS
            + " AND c.search_tsv @@ to_tsquery('spanish', ?) ORDER BY ts_rank(c.search_tsv, to_tsquery('spanish', ?)) DESC LIMIT ?";

    private final JdbcTemplate jdbc;
    private final AIServiceClient aiServiceClient;

    /** Fragmento recuperado con los datos para citar el mail. */
    public record ChunkHit(UUID chunkId, UUID emailId, String subject, String sender, String date, String content) {}

    private record Mail(UUID id, String subject, String fromName, String fromAddress, String preview, String html,
                        String attachmentsJson, String attachmentText) {}

    private record Chunk(UUID emailId, int index, String text) {}

    /** Indexa los mails del usuario que aún no tienen fragmentos. Los fallos solo se loguean; la siguiente corrida retoma. */
    public void indexPending(User user) {
        try {
            var pending = jdbc.query("""
                    SELECT e.id, e.subject, e.from_name, e.from_address, e.body_preview, e.body_html,
                           e.attachments_json, e.attachment_text
                    FROM email_messages e
                    WHERE e.user_id = ? AND NOT EXISTS (SELECT 1 FROM email_chunk c WHERE c.email_id = e.id)
                    ORDER BY e.received_at DESC NULLS LAST
                    LIMIT ?""",
                    (rs, i) -> new Mail(rs.getObject("id", UUID.class), rs.getString("subject"), rs.getString("from_name"),
                            rs.getString("from_address"), rs.getString("body_preview"), rs.getString("body_html"),
                            rs.getString("attachments_json"), rs.getString("attachment_text")),
                    user.getId(), EMAILS_PER_RUN);
            for (int start = 0; start < pending.size(); start += EMAILS_PER_GROUP) {
                indexGroup(user.getId(), pending.subList(start, Math.min(start + EMAILS_PER_GROUP, pending.size())));
            }
            if (!pending.isEmpty()) log.info("Fragmentos indexados: {} mails para user={}", pending.size(), user.getId());
        } catch (RuntimeException e) {
            log.warn("Indexación de fragmentos omitida para user={}: {}", user.getId(), e.getMessage());
        }
    }

    /** Todo un grupo de mails se guarda junto: si falla el modelo, ningún mail queda a medio indexar. */
    private void indexGroup(UUID userId, List<Mail> mails) {
        var chunks = new ArrayList<Chunk>();
        for (var mail : mails) {
            var texts = chunksOf(mail);
            for (int i = 0; i < texts.size(); i++) chunks.add(new Chunk(mail.id(), i, texts.get(i)));
        }
        var rows = new ArrayList<Object[]>();
        for (int start = 0; start < chunks.size(); start += TEXTS_PER_CALL) {
            var part = chunks.subList(start, Math.min(start + TEXTS_PER_CALL, chunks.size()));
            var vectors = aiServiceClient.embed(part.stream().map(Chunk::text).toList());
            for (int i = 0; i < part.size(); i++) {
                rows.add(new Object[]{userId, part.get(i).emailId(), part.get(i).index(), part.get(i).text(), toVectorLiteral(vectors.get(i))});
            }
        }
        jdbc.batchUpdate("INSERT INTO email_chunk (user_id, email_id, chunk_index, content, embedding) VALUES (?, ?, ?, ?, CAST(? AS vector)) ON CONFLICT (email_id, chunk_index) DO NOTHING", rows);
    }

    /** Fragmentos para la consulta: si alguna palabra coincide, esa búsqueda manda (más precisa); si no, similitud vectorial. */
    public List<ChunkHit> searchChunks(UUID userId, String query, int limit) {
        var terms = tsquery(query);
        if (!terms.isEmpty()) {
            var words = jdbc.query(TEXT_SQL, HIT_MAPPER, userId, terms, terms, limit);
            if (!words.isEmpty()) return words;
        }
        try {
            var vector = toVectorLiteral(aiServiceClient.embed(List.of(query)).get(0));
            return jdbc.query(VECTOR_SQL, HIT_MAPPER, userId, vector, limit);
        } catch (RuntimeException e) {
            log.warn("Búsqueda vectorial omitida para user={}: {}", userId, e.getMessage());
            return List.of();
        }
    }

    private static final RowMapper<ChunkHit> HIT_MAPPER = (rs, i) -> {
        var name = rs.getString("from_name");
        var address = rs.getString("from_address");
        var sender = name.isBlank() ? address : name + " <" + address + ">";
        return new ChunkHit(rs.getObject("id", UUID.class), rs.getObject("email_id", UUID.class),
                rs.getString("subject"), sender, rs.getString("received_day"), rs.getString("content"));
    };

    /** Cabecera (asunto, remitente, adjuntos, tipo de adjunto, inicio del cuerpo) y trozos del texto de adjuntos. */
    private static List<String> chunksOf(Mail mail) {
        var files = attachmentNames(mail.attachmentsJson());
        var body = mail.html() != null && !mail.html().isBlank() ? mail.html().replaceAll("<[^>]+>", " ") : nz(mail.preview());
        body = body.replaceAll("\\s+", " ").trim();
        if (body.length() > BODY_CHARS) body = body.substring(0, BODY_CHARS);
        var header = "De: %s <%s>\nAsunto: %s\nAdjuntos: %s\nTipo: %s\n%s".formatted(
                nz(mail.fromName()), nz(mail.fromAddress()), nz(mail.subject()),
                String.join(", ", files), typeWords(files), body);
        var chunks = new ArrayList<String>(List.of(header));
        var words = nz(mail.attachmentText()).trim().split("\\s+");
        if (words.length > 0 && !words[0].isEmpty()) {
            for (int start = 0; start < words.length; start += CHUNK_WORDS - OVERLAP_WORDS) {
                int end = Math.min(start + CHUNK_WORDS, words.length);
                chunks.add("Asunto: %s\n%s".formatted(nz(mail.subject()), String.join(" ", Arrays.copyOfRange(words, start, end))));
                if (end == words.length) break;
            }
        }
        return chunks;
    }

    private static List<String> attachmentNames(String json) {
        var files = new ArrayList<String>();
        try {
            if (json != null && !json.isBlank()) {
                List<Map<String, Object>> list = JSON.readValue(json, new TypeReference<>() {});
                list.forEach(a -> files.add(String.valueOf(a.get("filename"))));
            }
        } catch (Exception ignored) {
            // JSON de adjuntos corrupto: se indexa sin nombres de archivo.
        }
        return files;
    }

    /** Palabras que describen el tipo de archivo, para que "documento Word" encuentre un .docx. */
    private static String typeWords(List<String> files) {
        var words = new LinkedHashSet<String>();
        for (var file : files) {
            var name = file.toLowerCase(Locale.ROOT);
            if (name.endsWith(".doc") || name.endsWith(".docx")) words.add("word documento");
            else if (name.endsWith(".xls") || name.endsWith(".xlsx") || name.endsWith(".csv")) words.add("excel planilla");
            else if (name.endsWith(".ppt") || name.endsWith(".pptx")) words.add("powerpoint presentacion");
            else if (name.endsWith(".pdf")) words.add("pdf");
            else if (name.matches(".*\\.(png|jpe?g|gif|webp)$")) words.add("imagen foto");
        }
        return String.join(" ", words);
    }

    /** Términos OR para to_tsquery (solo letras y dígitos, así no hay sintaxis que escapar). */
    private static String tsquery(String query) {
        return Arrays.stream(query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(w -> w.length() >= 3)
                .distinct()
                .collect(Collectors.joining(" | "));
    }

    private static String nz(String value) {
        return value != null ? value : "";
    }

    private static String toVectorLiteral(List<?> values) {
        return values.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
    }
}
