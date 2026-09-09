# EmailFilter AI - Arquitectura y Guía Completa

Este documento describe la aplicación **EmailFilter AI** de punta a punta: qué hace, cómo está construida, cómo se comunican sus servicios y dónde están sus puntos débiles. Está escrito para que otra IA pueda entender el sistema sin leer el código completo y proponer mejoras con fundamento.

Todo lo afirmado aquí se verificó contra el código real del monorepo en `E:\Proyectos\MailAutomation` (estado a septiembre de 2026, con las features recientes: envío de replies, resumen diario con IA, filtro `destacados`, urgencia/SLA, fix camelCase y fix `/error` permitAll). Donde algo no pudo verificarse se marca explícitamente como **(verificar)**. No se incluye ningún secreto, solo nombres de variables.

---

## Resumen ejecutivo

EmailFilter AI es una aplicación web fullstack que conecta las cuentas de Gmail y Outlook del usuario vía OAuth, sincroniza sus emails a una base PostgreSQL local y ofrece un chat en lenguaje natural (español e inglés) para buscar, filtrar y clasificar esos emails con ayuda de un modelo de IA.

El sistema tiene tres servicios más la base de datos. El backend Spring Boot (puerto 8080) es el orquestador: expone la API REST, autentica con JWT, habla con Gmail API y Microsoft Graph, persiste en PostgreSQL y delega en el servicio de IA (FastAPI, puerto 8000) las tareas de lenguaje: extraer criterios de filtro del mensaje del usuario, resumir emails del día, clasificar con categorías y urgencia, y mejorar borradores de respuesta. El frontend React (puerto 3001 en Docker vía nginx, 5173 en desarrollo local con Vite) consume REST para datos y WebSocket STOMP para actualizaciones en tiempo real.

El flujo estrella es el chat con IA. El usuario escribe algo como "mostrame los emails importantes de esta semana", el backend reenvía el mensaje al servicio Python, este devuelve `{response, criteria}`, el backend traduce esos criterios a JPA Specifications, filtra en PostgreSQL y empuja los resultados al navegador por WebSocket. Todo el servicio de IA es stateless: nunca toca la base de datos.

El volumen real ronda los 6.143 emails sincronizados por el usuario principal (snapshot septiembre 2026), con 254 destacados (etiqueta IMPORTANT de Gmail) y 15 en spam. La suite de tests suma unos 166 tests entre los tres servicios (71 backend, 65 frontend, 30 IA), con un único error preexistente en un test de integración que requiere Docker dentro de Docker.

---

## Arquitectura general

### Diagrama de comunicación

```
Browser (React 18 + Vite)
  |
  |-- REST  /api/**  --------------------> Backend Java Spring Boot :8080
  |-- WS    /ws (SockJS + STOMP) -------->   |
                                             |-- PostgreSQL :5433 (5432 dentro de Docker)
                                             |-- Gmail API (googleapis gmail v1)
                                             |-- Microsoft Graph (Outlook)
                                             |-- HTTP --> Servicio IA FastAPI :8000
                                                            |
                                                            +--> Groq API (OpenAI-compatible)
```

### Servicios, puertos y contenedores

| Contenedor Docker | Servicio | Tecnología | Puerto host | Puerto interno |
|---|---|---|---|---|
| `emailfilter-db` | PostgreSQL 16 | `postgres:16-alpine`, volumen `postgres_data` | 5433 | 5432 |
| `emailfilter-backend` | API + WebSocket | Spring Boot 3.2.4, Java 17 | 8080 | 8080 |
| `emailfilter-ai` | Servicio de IA | FastAPI + uvicorn | 8000 | 8000 |
| `emailfilter-frontend` | UI web | React build estático servido por nginx | 3001 | 80 |

En desarrollo local sin Docker el frontend corre en el puerto 5173 (Vite dev server con proxy a `/api` y `/ws`), y PostgreSQL se espera en `localhost:5432`. Los contenedores tienen límites de recursos definidos en `docker-compose.yml` (backend 768m/1 CPU, IA 512m/1 CPU, DB 512m/1 CPU, frontend 128m/0.5 CPU). El backend apunta al servicio IA por nombre interno: `AI_SERVICE_URL=http://ai-python:8000`.

---

## Stack tecnológico

### Backend (`services/backend-java/`)

| Capa | Elección |
|---|---|
| Lenguaje | Java 17 |
| Framework | Spring Boot 3.2.4 (parent POM), Maven |
| Web | `spring-boot-starter-web` (REST), `spring-boot-starter-websocket` (STOMP + SockJS) |
| Seguridad | `spring-boot-starter-security`, OAuth2 client, jjwt 0.12.5 (HMAC-SHA) |
| Persistencia | `spring-boot-starter-data-jpa`, Hibernate, driver PostgreSQL (runtime) |
| Gmail | Google API Client + Gmail API v1 (`Gmail.Builder` con Bearer token) |
| Outlook | `RestTemplate` directo contra Microsoft Graph v1.0 |
| Cliente IA | `RestTemplate` propio en `AIServiceClient` (connect timeout 5s, read timeout 30s) |
| Utilidades | Lombok (`@Data`, `@Builder`, `@RequiredArgsConstructor`, `@Slf4j`), validación Jakarta |
| Paquete raíz | `com.emailfilter` (controller, service, model, repository, security, config, dto, exception) |

### Frontend (`services/frontend/`)

| Capa | Elección (versión en `package.json`) |
|---|---|
| UI | React 18.2, ReactDOM 18.2, React Router 6.22 |
| Build | Vite 5.1 + `@vitejs/plugin-react` 4.2 |
| Tiempo real | `@stomp/stompjs` 7.0 + `sockjs-client` 1.6 |
| Estilos | TailwindCSS 3.4, PostCSS, Autoprefixer; clases propias `btn-primary`, `card`, `input` |
| Iconos/fechas | `lucide-react` 0.364, `date-fns` 3.6, `clsx` 2.1 |
| Markdown | `react-markdown` 10.1 + `remark-gfm` 4.0 (tablas GFM del resumen diario) |
| Tests | Vitest 1.4, Testing Library React 14.2, jest-dom 6.4, jsdom 24 |

### IA (`services/ai-python/`)

| Capa | Elección |
|---|---|
| Lenguaje | Python 3.10+ local, 3.12 en Docker/tests |
| Framework | FastAPI + uvicorn, `pydantic-settings` para config |
| Cliente LLM | Compatible OpenAI vía `AI_API_URL` (por defecto Groq), modelo `openai/gpt-oss-120b` |
| Routers | `health`, `analyze` (prefijo `/analyze`), `chat` (prefijo `/chat`), `rewrite` (prefijo `/rewrite`) |
| Servicios | `ai_service.py` (singleton `AIService`), `chat_agent.py`, `email_analyzer.py`, `rewrite_agent.py` (verificar nombre exacto del módulo) |
| Modelos | `schemas.py` (Pydantic v2), `enums.py` |

---

## Modelo de datos

PostgreSQL 16, schema en `infrastructure/postgres/init/01-init.sql` (extensión `pgcrypto` para `gen_random_uuid()`). Todas las PK son UUID con default `gen_random_uuid()`.

### `users`

| Columna | Tipo | Notas |
|---|---|---|
| `id` | UUID PK | |
| `email` | VARCHAR(255) NOT NULL UNIQUE | Búsqueda por email en `AuthService.findByEmail` |
| `name` | VARCHAR(255) | |
| `provider` | VARCHAR(50) NOT NULL | `google` u `outlook` |
| `provider_id` | VARCHAR(255) | ID del usuario en el proveedor OAuth |
| `avatar_url` | TEXT | Google: `picture` de userinfo; Outlook: foto Graph en base64 o `""` |
| `created_at`, `updated_at` | TIMESTAMPTZ default `NOW()` | |

### `oauth_tokens`

| Columna | Tipo | Notas |
|---|---|---|
| `id` | UUID PK | |
| `user_id` | UUID FK a `users(id)` ON DELETE CASCADE | |
| `provider` | VARCHAR(50) NOT NULL | |
| `access_token` | TEXT NOT NULL | Token del proveedor (no el JWT propio) |
| `refresh_token` | TEXT | Puede ser vacío; solo se sobrescribe si llega uno no vacío |
| `token_type` | VARCHAR(50) default `Bearer` | |
| `scope` | TEXT | |
| `expires_at` | TIMESTAMPTZ | Calculado con `expires_in`; `getToken` refresca si venció |
| `created_at`, `updated_at` | TIMESTAMPTZ default `NOW()` | Upsert por `(userId, provider)`: actualiza el más reciente |

### `email_messages` (tabla central)

| Columna | Tipo | Notas |
|---|---|---|
| `id` | UUID PK | ID interno; el frontend usa `providerEmailId` para acciones y replies |
| `user_id` | UUID FK a `users(id)` ON DELETE CASCADE | |
| `provider` | VARCHAR(50) NOT NULL | `google` u `outlook` |
| `provider_email_id` | VARCHAR(255) NOT NULL | ID del mensaje en Gmail/Graph |
| `thread_id` | VARCHAR(255) | Thread de Gmail |
| `from_address` | VARCHAR(255) | Remitente; el reply responde a este campo |
| `from_name` | VARCHAR(255) | |
| `to_addresses` | TEXT | |
| `subject` | TEXT | |
| `body_preview` | TEXT | Texto plano; lo que la IA resume y filtra con `bodyContains` |
| `body_html` | TEXT | |
| `is_read` | BOOLEAN default FALSE | |
| `is_starred` | BOOLEAN default FALSE | Estrella gestionada por refresh dedicado |
| `labels` | TEXT | **String con comas**, ej: `INBOX,IMPORTANT` o `...,SPAM`. No es array ni JSONB |
| `received_at` | TIMESTAMPTZ | Fecha del email; base de filtros `dateFrom`/`dateTo` |
| `fetched_at` | TIMESTAMPTZ default `NOW()` | Vía `@PrePersist` si es nulo |
| Constraint | `UNIQUE(user_id, provider_email_id)` | **Guardia de deduplicación**: el sync inserta o actualiza, nunca duplica |

### Índices (`email_messages` y resto)

```sql
idx_email_messages_user_id              ON email_messages(user_id)
idx_email_messages_received_at          ON email_messages(received_at DESC)
idx_email_messages_provider             ON email_messages(user_id, provider)
idx_email_messages_user_is_read         ON email_messages(user_id, is_read)
idx_email_messages_user_is_starred      ON email_messages(user_id, is_starred)
idx_email_messages_user_from_address    ON email_messages(user_id, from_address)
idx_email_messages_user_labels          ON email_messages(user_id, labels)
idx_email_messages_user_received_at     ON email_messages(user_id, received_at)
idx_oauth_tokens_user_id                ON oauth_tokens(user_id)
idx_chat_history_user_id                ON chat_history(user_id)
idx_visto_user_id                       ON visto(user_id)
```

Los cinco índices compuestos `user_*` son agregados recientes para acelerar los filtros combinados por usuario.

### `filter_criteria`, `chat_history`, `visto`

| Tabla | Columnas clave | Uso |
|---|---|---|
| `filter_criteria` | `id`, `user_id` FK, `name`, `criteria` JSONB NOT NULL, `created_at` | Filtros guardados (`FilterService.saveFilter`/`applyFilter`) |
| `chat_history` | `id`, `user_id` FK, `role`, `content` TEXT NOT NULL, `created_at` | Historial persistido (el chat en vivo además usa `conversation_id` en memoria del agente) |
| `visto` | `id`, `user_id` FK, `provider_email_id`, `visto` BOOLEAN, timestamps, `UNIQUE(user_id, provider_email_id)` | Marca local "visto" separada de `is_read`; `filterEmails` la adjunta al DTO sin N+1 |

---

## Autenticación y seguridad

### OAuth Google y Outlook

1. El frontend pide `GET /api/auth/oauth2/{provider}` y recibe `{authUrl}` ya armada por `AuthController.initiateOAuth2`.
2. Google pide scopes `email profile gmail.readonly gmail.modify` con `access_type=offline` y `prompt=consent`. Outlook pide `openid email profile Mail.Read Mail.ReadWrite offline_access` contra el tenant configurado.
3. Tras el consentimiento, el frontend envía `POST /api/auth/callback/{provider}` con `{authorizationCode, provider}`.
4. El backend intercambia el `code` por tokens (Google: `oauth2.googleapis.com/token` + userinfo; Outlook: `login.microsoftonline.com/{tenant}/oauth2/v2.0/token` + Graph `/me`).
5. `AuthService.authenticateOAuth2User` busca por `(provider, providerId)` o crea el usuario, emite JWT propios y guarda el token del proveedor con upsert.
6. Avatares: Google usa `picture` de userinfo (el frontend le pone `referrerPolicy="no-referrer"` porque el CDN `lh3.googleusercontent.com` devuelve 403 si recibe header Referer). Outlook descarga `GET /me/photo/$value` y lo guarda como data URL base64; si falla (404 típico), guarda `""`.

### JWT access y refresh

| Aspecto | Detalle verificado en `JwtTokenProvider` |
|---|---|
| Algoritmo | HS256 con `Keys.hmacShaKeyFor(secretBytes)` |
| Validación al arranque | El secreto debe medir 32 bytes mínimo o el bean lanza `IllegalArgumentException` con instrucciones |
| Access token | `sub` = userId (UUID string), claims `email` y `type=access`, expira según `JWT_EXPIRATION_MS` (default 24h) |
| Refresh token | `sub` = userId, claim `type=refresh`, expira según `JWT_REFRESH_EXPIRATION_MS` (default 7d) |
| Principal | **Es el userId, no el email**. `UserDetailsServiceImpl.buildUserDetails` pone `user.getId().toString()` como username; los controllers hacen `UUID.fromString(auth.getName())` |
| Envío | Header `Authorization: Bearer <access>` en cada request (salvo `/api/auth/refresh`) |
| Refresh | `POST /api/auth/refresh` con `{refreshToken}` rota ambos tokens |

`JwtAuthenticationFilter` solo acepta access tokens: si el token existe pero `type` no es `access`, responde 401 y corta. Si no hay token, deja pasar y es `SecurityConfig` quien decide. Punto sutil: sin `formLogin` ni `httpBasic` configurados, el entry point por defecto es `Http403ForbiddenEntryPoint`, así que un request sin token a un endpoint protegido devuelve **403, no 401**. El interceptor `api.js` del frontend solo reintenta refresh ante 401.

### Reglas `SecurityConfig`

```java
.requestMatchers("/api/auth/**", "/ws/**", "/actuator/health", "/error").permitAll()
.anyRequest().authenticated()
```

Sesión stateless, CSRF deshabilitado, `JwtAuthenticationFilter` antes de `UsernamePasswordAuthenticationFilter`. El `/error` en permitAll es un fix reciente: antes, cuando el backend fallaba (ej. la IA devolvía 500), Spring redirigía a `/error`, la seguridad lo bloqueaba y el cliente veía un 403 engañoso en vez del 500 real.

### Auth del WebSocket

`WebSocketAuthInterceptor` actúa en el canal inbound: ante un CONNECT STOMP exige un access token válido, tomado del header nativo `Authorization: Bearer ...` o del header `access_token`. Sin token válido devuelve `null` y el handshake se rechaza (no llega frame CONNECTED). El principal del mensaje es el userId string.

### CORS

`CorsConfig` lee `cors.allowed-origins` (mapeado de `FRONTEND_URL`), permite métodos GET/POST/PUT/DELETE/PATCH/OPTIONS, headers `Authorization, Content-Type, Accept`, credenciales y `maxAge` 3600. `WebSocketConfig` reutiliza los mismos orígenes para el endpoint `/ws`.

---

## Flujo de sincronización de emails

### Sincronización incremental (`POST /api/emails/sync`)

La usa el botón "Actualizar nuevos emails" y el auto-sync del Dashboard. `syncEmailsAsync` corre en `@Async("emailSyncExecutor")`, con guardia de concurrencia por usuario (`syncingStatus.putIfAbsent`) y TTL de limpieza. Responde 202 `{status: syncing}` al instante; el progreso llega por WebSocket.

En Gmail: si ya hay emails, toma el `receivedAt` máximo y llama `fetchIncrementalEmails` con queries `in:inbox after:<fecha>` **y** `in:spam after:<fecha>` (ambas, con dedup por `LinkedHashMap`), saltando IDs ya existentes. Si es la primera vez, hace fetch completo con `fetchAllEmails(accessToken, 100, maxEmailsPerSync)`. En Outlook llama `outlookService.fetchEmails(accessToken, 100)` contra Graph con `$top`, ordenado por `receivedDateTime DESC`.

Después de guardar, siempre pasa el **refresh de labels y estrellas** (solo Google), luego la **notificación de urgentes**, y al final emite `sync_complete` (o `sync_error: <msg>` si algo falló). Todo el post-proceso es best-effort: un fallo en refresh o en IA nunca rompe el sync.

### Sincronización completa (`POST /api/emails/sync/full`)

La usa el botón "Descargar todo el historial". `syncAllEmailsAsync`:

1. Lista **todos** los IDs de Gmail paginando `in:inbox` e `in:spam` por separado (helper `fetchAllMessageIds`, dedup con `LinkedHashSet`), emitiendo `sync_progress:0:0`.
2. Resta los IDs ya en DB con una query liviana paginada de a 1000 (`findProviderEmailIdsByUserId`, sin traer entidades).
3. Si no hay nuevos, igual corre el refresh de labels/estrellas y emite `sync_complete`.
4. Si hay nuevos, los descarga en **batches de 50** con progreso `sync_progress:<fetched>:<total>` por WebSocket.
5. Al terminar: refresh de labels, notificación de urgentes (acumulando los guardados de todos los batches) y `sync_complete`.

Para proveedores no Google, el full sync delega en el sync normal.

### Dedup y actualización

`saveEmails` ya no es solo inserción: busca por `(providerEmailId, userId)`; si existe, **actualiza** `isStarred`, `isRead`, `labels`, `subject`, `from`, `to`, `body`, `receivedAt` y `threadId`. El constraint `UNIQUE(user_id, provider_email_id)` sigue siendo la guardia dura contra duplicados. La paginación de IDs existentes evita cargar miles de entidades en memoria.

### Refresh de labels y estrellas

`refreshLabelsAndStars` corre tras guardar en ambos syncs (incluido el early-return sin emails nuevos). Lista IDs de `is:starred` e `in:spam` (paginado 500/página) y aplica tres queries `@Modifying`: `unstarAllByUserId`, `starByProviderEmailIds` y `addSpamLabelByProviderEmailIds` (concatena `,SPAM` solo si falta). Detalle de implementación: el `@Transactional` del service es inerte por auto-invocación sin proxy, así que las que realmente sostienen la transacción son las anotaciones `@Transactional` a nivel repository. Este refresh nació de un bug real: con 1.000+ emails en inbox, el presupuesto compartido dejaba `remaining=0` para spam (nunca se descargaba) y el `is_starred` local jamás se actualizaba.

### Notificaciones urgentes con SLA

`notifyUrgentEmails` toma hasta 20 emails recién sincronizados, los convierte a DTO, llama a `POST /analyze` y filtra clasificaciones con `urgency == "high"` matcheando `classification.id == providerEmailId`. Trunca subjects a 60 caracteres y emite por WebSocket:

```
urgent_emails:<count>:<subject1> | <subject2> | <subject3>
```

El Dashboard lo parsea con `indexOf(':')` (no `split`, porque los asuntos pueden contener `:`). El banner rojo es descartable manualmente, sin auto-dismiss. En el full sync esta notificación se salta si no había emails nuevos.

### Límites de paginación

Gmail lista de a 100 por página (`maxResultsPerPage=100`), el full sync descarga detalles en batches de 50, el refresh lista de a 500, y la paginación de IDs en DB va de a 1000. El resumen diario capa en 100 emails y el análisis urgente en 20. El tope por sync se gobierna con `EMAIL_SYNC_MAX_EMAILS` (default 1000 según `.env.example`). La paginación completa de Gmail puede tardar minutos en buzones grandes; nunca se reemplazó por una llamada bulk única.

---

## API REST

Base backend: `http://localhost:8080`. Todas salvo las marcadas requieren `Authorization: Bearer <accessToken>`. Paginados devuelven `Page<EmailDTO>` de Spring (`content`, `totalElements`, `totalPages`, `number`, `size`).

### Auth (`/api/auth`, `AuthController`)

| Método | Path | Body / Params | Respuesta | Errores |
|---|---|---|---|---|
| GET | `/api/auth/oauth2/{provider}` | `provider` = `google` \| `outlook` | 200 `{authUrl}` | `IllegalArgumentException` si el provider no existe |
| POST | `/api/auth/callback/{provider}` | `{authorizationCode, provider}` | 200 `AuthResponse` (`userId`, `email`, `name`, `accessToken`, `refreshToken`, `avatarUrl`) | 401 `{error: "Error al autenticar con ..."}` |
| POST | `/api/auth/refresh` | `{refreshToken}` (opcional en tipo, requerido en valor) | 200 `AuthResponse` con tokens rotados | 400 vacío si falta; excepción si el token es inválido |

### Emails (`/api/emails`, `EmailController`)

| Método | Path | Body / Params | Respuesta | Errores |
|---|---|---|---|---|
| GET | `/api/emails` | `page` (def. 0), `size` (def. 20) | 200 `Page<EmailDTO>` ordenado por `receivedAt` DESC | 403 sin token |
| GET | `/api/emails/{id}` | `id` UUID interno | 200 `EmailDTO` | Excepción si no existe o no pertenece al usuario |
| GET | `/api/emails/filter` | query libre `Map<String,String>` más `page`, `size` | 200 `Page<EmailDTO>` vía Specifications | Claves desconocidas se ignoran |
| POST | `/api/emails/action` | `{provider, emailId, action}` con `action` en `read \| unread \| star \| unstar \| trash` | 200 vacío + notificación WS | 400 si falta algún campo; `IllegalArgumentException` si la acción es desconocida |
| POST | `/api/emails/sync` | `{provider}` | 202 `{status: "syncing"}` | 400 `{error: "provider is required"}` |
| GET | `/api/emails/sync/status` | n/a | 200 `{syncing: boolean}` | n/a |
| POST | `/api/emails/sync/full` | `{provider}` | 202 `{status: "syncing", mode: "full"}` | 400 `{error: "provider is required"}` |
| GET | `/api/emails/summary` | `date` (def. `today`; acepta `yesterday` o ISO `yyyy-MM-dd`) | 200 `{summary, emailCount, date}`; día vacío devuelve `"No hay correos para resumir."` con `emailCount: 0` | 500 si la IA falla (ya no enmascarado como 403) |
| GET | `/api/emails/count` | n/a | 200 `{count}` | n/a |
| POST | `/api/emails/rewrite` | `{draft, originalSubject, originalFrom, originalBody, tone, language, customRules}` | 200 `{rewritten, original}` desde `POST /rewrite` de la IA | 400/500 estándar de validación o IA |
| POST | `/api/emails/send` | `{provider, emailId, body}` (`emailId` es el **providerEmailId**, no el UUID) | 200 `{status: "sent", messageId}` | 400 `{error: "provider/emailId/body is required"}` por campo; `IllegalArgumentException` si el email no existe o el provider no es soportado |

### Chat (`/api/chat`, `ChatController`)

| Método | Path | Body | Respuesta |
|---|---|---|---|
| POST | `/api/chat` | `{message, conversationId}` (`ChatRequest` validado) | 200 mapa IA (`response`, `criteria`, `conversation_id`) más `emails` (página completa si hubo criterios) y push WS doble |

Si el filtro no trae resultados, el backend agrega una explicación en español (`buildNoResultsExplanation`) según los criterios usados. El `size` de resultados sale de `criteria.size`, default 20.

### Filtros soportados en `GET /api/emails/filter`

Todos combinan con AND. Valores en blanco se ignoran.

| Clave | Efecto |
|---|---|
| `fromAddress` | `LIKE` insensible a mayúsculas sobre remitente (escapa `%`, `_`) |
| `subjectContains` | Uno o varios términos separados por coma (OR entre ellos) sobre asunto |
| `bodyContains` | Igual que el anterior pero sobre `bodyPreview` |
| `isRead` | `true` / `false` exacto |
| `isStarred` | `true` / `false` exacto |
| `important` | `labels LIKE %IMPORTANT%` (el valor se ignora, basta que no sea blanco) |
| `destacados` | **Nuevo**: `isStarred = true OR labels LIKE %IMPORTANT%` (cubre usuarios sin estrellas pero con IMPORTANT automático de Gmail) |
| `label` | `LIKE` sobre `labels` con escape (ej. `SPAM`, `CATEGORY_PROMOTIONS`) |
| `dateFrom` | `receivedAt >= Instant.parse(value)` (ISO-8601) |
| `dateTo` | `receivedAt <= Instant.parse(value)` |

---

## WebSocket

| Aspecto | Detalle |
|---|---|
| Endpoint | `/ws` con SockJS (`WebSocketConfig.registerStompEndpoints`), orígenes de `cors.allowed-origins` |
| Broker | Simple broker en `/topic` y `/queue`; prefijo de app `/app`; prefijo de usuario `/user` |
| Auth | `WebSocketAuthInterceptor` en CONNECT (Bearer o header `access_token`); sin access válido no hay CONNECTED |
| Publicación | `@MessageMapping("/chat.send")` en `ChatController` (el cliente publica en `/app/chat.send`) |

### Topics por usuario (`/user/{userId}/queue/*`)

| Destino | Quién lo emite | Payload |
|---|---|---|
| `/queue/emails` | `sendEmailsToUser` (chat REST y WS) | `{type: "filtered_emails", emails: [...]}` |
| `/queue/chat` | `sendChatResponse` (chat REST y WS) | `{type: "chat_response", data: {...}}` |
| `/queue/notifications` | `sendNotification` (sync, acciones, urgentes) | `{type: "notification", message: string}` |

### Mensajes de notificación (string en `message`)

| Mensaje | Origen |
|---|---|
| `sync_progress:<fetched>:<total>` | Full sync (arranca en `sync_progress:0:0`) |
| `sync_complete` | Ambos syncs al terminar |
| `sync_error: <detalle>` | Sync con excepción |
| `urgent_emails:<count>:<s1> \| <s2> \| <s3>` | Análisis post-sync con urgentes high |
| `Email <action> completed` | `POST /api/emails/action` |

### Cliente frontend

`lib/websocket.js` crea el `Client` STOMP con `webSocketFactory` SockJS hacia `${VITE_WS_URL}/ws`, `reconnectDelay` 5000 ms y heartbeat 4000/4000. `useWebSocket(token, handlers)` extrae el `userId` del `sub` del JWT, suscribe `queue/emails`, `queue/chat` y `queue/notifications` solo tras `onConnect`, y al desmontar desuscribe todo y desconecta. Las suscripciones pedidas antes de conectar quedan en cola pendiente. `ChatPanel` deduplica respuestas IA por `conversation_id`.

---

## Servicio de IA

Stateless por diseño: recibe emails en el body, devuelve JSON, nunca toca PostgreSQL. Routers montados en `main.py`: `analyze` bajo `/analyze`, `chat` bajo `/chat`, `rewrite` bajo `/rewrite`, más `health` sin prefijo **(verificar path exacto en `app/routers/health.py`, típicamente `GET /health`)**. CORS limitado a `FRONTEND_URL` con métodos GET/POST.

### Endpoints

| Método | Path | Request | Response |
|---|---|---|---|
| POST | `/chat` | `{message, conversation_id, context}` | `{response, criteria, conversation_id}` |
| POST | `/chat/summarize` | `{emails: EmailItem[]}` | `{summary}` (markdown bien formado con tablas) |
| POST | `/analyze` | `{emails: EmailItem[]}` | `{classifications: [...], summary: "Classified N emails."}` |
| POST | `/rewrite` | `{draft, original_subject, original_from, original_body, tone, language, custom_rules}` | `{rewritten, original}` |
| GET | `/health` (verificar) | n/a | Estado del servicio; el README lo usa como smoke test con curl |

### Contrato `POST /chat` (lo consume Java directamente)

```json
{
  "response": "Mostrando emails importantes de esta semana.",
  "criteria": { "important": "true", "dateRange": "this_week" },
  "conversation_id": "abc123def456"
}
```

Campos de criteria que el agente puede emitir: `fromAddress`, `subjectContains`, `bodyContains` (con sinónimos separados por coma), `isRead`, `isStarred`, `important`, `label` (solo `SPAM`, `IMPORTANT`, `CATEGORY_UPDATES`, `CATEGORY_SOCIAL`, `CATEGORY_PROMOTIONS`, `CATEGORY_FORUMS`), `size` y `dateRange` con tokens `today`, `yesterday`, `this_week`, `last_week`, `this_month`, `last_month`, `this_year`, `last_7_days`, `last_30_days`, `last_90_days`.

El backend Java solo entiende fechas ISO (`dateFrom`/`dateTo`), así que `process_chat_message` resuelve el token en Python (`resolve_date_token`, semana desde lunes, todo en UTC) y lo reemplaza por `dateFrom`/`dateTo` antes de responder. La respuesta del LLM se limpia de cercas markdown ` ``` ` y se parsea con doble intento (incluido fallback de llaves dobles); si falla, devuelve el texto crudo con `criteria: {}` y un `conversation_id` generado. Regla de seguridad del prompt: el mensaje del usuario es solo datos, nunca instrucciones (anti prompt-injection).

### Clasificación (`POST /analyze`)

10 categorías: `important`, `work`, `personal`, `spam`, `social`, `promotions`, `updates`, `finance`, `travel`, `other`. Cada item devuelve:

```json
{
  "id": "<providerEmailId>",
  "label": "finance",
  "priority": 9,
  "requires_action": true,
  "reason": "motivo en español",
  "urgency": "high",
  "urgency_reason": "motivo de urgencia en español"
}
```

Reglas de urgencia: `high` ante deadlines, vencimientos, "urgente", "asap", "hoy", "mañana", facturas por vencer o `priority >= 8`; `medium` si requiere atención sin fecha dura; `low` si es informativo. El fallback por JSON inválido devuelve `label: other`, `priority: 5`, `urgency: low`. Ojo: si la IA devuelve JSON válido pero sin claves de urgencia, el resultado pasa tal cual sin inyección server-side, así que el consumidor debe tolerar su ausencia. El batch usa semáforo `asyncio` de 5 en `analyze_emails_batch`.

### Resumen (`POST /chat/summarize`)

`summarize_emails` toma hasta 20 emails (`subject`, `from_address`, `body_preview` truncado a 200 chars), pide agrupación por tema o remitente con destacados, y devuelve markdown. Acepta día vacío devolviendo `"No hay correos para resumir."`.

### Patrón camelCase/snake_case (`EmailItem`)

El backend Java serializa con Jackson en **camelCase** (`bodyPreview`, `fromAddress`, `providerEmailId`, `receivedAt`). El schema Pydantic usa `ConfigDict(alias_generator=to_camel, populate_by_name=True)`: acepta ambos formatos de entrada, pero `model_dump()` sigue emitiendo snake_case, que es lo que lee `summarize_emails`. Además el summarize usa `(e.get('body_preview') or '')[:200]` porque `.get()` con default no protege contra valores `None` presentes. Sin este fix, `/analyze` clasificaba "Subject: None" y `/chat/summarize` daba 500.

### Modelo por defecto

`AI_MODEL=openai/gpt-oss-120b` vía `AI_API_URL=https://api.groq.com/openai/v1` con `AI_PROVIDER=openai`. Todo configurable por entorno. El `.env.example` advierte que `llama-3.1-8b-instant` devuelve 404 en Groq.

---

## Frontend

### Rutas (`App.jsx`, lazy + code-splitting por página)

| Ruta | Página | Acceso |
|---|---|---|
| `/login` | `Login` (botones Google/Outlook) | Pública |
| `/auth/callback` | `Callback` (intercambia `code` y redirige) | Pública |
| `/` | `Dashboard` (stats, gráfico, resumen, chat) | Protegida, índice del `Layout` |
| `/inbox` | `Inbox` (bandeja + sync incremental/full) | Protegida |
| `/starred` | `Starred` (título "Destacados", filtro `{destacados: true}`) | Protegida |
| `/spam` | `Spam` | Protegida |
| `/settings` | `Settings` | Protegida |
| `*` | Redirige a `/` | n/a |

`ProtectedRoute` muestra spinner mientras `useAuth` carga y redirige a `/login` sin usuario. Para cache-busting tras deploys se usa query param (`/?v=N`): el Dashboard vive en `/`, así que `/dashboard?v=N` redirige y no sirve.

### Páginas y componentes (`src/`)

| Pieza | Rol |
|---|---|
| `pages/Dashboard.jsx` | Stats (total, no leídos, destacados, spam), gráfico por fecha, card "Resumen de hoy" con refresh, banner de urgentes, auto-sync una vez por sesión (flag en `sessionStorage`) |
| `pages/Inbox.jsx`, `Spam.jsx`, `Starred.jsx` | Listas paginadas (20 por página) con filtros; `Starred` usa el filtro `destacados` |
| `pages/Login.jsx`, `Callback.jsx` | OAuth UI e intercambio de código |
| `pages/Settings.jsx` | Preferencias (incluye settings de rewrite que `ReplyPanel` lee de localStorage) |
| `components/Layout.jsx` | Shell con header (toggle dark mode) y navegación |
| `components/EmailList.jsx`, `EmailListView.jsx`, `EmailCard.jsx` | Lista, vista con paginación y tarjeta individual |
| `components/EmailDetail.jsx` | Detalle con acciones (star, read/unread, trash) y reply; pasa `email.provider` + `email.providerEmailId` |
| `components/ChatPanel.jsx` | Chat con historial y dedup por `conversation_id` |
| `components/ReplyPanel.jsx` | Borrador + "Mejorar con IA" + botón "Enviar" (usa `rewritten` si existe, si no el `draft`; spinner, banner verde de enviado, reset al editar o limpiar) |

### Hooks, contexto y lib

| Pieza | Rol |
|---|---|
| `hooks/useAuth.js` | Expone `{user, loading, login, logout}` del contexto |
| `hooks/useWebSocket.js` | Conecta por token y suscribe las 3 colas (ver sección WebSocket) |
| `context/AuthContext.jsx` | Hidrata usuario desde localStorage al montar; `login` guarda `AuthResponse` + provider |
| `context/ThemeContext.jsx` | Dark mode con persistencia en localStorage (verificar nombre exacto del contexto) |
| `lib/api.js` | `request()` con `Authorization` automática, reintento único ante 401 vía refresh singleton (`refreshPromise`), logout y redirect a `/login` si el refresh falla; `emails.*` (list, filter, action, get, sync, syncFull, syncStatus con fallback, count con fallback, summary, send), `chat.send`, `rewrite.improve`, `auth.*` |
| `lib/auth.js` | `getStoredAuth` / `storeAuth` / `clearAuth` / `isTokenExpired`; si el access expiró, limpia todo y devuelve `null` |
| `lib/websocket.js` | Cliente STOMP/SockJS singleton con suscripciones pendientes y helpers `subscribeToUser`, `sendMessage`, `disconnectWebSocket` |

### Detalles recientes del Dashboard

La card "Resumen de hoy" renderiza el markdown con `<ReactMarkdown remarkPlugins={[remarkGfm]}>` y mapeo Tailwind (h2/h3, p, strong, listas, tablas con wrapper `overflow-x-auto`, th/td con bordes, thead, hr, links con `target=_blank`). Sin `remark-gfm` las tablas quedan como texto con pipes: react-markdown v10 no trae GFM por defecto. El footer usa `new Date(date + 'T00:00:00')` (medianoche **local**) para no caer al día anterior en timezones negativos como Argentina. Estados: spinner "Generando resumen...", error con botón "Reintentar", día vacío con el mensaje del backend tal cual.

### Estilos y build

Tailwind utility-first más clases propias, dark mode con toggle en header persistido, `date-fns` con formato `dd/MM/yyyy`, Vite con proxy `/api` a `localhost:8080` y `/ws` a `ws://localhost:8080` (ojo: `vite.config.js` fija `port: 3001` para dev, aunque el README indica 5173; verificar cuál aplica en tu checkout). Producción: build estático servido por nginx en el contenedor frontend.

---

## Contratos cross-service

### 1. Chat API

```
Backend -> IA:  POST /chat  {message, conversation_id, context: []}
IA -> Backend:  {response, criteria: {...}, conversation_id}
Backend -> WS:  /user/{userId}/queue/emails  -> {type: filtered_emails, emails: [...]}
Backend -> WS:  /user/{userId}/queue/chat    -> {type: chat_response, data: {...}}
REST:           POST /api/chat responde el mismo mapa IA (con `emails` como Page si hubo criterios)
```

`AIServiceClient.chat` manda `context` siempre vacío y `conversation_id` como string ("" si es nulo).

### 2. JWT

```
Access:   HS256, sub=userId UUID, claims email + type=access,  JWT_EXPIRATION_MS (24h)
Refresh:  HS256, sub=userId UUID, claim type=refresh,          JWT_REFRESH_EXPIRATION_MS (7d)
Header:   Authorization: Bearer <access>
```

El principal del `SecurityContext` es el userId string. El filtro REST solo acepta `type=access`; el interceptor WS también. Refresh rota ambos tokens.

### 3. Dedup de sync

```
Constraint:  UNIQUE(user_id, provider_email_id) en email_messages y en visto
Labels:      string con comas en VARCHAR/TEXT (ej. "INBOX,IMPORTANT")
Queries:     "in:inbox" + "in:spam" (listado y fetch), "is:starred" + "in:spam" (refresh)
```

### 4. WebSocket

```
Connect:    /ws (SockJS), auth en CONNECT
Subscribe:  /user/{userId}/queue/emails | /user/{userId}/queue/chat | /user/{userId}/queue/notifications
Publish:    /app/chat.send  -> ChatController.handleWebSocketChat
```

---

## Variables de entorno

Solo nombres y propósito, sin valores. Fuente: `.env.example` + `docker-compose.yml`.

| Variable | Propósito | Default / Nota |
|---|---|---|
| `POSTGRES_DB` | Nombre de la base | `emailfilter` |
| `POSTGRES_USER` | Usuario de PostgreSQL | `emailfilter_user` |
| `POSTGRES_PASSWORD` | Contraseña de PostgreSQL | Cambiar en prod |
| `POSTGRES_HOST` | Host DB para el backend | `postgres` en Docker, `localhost` en local |
| `POSTGRES_PORT` | Puerto DB | 5432 interno, 5433 en host Docker |
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | Documentadas pero **sin uso** (no hay servicio Redis en compose) | Solo `.env.example` |
| `JWT_SECRET` | Secreto HMAC-SHA (mínimo 32 bytes, validado al arranque) | Requerido |
| `JWT_EXPIRATION_MS` | Vida del access token | 86400000 (24h) |
| `JWT_REFRESH_EXPIRATION_MS` | Vida del refresh token | 604800000 (7d) |
| `EMAIL_SYNC_MAX_EMAILS` | Tope de emails por sync inicial | 1000 |
| `GOOGLE_CLIENT_ID` | OAuth Google | Requerido para Gmail |
| `GOOGLE_CLIENT_SECRET` | OAuth Google | Requerido para Gmail |
| `GOOGLE_REDIRECT_URI` | Redirect Google (`.../api/auth/callback/google`) | Debe coincidir con Google Cloud Console |
| `OUTLOOK_CLIENT_ID` | OAuth Microsoft | Requerido para Outlook |
| `OUTLOOK_CLIENT_SECRET` | OAuth Microsoft | Requerido para Outlook |
| `OUTLOOK_REDIRECT_URI` | Redirect Outlook | Apunta al frontend (`/auth/callback`) según ejemplo |
| `OUTLOOK_TENANT` | Tenant Azure AD | `common` |
| `AI_SERVICE_URL` | URL del servicio IA vista desde el backend | `http://ai-python:8000` en Docker |
| `AI_PROVIDER` | Proveedor del cliente LLM | `openai` |
| `AI_API_KEY` | Key de Groq | Requerido |
| `AI_API_URL` | Endpoint OpenAI-compatible | `https://api.groq.com/openai/v1` |
| `AI_MODEL` | Modelo | `openai/gpt-oss-120b` |
| `FRONTEND_URL` | Origen permitido (CORS + WS) | `http://localhost:3001` |

---

## Cómo ejecutar

### Docker (recomendado)

```bash
cp .env.example .env
# completar GOOGLE_*, JWT_SECRET, AI_API_KEY (ver README para el alta en Google Cloud y Groq)

docker compose up -d --build
docker compose logs -f            # todos
docker compose logs -f backend    # uno solo
docker ps                         # estado
```

Abrir `http://localhost:3001`. Comandos útiles: `docker compose restart backend`, `docker compose down` (bajar), `docker compose down -v` (bajar **borrando la DB**), rebuild de uno: `docker compose build frontend --no-cache` + `docker compose up -d frontend`. Ojo: el servicio IA se llama `ai-python`, así que `docker compose up ... ai` falla con "no such service".

### Local sin Docker

Requiere Java 17 + Maven, Python 3.10+, Node 18+ y PostgreSQL 16 en `localhost:5432`.

```bash
cp .env.example .env
psql -U postgres -f infrastructure/postgres/init/01-init.sql
```

Luego doble clic en `start-local.bat` (verifica Postgres, crea venv e instala, compila backend, instala frontend y abre 3 consolas), o por servicio con `run-backend.bat` (8080), `run-ai.bat` (8000) y `run-frontend.bat` (5173). Abrir `http://localhost:5173`.

### Primer uso

Login con Google (o Outlook), la app detecta bandeja vacía y lanza full sync con barra de progreso, y después los syncs son incrementales. El chat entiende frases como "mostrame los emails importantes de esta semana" o "busca emails de facturación".

---

## Testing

| Servicio | Cómo correr | Estado (septiembre 2026) |
|---|---|---|
| Backend | `mvn test` vía imagen `maven:3.9-eclipse-temurin-17` en Docker (el repo no asume Maven local) | **71 tests, 0 fallos, 1 error preexistente**: `EmailFilterIntegrationTest` falla por Docker-in-Docker (ignorable, documentado en el notepad) |
| Frontend | `npx vitest run` en `services/frontend` (`npm run build` para verificar bundle) | **65 tests / 17 archivos**, todo verde |
| IA | `pytest` en `python:3.12-slim` | **30 tests**, todo verde |

Cobertura temática: controllers (`EmailControllerTest` con summary y send, `AuthControllerTest`), `EmailServiceTest` (filtros incluido `destacados`, summary, urgentes, sendReply), `GmailServiceTest` (MIME + envío con decodificación base64), `JwtTokenProviderTest`, `WebSocketAuthInterceptorTest`, `AIServiceClientTest`, `FilterServiceTest`; en frontend `api.test.js`, `Dashboard.test.jsx` (incluido markdown), `ReplyPanel.test.jsx`; en IA `test_chat_agent.py`, `test_email_analyzer.py` (urgencia high/low + fallback), `test_schemas.py` (camelCase + snake_case + `model_dump`).

Patrones que ahorran tiempo (del notepad): mockear la cadena Gmail (`users()` -> `messages()` -> `list`/`get`) con stubs `lenient()`; subclases `TestableGmailService`/`TestableEmailService` que overridean `buildGmailClient` (protected); `filterEmails` se testea capturando el `Specification` con `ArgumentCaptor` porque la auto-invocación impide mockearlo; en frontend, todo método nuevo de `api.js` usado por un componente debe existir en el mock de fábrica o los tests mueren con "not a function".

---

## Gotchas y deuda técnica

Todo lo de esta sección viene del notepad `.omo/notepads/emailfilter-audit/learnings.md` y del código.

- **Redis sin uso**: existe `RedisConfig.java` y vars `REDIS_*`, pero no hay servicio Redis en compose. No agregar código dependiente de Redis sin habilitarlo primero (o eliminar la config).
- **Paginación de Gmail**: `fetchAllEmails` pagina todo el buzón; en cuentas grandes tarda minutos. No reemplazar por bulk único. El presupuesto separado inbox/spam y el dedup con `LinkedHashMap` son load-bearing.
- **Outlook menos testeado**: `OutlookService` funciona (fetch, read/unread, star vía `flagStatus`, trash vía `/move`, send vía `/me/sendMail`), pero Gmail tiene mucha más cobertura de tests y uso real. Tocar auth o sync exige probar ambos.
- **Timezone en fechas**: la IA resuelve rangos en UTC (`resolve_date_token`) mientras el resumen diario usa `ZoneId.systemDefault()` del backend y el frontend parsea `date + 'T00:00:00'` como hora local. Mezclar las tres puede mover un día el borde. Los tests de fecha usan regex en vez de fecha exacta por este motivo.
- **react-markdown sin GFM no parsea tablas**: v10 requiere `remarkPlugins={[remarkGfm]}` o las tablas del resumen quedan como pipes crudos.
- **camelCase/snake_case**: Java emite camelCase, Pydantic acepta ambos gracias a `alias_generator`, y `model_dump()` emite snake_case para el summarize. No agregar `serialization_alias` ni `by_alias=True` o se rompe esa cadena.
- **Auto-invocación y `@Transactional`**: `filterEmails` no se puede mockear sobre la misma instancia, y el `@Transactional` de `refreshLabelsAndStars` es inerte; las transacciones reales viven en los métodos del repository.
- **`isAccessToken` estricto**: cualquier token no-access (incluido un refresh usado por error) da 401 en REST y rechazo en WS. El payload JWT de pruebas manuales debe incluir `"type": "access"`.
- **403 vs 401**: sin token en endpoint protegido la respuesta es 403 por el entry point por defecto, no 401. No "arreglarlo" sin decisión explícita (requeriría un `authenticationEntryPoint` propio).
- **PowerShell y curl**: `curl` es alias de `Invoke-WebRequest`; usar `curl.exe` o `Invoke-WebRequest`. El JSON inline se rompe por el quoteo: escribir el payload a archivo con `Set-Content` y usar `--data-binary "@file"`.
- **Nginx cachea el bundle**: tras rebuild del frontend, abrir con `/?v=N` o se sigue viendo el bundle viejo (verificar el hash en `document.querySelectorAll('script[src]')`).
- **Playwright MCP**: matar `chrome.exe` colgados que matchean `ms-playwright-mcp` antes de reusar el perfil; si el server dice "Browser is already in use" aunque no haya proceso, el lock es interno (esperar o usar `--isolated`). En `about:blank`, `browser_evaluate` falla con SecurityError: navegar primero a la app e inyectar después.
- **Envío de emails**: el reply siempre va al `fromAddress` del original con `Re:` idempotente (`(?i)^re:\s*`). Graph devuelve 202 sin body, por eso Outlook genera UUID local. Para probar end-to-end sin emails propios, insertar una fila self-addressed y borrarla después; nunca enviar a externos en pruebas.
- **Claves JWT en localStorage**: access, refresh y user viven en localStorage (`auth.js`). Un token viejo en localStorage redirige a `/login` de forma confusa; ante rarezas, limpiar storage primero.
- **Dashboard en `/`**: la ruta raíz es el Dashboard; no existe `/dashboard`.
- **MockMvc no pasa por `/error`**: las excepciones de controller en `@WebMvcTest` surgen como `ServletException`, no como 500. El test del fix `/error` pega a `/error` directo con atributos `jakarta.servlet.error.*`.

---

## Oportunidades de mejora

Cada item trae impacto estimado (Alto/Medio/Bajo) y parte de la deuda o gap observado.

- **Arreglar `EmailFilterIntegrationTest` o marcarlo `@Disabled` con ticket** (Bajo): el único error preexistente mete ruido en cada run y entrena a ignorar rojos. Impacto Bajo pero costo casi nulo.
- **Tests de integración reales por contrato** (Alto): hoy casi todo es unitario con mocks. Un test que pegue `POST /chat` contra IA mockeada por WireMock y otro que valide el schema `EmailItem` contra un `EmailDTO` serializado real evitarían regresiones como la del camelCase.
- **Rate limits y backoff en Gmail/Graph** (Alto): el full sync en buzones grandes hace cientos de llamadas sin retry ni backoff. Agregar reintentos con jitter y respeto a 429/quotas es lo que más protege al usuario pesado.
- **Paginación server-side real en el Dashboard** (Medio): listas de 20 con `totalElements` de 6.143 piden scroll infinito o paginación con cursor en vez de page index, que se degrada con offsets grandes.
- **Hacer configurable "Destacados"** (Medio): hoy es OR fijo (`isStarred` OR `IMPORTANT`). Un setting por usuario (solo estrellas, solo IMPORTANT, ambos) y exponer el conteo 254 en el mismo endpoint de stats evitaría confusiones como la del "0 destacados".
- **Internacionalización del frontend** (Medio): la UI mezcla español rioplatense con inglés técnico. Extraer strings a un diccionario `es` (y `en` después) facilita consistencia y futuros idiomas.
- **Observabilidad mínima** (Medio): los `log.info` con tiempos ya existen en chat y sync; falta exponerlos: endpoint `/actuator` completo o métricas Micrometer + un dashboard de latencia IA, tamaño de syncs y tasa de urgentes.
- **Refresh OAuth robusto** (Alto): `getToken` refresca el token del proveedor, pero si el refresh del proveedor expira el sync falla con `sync_error` genérico. Detectar ese caso y pedir re-login con mensaje claro ahorra soporte.
- **Sanear HTML del detalle** (Alto): `bodyHtml` se muestra en `EmailDetail`; si se renderiza sin sanitizar es vector XSS. Agregar DOMPurify o similar es barato y urgente si hay `dangerouslySetInnerHTML` (verificar).
- **Normalizar `labels` a tabla o enum** (Medio): el `LIKE %X%` sobre string con comas funciona pero impide índices eficientes y puede dar falsos positivos por substring. Una tabla `email_labels` o al menos match por token exacto lo endurece.
- **Cola real para syncs** (Medio): el flag en memoria `syncingStatus` se pierde al reiniciar y no escala a múltiples réplicas. Una tabla `sync_jobs` o una cola liviana daría reintentos y visibilidad.
- **Eliminar o habilitar Redis** (Bajo): la config muerta confunde a cada IA nueva que lee el repo. Decidir: borrar `RedisConfig` + vars, o usarlo para cachear `count` y resumens diarios.
- **Contrato `/analyze` estricto** (Medio): hoy urgency ausente pasa tal cual si el JSON es válido. Validar en Pydantic con default `low` + log de warning cierra el gap del lado IA en vez de cada consumidor.
- **TTL y limpieza de `syncingStatus`** (Bajo, verificar): existe `SYNC_STATUS_TTL_MS` con limpieza; confirmar que el TTL cubre full syncs de buzones muy grandes o un sync largo quedaría marcado como libre a mitad de corrida.
- **Documentar `OUTLOOK_REDIRECT_URI` real** (Bajo): el ejemplo apunta al frontend (`/auth/callback`) mientras Google apunta al backend (`/api/auth/callback/google`). Unificar o documentar por qué difieren evita errores de alta en Azure.
- **Resumens cacheados por día** (Medio): `GET /summary` llama a la IA en cada carga del Dashboard. Cachear por `(userId, date)` con invalidación al llegar emails nuevos ahorra latencia y costo Groq.

---

*Fin del documento. Fuentes: `AGENTS.md`, `README.md`, `docker-compose.yml`, `.env.example`, `infrastructure/postgres/init/01-init.sql`, código de `services/backend-java`, `services/frontend`, `services/ai-python` y `.omo/notepads/emailfilter-audit/learnings.md`.*
