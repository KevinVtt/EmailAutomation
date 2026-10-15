# AGENTS.md — EmailFilter AI

Fullstack monorepo: Gmail/Outlook OAuth → emails synced to local PostgreSQL → natural-language
chat (AI service) that searches, filters and classifies emails. Read this file before touching code.

## Layout
```
emailfilter/
├── services/
│   ├── backend-java/   # Spring Boot 3.2.4 (Java 17, Maven), port 8080, package com.emailfilter
│   ├── frontend/       # React 18 + Vite + Tailwind, port 5173 (dev) / 3001 (prod)
│   └── ai-python/      # FastAPI, OpenAI-compatible client (Groq), port 8000
├── infrastructure/postgres/init/   # SQL schema (source of truth for the DB)
├── docker-compose.yml
└── .env / .env.example             # env vars are documented in .env.example
```

## Architecture
```
Browser (React) ── REST /api/** + WebSocket /ws ──► Java Backend (8080)
                                                      ├── PostgreSQL (5432)
                                                      ├── Gmail API / Microsoft Graph
                                                      └── HTTP ──► Python AI (8000) ──► Groq API
```
1. **OAuth login**: `/api/auth/oauth2/{provider}` (issues a CSRF `state`) → Google/Microsoft →
   frontend `/auth/callback` → `POST /api/auth/callback/{provider}` with `{authorizationCode, provider, state}` → JWT in localStorage.
2. **Email sync**: `POST /api/emails/sync` → GmailService paginates inbox + spam → dedup by (userId, providerEmailId).
3. **AI chat filter**: message → `/api/chat` → Python `/chat` → `{response, criteria}` → JPA Specifications → WebSocket push of filtered emails.
4. **Stats**: `GET /api/emails/stats` returns the dashboard counters + 7-day activity in one call.

## Working rules
- Work one service at a time. Do not modify files outside the service you were asked to change
  without saying so first.
- Cross-service changes go in this order: `ai-python` → `backend-java` → `frontend`.
- When a change touches a contract (below) or an env var, say it explicitly in your final summary.
- Always check: does this change affect the `/api/chat` WebSocket flow or OAuth?
- Never read, print or commit `.env` or any secret. Use `.env.example`.
- Never push to `main`/`master` or force-push. Work on a separate branch.

## Backend (`services/backend-java/`)
- `/api/chat` must keep returning `{response, criteria}` from the AI + filtered emails via WebSocket.
  `ChatController` serves both `POST /api/chat` and `/app/chat.send`; both share `processChat(...)` and must work.
  In both paths, the `emails` field is the LIST (`page.getContent()`), not a Spring `Page`.
- OAuth callback requires a single-use `state` issued by `GET /api/auth/oauth2/{provider}`
  (in-memory, 10-min TTL, process-local: a multi-instance deploy needs a shared store or signed state).
- OAuth tokens are encrypted at rest with `TOKEN_ENCRYPTION_KEY` (`TokenCryptoService`, AES-GCM, `enc:` prefix).
  Values without the prefix are legacy plaintext and are read as-is; without the key everything stays plaintext (dev only).
- JWT is HMAC-SHA. Do not change the secret structure without updating `JWT_SECRET`.
  `refreshAccessToken` accepts only `type=refresh` tokens. The principal in `SecurityContext` is `userId` (UUID string), not email.
- `UNIQUE(userId, providerEmailId)` on `email_messages` is the sync dedup guard. Never remove it.
- `EmailRepository`'s counters use the `NOT_SEEN` fragment: "no leído"/"urgente"/"pendiente" exclude emails
  with a `visto=true` row for that user (the app's own read state), because `saveEmails` overwrites `is_read`
  with the provider's flag on every sync.
- Use the `application-dev.yml` profile locally (DDL auto-update + SQL logging).
- Gmail: keep `GmailService.fetchAllEmails()` paginated (queries `in:inbox` + `in:spam`, 100 per page);
  do not replace it with one bulk call. `OutlookService` is less tested: test both providers when touching auth or sync.
- Provider HTTP calls (`markAsRead`, `toggleStar`, `moveToTrash`, `sendReply`, ...) must stay OUTSIDE a DB
  transaction: they can sleep on retry/backoff and would otherwise pin a pooled connection (pool size 10).
- CORS is controlled by `FRONTEND_URL` (`CorsConfig`).
- There is no Redis in this project. Do not add Redis-dependent code unless it is enabled in Docker Compose.

## Frontend (`services/frontend/`)
- Access tokens only via `getStoredAuth()` / `storeAuth()`.
- `useWebSocket.js`: keep the 5s reconnect + heartbeat; subscribe to `/user/queue/*` (no userId in the path).
  There is ONE shared connection for the whole app (`lib/websocket.js`): each component registers its own
  subscriptions and drops only those on unmount — never tear the client down from a page. Logout calls
  `disconnectWebSocket()`. A reconnect re-attaches every live subscription and re-reads the access token.
- OAuth `state`: `Login.jsx` stores it in `sessionStorage['oauthState']`; `Callback.jsx` sends it to the backend. Keep both.
- Dashboard auto-syncs once per session through a `sessionStorage` flag. Preserve it.
- `Layout` also runs a background incremental sync every 2 min (plus on window focus) so new mail arrives
  without a manual refresh. The Dashboard's sync banner only covers syncs the Dashboard itself started.
- Lists (`EmailListView`) refetch silently on `sync_complete` / `emails_changed`.
- Dashboard stats come from the single `GET /api/emails/stats` call (`api.emails.stats()`); do not fan out per-filter/per-day requests again.
- Read state lives in two places: `visto` (opened by the user) and `email_messages.is_read` (the provider's
  UNREAD flag). "No leído" means `is_read = false` **and** no `visto=true` row — opening an email sets both;
  counting `is_read` alone is wrong because the sync overwrites it with whatever the provider says.
  After any `POST /api/emails/action` the backend sends the `emails_changed` notification and the
  Dashboard refetches stats on it.
- Email bodies are decoded from Gmail's raw MIME: base64 **and** quoted-printable (`util/QuotedPrintable`),
  preferring the `text/html` part. Without the quoted-printable step the reader shows `=3D`, `=C3=A9`, etc.
- `ChatPanel` dedups AI responses by `conversation_id`. Do not remove that logic.
- Vite proxies `/api` and `/ws` in dev: check `vite.config.js` before touching proxy rules.
- Use Tailwind utilities and existing classes (`btn-primary`, `card`, `input`) before adding CSS.

## AI service (`services/ai-python/`)
- `chat_agent.py` MUST return JSON `{response: string, criteria: object}`; the Java backend parses it directly.
- Criteria fields: `fromAddress`, `subjectContains`, `bodyContains`, `isRead`, `isStarred`, `important`,
  `dateFrom`, `dateTo`, `label`, `size`.
- Valid labels: `SPAM`, `IMPORTANT`, `CATEGORY_UPDATES`, `CATEGORY_SOCIAL`, `CATEGORY_PROMOTIONS`, `CATEGORY_FORUMS`.
- The system prompt must keep handling relative Spanish dates ("esta semana", "ayer", "este mes").
  If you change the prompt, test those expressions.
- `email_analyzer.py` classifies into 10 categories with priority score 1–10.
- Default model `openai/gpt-oss-120b` via Groq, configurable with `AI_MODEL`.
- Keep the service stateless: no database dependency.
- In Docker its port 8000 is NOT published (only the backend reaches it internally); local dev runs it on 8000.

## Database / infra (`infrastructure/`, `docker-compose.yml`, `.env.example`)
- Never drop or rename columns without a migration script.
- Changes to `email_messages` (especially `labels`, `bodyPreview`, `providerEmailId`) must account for the backend.
  `labels` is a comma-separated VARCHAR.
- New env vars go in `.env.example` with a comment explaining their purpose.
- `filter_criteria` and `chat_history` are unused (the "saved filters" feature and a `ChatMessage` entity
  were removed); their DDL is gone from the init SQL. Databases created before that change may still have
  them — drop them manually if you want.

## Contracts (do not break)
```
Chat:      Backend → AI   POST /chat  { message, conversation_id, context: [...] }
           AI → Backend   { response, criteria: {...}, conversation_id }
           Backend → UI   { response, criteria, conversation_id, emails: [...] }  (emails = list, not a Page)
OAuth:     GET  /api/auth/oauth2/{provider}          → { authUrl } (authUrl carries &state=<uuid>)
           POST /api/auth/callback/{provider}        body { authorizationCode, provider, state }  (401 on bad/used/expired state)
JWT:       Authorization: Bearer <token>; access + refresh, HMAC-SHA; principal = userId;
           /api/auth/refresh accepts only refresh-typed tokens
WebSocket: connect /ws (SockJS)
           subscribe /user/queue/emails | chat | notifications
           publish   /app/chat.send
           notifications: sync_complete | sync_progress:<n>:<total> | sync_error | urgent_emails:<n>:<subjects>
                          | emails_changed (email action done → Dashboard refreshes counters)
```

## Run locally
```bash
psql -f infrastructure/postgres/init/01-init.sql                         # PostgreSQL
cd services/backend-java && mvn spring-boot:run -Dspring-boot.run.profiles=dev
cd services/ai-python && pip install -r requirements.txt && uvicorn app.main:app --port 8000
cd services/frontend && npm install && npm run dev
```
Copy `.env.example` → `.env` and fill all variables first.

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

When the user types `/graphify`, use the installed graphify skill or instructions before doing anything else.

Rules:
- When graphify-out/graph.json exists, run `graphify query "<question>"` before your first grep, search or source-file read for a codebase question, and put this rule in every subagent prompt that explores code. Read raw files only for lines the graph or an earlier read already located. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- Dirty graphify-out/ files are expected after hooks or incremental updates; dirty graph files are not a reason to skip graphify. Only skip graphify if the task is about stale or incorrect graph output, or the user explicitly says not to use it.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).
- A committed graphify-out/graph.json is rewritten whole on every build, so a large diff to it in a pull request is expected. Do not flag it as churn or suggest skipping the graph rebuild or its hooks.
