# EmailFilter AI — Auditoría Completa

**Fecha:** 2026-07-17  
**Archivos revisados:** 54  
**Servicios auditados:** Backend Java, AI Python, Frontend React

---

## Resumen Ejecutivo

Se encontraron **50 issues** en total: **5 critical**, **12 high**, **18 medium**, **15 low**. Los problemas más graves son: (1) el frontend suscribe WebSocket con userId incorrecto, nunca recibe mensajes; (2) el repositorio de emails no filtra por userId en la dedup, causando emails perdidos; (3) el cliente AI no tiene timeout configurado, bloqueando threads indefinidamente; (4) la sincronización de emails ejecuta N+1 queries; y (5) Gmail API no maneja rate limits (429). Hay 4 violaciones de contrato cross-service que rompen la integración entre servicios.

---

## Issues por Severidad

### CRITICAL (5)

| ID | Servicio | Archivo | Descripción |
|----|----------|---------|-------------|
| ISSUE-005 | frontend | hooks/useWebSocket.js | subscribeToUser() usa userId='queue' hardcoded en lugar del userId real. Frontend NUNCA recibe mensajes WebSocket. |
| ISSUE-001 | backend-java | repository/EmailRepository.java | existsByProviderEmailId() no filtra por userId. Emails se pierden entre usuarios. |
| ISSUE-002 | backend-java | service/AIServiceClient.java | RestTemplate sin timeout. Threads se cuelgan indefinidamente si el AI service está lento. |
| ISSUE-004 | backend-java | service/EmailService.java | saveEmails() ejecuta N+1 queries (400 queries para 200 emails). Sync extremadamente lento. |
| ISSUE-003 | backend-java | service/GmailService.java | Sin manejo de rate limits (429) de Gmail API. Sync falla permanentemente. |

### HIGH (12)

| ID | Servicio | Archivo | Descripción |
|----|----------|---------|-------------|
| ISSUE-008 | backend-java | config/SecurityConfig.java | WebSocket (/ws/**) no requiere JWT. Datos expuestos a usuarios anónimos. |
| ISSUE-009 | backend-java | config/WebSocketConfig.java | WebSocket permite cualquier origen (allowedOriginPatterns('*')). Cross-site attack. |
| ISSUE-010 | backend-java | config/CorsConfig.java | CORS permite cualquier header. Header injection posible. |
| ISSUE-014 | ai-python | services/ai_service.py | Cada request crea httpx.AsyncClient nuevo que nunca se cierra. Memory leak. |
| ISSUE-013 | ai-python | services/ai_service.py | Sin retry para errores 429/5xx. Errores transitorios causan falla inmediata. |
| ISSUE-015 | ai-python | services/chat_agent.py | No valida estructura del JSON parseado. Respuesta inválida pasa silenciosamente. |
| ISSUE-016 | ai-python | services/email_analyzer.py | Procesa emails secuencialmente. 200 emails = 200 segundos. |
| ISSUE-018 | backend-java | service/AIServiceClient.java | chat() no envía campo 'context'. Contexto de conversación perdido. |
| ISSUE-019 | ai-python | routers/chat.py | Endpoint /summarize en /chat/summarize. Java llama a /summarize → 404. |
| ISSUE-007 | backend-java | service/GmailService.java | Solo extrae text/plain, ignora text/html. Emails vacíos para HTML-only. |
| ISSUE-011 | backend-java | service/EmailService.java | LIKE injection en filterEmails(). Caracteres % y _ no escapados. |
| ISSUE-017 | ai-python | models/enums.py | EmailLabel enum no coincide con AGENTS.md. Valores lowercase sin CATEGORY_ prefix. |

### MEDIUM (18)

| ID | Servicio | Archivo | Descripción |
|----|----------|---------|-------------|
| ISSUE-020 | frontend | components/EmailDetail.jsx | Botones de acción (Star, Read, Trash) sin handlers onClick. |
| ISSUE-022 | backend-java | controller/ChatController.java | extractCriteria() no valida estructura de respuesta AI. |
| ISSUE-023 | backend-java | controller/EmailController.java | performAction() sin null checks en campos del request. |
| ISSUE-024 | backend-java | controller/EmailController.java | syncEmails() no valida provider null. |
| ISSUE-025 | ai-python | models/schemas.py | ChatRequest sin campo 'context'. |
| ISSUE-026 | ai-python | main.py | CORS permite todos los orígenes. |
| ISSUE-027 | backend-java | service/GmailService.java | parseReceivedAt() puede retornar null. |
| ISSUE-028 | backend-java | service/AuthService.java | RuntimeException genérico en findByEmail(). |
| ISSUE-029 | backend-java | service/EmailService.java | catch Exception genérico en syncEmailsAsync(). |
| ISSUE-030 | backend-java | service/OutlookService.java | Sin paginación. Buzones >100 emails truncados. |
| ISSUE-031 | backend-java | service/AIServiceClient.java | summarizeEmails() llama a ruta incorrecta. |
| ISSUE-032 | frontend | components/ChatPanel.jsx | console.log() extensivos en producción. |
| ISSUE-033 | frontend | lib/api.js | refreshToken() sin mutex. Race condition. |
| ISSUE-034 | frontend | components/EmailDetail.jsx | dangerouslySetInnerHTML sin sanitización. XSS. |
| ISSUE-035 | frontend | context/AuthContext.jsx | No valida token al montar. |
| ISSUE-036 | frontend | lib/websocket.js | Sin manejo de errores visible para usuario. |
| ISSUE-037 | frontend | components/EmailList.jsx | Sin estado de error. |
| ISSUE-021 | backend-java | controller/AuthController.java | System.getenv() directo en lugar de @Value. |

### LOW (15)

| ID | Servicio | Archivo | Descripción |
|----|----------|---------|-------------|
| ISSUE-006 | backend-java | service/OutlookService.java | Dead code: 'var flags = accessToken;' sin usar. |
| ISSUE-038 | backend-java | repository/OAuthTokenRepository.java | @Repository inconsistente. |
| ISSUE-039 | backend-java | service/GmailService.java | GoogleCredential deprecado. |
| ISSUE-040 | backend-java | service/EmailService.java | @SuppressWarnings en casts inseguros. |
| ISSUE-041 | ai-python | config.py | .env path puede fallar en Docker. |
| ISSUE-042 | backend-java | controller/AuthController.java | Mensajes de error pueden filtrar info sensible. |
| ISSUE-043 | frontend | hooks/useWebSocket.js | Cleanup coincide con topics incorrectos. |
| ISSUE-044 | frontend | components/ChatPanel.jsx | messagesEndRef inicialización innecesaria. |
| ISSUE-045 | frontend | lib/auth.js | catch vacío sin logging. |
| ISSUE-046 | frontend | components/EmailCard.jsx | Sin loading states para acciones. |
| ISSUE-047 | frontend | lib/api.js | parsea response como JSON sin verificar Content-Type. |
| ISSUE-048 | backend-java | service/EmailService.java | ConcurrentHashMap puede crecer indefinidamente. |
| ISSUE-049 | ai-python | services/chat_agent.py | MD5 para conversation_id. Débil criptográficamente. |
| ISSUE-050 | backend-java | dto/FilterRequest.java | DTO no utilizado (dead code). |
| ISSUE-012 | backend-java | service/EmailService.java | refreshToken() sin manejo de errores específico. |

---

## Violaciones de Contrato Cross-Service

### 1. Chat API Contract
- **Documentado:** Backend envía `{message, conversation_id, context}` al servicio AI
- **Real:** Backend envía `{message, conversation_id}` sin campo `context`. Servicio AI schema tampoco lo define.
- **Archivos afectados:** `AIServiceClient.java`, `schemas.py`

### 2. WebSocket Endpoints  
- **Documentado:** Frontend se suscribe a `/user/{userId}/queue/emails`, `/user/{userId}/queue/chat`
- **Real:** Frontend se suscribe a `/user/queue/emails` (userId='queue' hardcoded incorrectamente)
- **Archivos afectados:** `useWebSocket.js`, `websocket.js`

### 3. Summarize Endpoint
- **Documentado:** No explícitamente documentado en AGENTS.md
- **Real:** Java llama a `/summarize`, Python expone en `/chat/summarize`
- **Archivos afectados:** `AIServiceClient.java`, `chat.py`, `main.py`

### 4. Email Labels
- **Documentado:** AGENTS.md: SPAM, IMPORTANT, CATEGORY_UPDATES, CATEGORY_SOCIAL, CATEGORY_PROMOTIONS, CATEGORY_FORUMS
- **Real:** Python enum usa: spam, important, updates, social, promotions, forums (lowercase, sin CATEGORY_ prefix)
- **Archivos afectados:** `enums.py`

---

## Orden Recomendado de Fixes

Los issues deben resolverse en este orden para maximizar la estabilidad del sistema:

1. **ISSUE-005** — Frontend WebSocket userId='queue' (CRITICAL) — Sin esto, el frontend no recibe ningún dato en tiempo real. Todo el flujo de chat con filtros está roto.

2. **ISSUE-001** — EmailRepository.existsByProviderEmailId sin userId (CRITICAL) — Emails se pierden durante sincronización multi-usuario.

3. **ISSUE-002** — AIServiceClient sin timeout (CRITICAL) — Threads del backend se cuelgan indefinidamente, eventualmente agotando el pool.

4. **ISSUE-004** — EmailService N+1 en saveEmails (CRITICAL) — Sincronización de 200 emails ejecuta 400 queries. Extremadamente lento.

5. **ISSUE-003** — GmailService sin rate limits (CRITICAL) — Sincronización de buzones grandes falla sin retry.

6. **ISSUE-008** — SecurityConfig WebSocket sin auth (HIGH) — Datos de emails expuestos a usuarios anónimos.

7. **ISSUE-009** — WebSocketConfig allowedOriginPatterns '*' (HIGH) — Cross-site WebSocket hijacking posible.

8. **ISSUE-010** — CorsConfig allowedHeaders '*' (HIGH) — Header injection posible.

9. **ISSUE-014** — AIService memory leak (HIGH) — Cada request crea HTTP client nuevo sin cerrar.

10. **ISSUE-013** — AIService sin retry (HIGH) — Errores transitorios causan falla inmediata.

11. **ISSUE-015** — chat_agent sin validación JSON (HIGH) — Respuesta inválida pasa silenciosamente.

12. **ISSUE-016** — email_analyzer secuencial (HIGH) — Análisis de lote extremadamente lento.

13. **ISSUE-018** — AIServiceClient chat sin context (HIGH) — Contexto de conversación perdido.

14. **ISSUE-019** — Summarize endpoint path mismatch (HIGH) — Función de resumen retorna 404.

15. **ISSUE-007** — GmailService solo text/plain (HIGH) — Emails vacíos para HTML-only.

16. **ISSUE-011** — filterEmails LIKE injection (HIGH) — Seguridad comprometida.

17. **ISSUE-012** — refreshToken sin manejo de errores (HIGH) — Tokens expirados sin recuperación.

18. **ISSUE-017** — EmailLabel enum mismatch (HIGH) — Labels no coinciden con contrato.

19. **ISSUE-020** — EmailDetail action buttons sin handlers (HIGH) — UI no funcional.

---

*Generado por auditoría automática — 2026-07-17*
