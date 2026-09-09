# AGENTS.md — EmailFilter AI

> This file defines the project context, architecture, agent roles, and coordination rules
> for AI coding agents (OpenCode + oh-my-opencode or similar orchestrators).
> Read this file completely before taking any action on the codebase.

---

## Project Overview

**EmailFilter AI** is a fullstack web application that connects to Gmail/Outlook via OAuth,
syncs emails to a local PostgreSQL database, and exposes a natural-language chat interface
powered by an AI service to search, filter, and classify emails.

**Monorepo structure:**
```
emailfilter/
├── services/
│   ├── backend-java/        # Spring Boot 3.2.4, port 8080
│   ├── frontend/            # React 18 + Vite, port 5173 (dev) / 3001 (prod)
│   └── ai-python/           # FastAPI + Groq/OpenAI-compatible, port 8000
├── infrastructure/
│   └── postgres/init/       # SQL schema init scripts
├── docker-compose.yml
├── .env / .env.example
└── AGENTS.md                # ← you are here
```

---

## Architecture

### Service Communication
```
Browser (React)
    │
    ├── REST /api/** + WebSocket /ws  ──► Java Backend (8080)
    │                                        │
    │                                        ├── PostgreSQL (5432)
    │                                        ├── Gmail API / Microsoft Graph
    │                                        └── HTTP ──► Python AI Service (8000)
    │                                                        └── Groq API (openai/gpt-oss-120b)
```

### Key Flows
1. **OAuth Login**: Browser → `/api/auth/oauth2/{provider}` → Google/Microsoft → Callback → JWT stored in localStorage
2. **Email Sync**: `POST /api/emails/sync` → GmailService paginates all inbox+spam → saved with dedup by (userId, providerEmailId)
3. **AI Chat Filter**: User message → `/api/chat` → Python `/chat` → JSON `{response, criteria}` → JPA Specifications → WebSocket push of filtered emails

---

## Tech Stack (per service)

### Backend — Java Spring Boot (`services/backend-java/`)
- Java 17, Maven, Spring Boot 3.2.4
- Spring Security (stateless JWT), Spring Data JPA, WebSocket (STOMP/SockJS)
- PostgreSQL via Hibernate, jjwt 0.12.5
- Google API Client + Gmail API v1, Microsoft Graph (Outlook)
- Root package: `com.emailfilter`

### Frontend — React (`services/frontend/`)
- React 18 + Vite, React Router v6
- @stomp/stompjs + sockjs-client (WebSocket)
- TailwindCSS, lucide-react, date-fns, clsx
- Auth via localStorage (JWT access + refresh tokens)
- API layer in `src/lib/api.js` with auto 401→refresh interceptor

### AI Service — Python FastAPI (`services/ai-python/`)
- Python, FastAPI, uvicorn, httpx, pydantic-settings
- OpenAI-compatible API client (default: Groq + openai/gpt-oss-120b)
- Routers: `/chat`, `/analyze`, `/health`
- `chat_agent.py` extracts filter criteria from natural language (JSON output)
- `email_analyzer.py` classifies emails into 10 categories with priority score

---

## Database Schema

```sql
users           (id UUID, email, name, provider, providerId, avatarUrl, timestamps)
oauth_tokens    (id UUID, userId FK, provider, accessToken, refreshToken, expiresAt)
email_messages  (id UUID, userId FK, provider, providerEmailId, threadId,
                 fromAddress, fromName, toAddresses, subject, bodyPreview, bodyHtml,
                 isRead, isStarred, labels VARCHAR, receivedAt, fetchedAt)
                 UNIQUE(userId, providerEmailId)
chat_history    (id UUID, userId FK, role, content, timestamp)
filter_criteria (id UUID, userId FK, name, criteria JSONB, timestamp)
```

---

## Environment Variables

```
# Database
POSTGRES_DB, POSTGRES_USER, POSTGRES_PASSWORD, POSTGRES_HOST, POSTGRES_PORT

# Auth
JWT_SECRET, JWT_EXPIRATION_MS (default 24h), JWT_REFRESH_EXPIRATION_MS (default 7d)

# OAuth
GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET, GOOGLE_REDIRECT_URI
OUTLOOK_CLIENT_ID, OUTLOOK_CLIENT_SECRET, OUTLOOK_REDIRECT_URI, OUTLOOK_TENANT

# AI
AI_SERVICE_URL, AI_PROVIDER, AI_API_KEY, AI_API_URL, AI_MODEL

# Misc
FRONTEND_URL
```

---

## Agent Roles & Responsibilities

This project uses a multi-agent structure. Each agent owns a specific domain.
**Agents must not modify files outside their domain without explicit approval.**

---

### 🧠 Orchestrator (Sisyphus / Lead Agent)
**Role:** Task decomposition, coordination, and final validation.

**Responsibilities:**
- Break down user requests into tasks per agent domain
- Resolve cross-service dependencies (e.g., API contract changes that affect frontend + backend)
- Validate that all agents completed their tasks without breaking integration points
- Write and maintain this AGENTS.md when the architecture changes

**Constraints:**
- Does NOT write production code directly
- Escalates to the user when agents produce conflicting changes
- Always checks: does this change affect the `/api/chat` WebSocket flow or OAuth?

---

### ☕ Backend Agent (Java Spring Boot)
**Domain:** `services/backend-java/`

**Responsibilities:**
- All Java code: controllers, services, repositories, models, DTOs, security, config
- JPA entity changes and Hibernate schema updates
- JWT auth logic (`JwtTokenProvider`, `JwtAuthenticationFilter`)
- Gmail API integration (`GmailService`) and Outlook (`OutlookService`)
- HTTP client to AI service (`AIServiceClient`)
- WebSocket messaging (`WebSocketService`, STOMP config)
- Filter logic via JPA Specifications (`EmailService.filterEmails`)

**Key files:**
```
src/main/java/com/emailfilter/
├── controller/     AuthController, EmailController, ChatController
├── service/        AuthService, EmailService, GmailService, AIServiceClient, WebSocketService
├── model/          User, OAuthToken, EmailMessage, ChatMessage, FilterCriteria
├── security/       JwtTokenProvider, JwtAuthenticationFilter
└── config/         SecurityConfig, WebSocketConfig, CorsConfig
```

**Rules:**
- Never break the `/api/chat` contract: must return `{response, criteria}` from AI + filtered emails via WebSocket
- JWT tokens use HMAC-SHA — never change the secret structure without updating `JWT_SECRET` env var
- Email dedup is enforced by `UNIQUE(userId, providerEmailId)` — do not remove this constraint
- Always use `application-dev.yml` profile for local development (DDL auto-update + SQL logging)
- RedisConfig exists but Redis is not actively used — can be ignored or removed safely

---

### ⚛️ Frontend Agent (React)
**Domain:** `services/frontend/`

**Responsibilities:**
- All React components, pages, hooks, context, and utilities
- WebSocket subscription management (`useWebSocket.js`, `websocket.js`)
- Auth state management (`AuthContext`, localStorage helpers)
- API calls with JWT refresh interceptor (`api.js`)
- Email list/detail rendering, chat panel, date filters, pagination

**Key files:**
```
src/
├── pages/          Login, Callback, Dashboard
├── components/     Layout, EmailList, EmailCard, EmailDetail, ChatPanel
├── hooks/          useAuth.js, useWebSocket.js
├── context/        AuthContext.jsx
└── lib/            api.js, auth.js, websocket.js
```

**Rules:**
- Auth tokens are stored in localStorage — access via `getStoredAuth()` / `storeAuth()` helpers only
- WebSocket reconnects every 5s with heartbeat — do not break this in `useWebSocket.js`
- Dashboard auto-syncs once per session via `sessionStorage` flag — preserve this behavior
- ChatPanel deduplicates AI responses via `conversation_id` — do not remove this logic
- Vite proxies `/api` and `/ws` to backend in dev — check `vite.config.js` before touching proxy rules
- Use Tailwind utility classes + existing custom classes (`btn-primary`, `card`, `input`) before adding new CSS

---

### 🐍 AI Agent (Python FastAPI)
**Domain:** `services/ai-python/`

**Responsibilities:**
- All Python code: FastAPI routers, services, schemas, config
- Chat agent system prompt and filter criteria extraction logic
- Email classification logic (10 categories, priority score 1–10)
- OpenAI-compatible API client configuration

**Key files:**
```
app/
├── main.py
├── config.py
├── routers/        chat.py, analyze.py
├── services/       ai_service.py, chat_agent.py, email_analyzer.py
└── models/         schemas.py, enums.py
```

**Rules:**
- `chat_agent.py` MUST return JSON `{response: string, criteria: object}` — the Java backend parses this directly
- Filter criteria fields: `fromAddress`, `subjectContains`, `bodyContains`, `isRead`, `isStarred`,
  `important`, `dateFrom`, `dateTo`, `label`, `size`
- Valid label values: `SPAM`, `IMPORTANT`, `CATEGORY_UPDATES`, `CATEGORY_SOCIAL`,
  `CATEGORY_PROMOTIONS`, `CATEGORY_FORUMS`
- Supports relative dates in Spanish ("esta semana", "ayer", "este mes") — preserve this in the system prompt
- Default model: `openai/gpt-oss-120b` via Groq — model is configurable via `AI_MODEL` env var
- Do not add sync dependencies between AI service and database — it must remain stateless

---

### 🗄️ Database/Infra Agent
**Domain:** `infrastructure/`, `docker-compose.yml`, `.env.example`

**Responsibilities:**
- PostgreSQL schema changes (`infrastructure/postgres/init/`)
- Docker Compose service definitions and networking
- Environment variable documentation (`.env.example`)
- Migration scripts when schema changes are needed

**Rules:**
- Never drop or rename columns without a migration script
- Schema changes that affect `email_messages` must be coordinated with Backend Agent
  (especially `labels`, `bodyPreview`, `providerEmailId`)
- `UNIQUE(userId, providerEmailId)` must never be removed — it's the dedup guard for email sync
- New env vars must be added to `.env.example` with a comment explaining their purpose

---

## Cross-Agent Contracts (DO NOT BREAK)

These are the integration points between services. Any change here requires coordination
between the relevant agents.

### 1. Chat API Contract
```
Backend → Python AI:
  POST /chat
  { "message": string, "conversation_id": string, "context": [...] }

Python AI → Backend:
  { "response": string, "criteria": { ...filter fields... }, "conversation_id": string }

Backend → Frontend (WebSocket):
  /user/{userId}/queue/emails  → filtered EmailDTO[]
  /user/{userId}/queue/chat    → AI response string
```

### 2. JWT Structure
```
Access token:  HMAC-SHA, configurable expiry (JWT_EXPIRATION_MS)
Refresh token: HMAC-SHA, configurable expiry (JWT_REFRESH_EXPIRATION_MS)
Principal:     userId (UUID as string)
Header:        Authorization: Bearer <token>
```

### 3. Email Sync Dedup
```
Unique constraint: (userId, providerEmailId)
Labels stored as: comma-separated string in VARCHAR column
Gmail queries:    "in:inbox" + "in:spam", 100 per page
```

### 4. WebSocket Endpoints
```
Connect:    /ws (SockJS)
Subscribe:  /user/{userId}/queue/emails
            /user/{userId}/queue/chat
            /user/{userId}/queue/notifications
Publish:    /app/chat.send
```

---

## How to Run Locally

```bash
# 1. PostgreSQL (local, port 5432)
psql -f infrastructure/postgres/init/01-init.sql

# 2. Backend
cd services/backend-java
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 3. AI Service
cd services/ai-python
pip install -r requirements.txt
uvicorn app.main:app --port 8000

# 4. Frontend
cd services/frontend
npm install && npm run dev
```

Copy `.env.example` → `.env` and fill all variables before starting.

---

## Agent Coordination Protocol

When working as a team, agents follow this protocol:

1. **Orchestrator** receives a task and decomposes it into sub-tasks per agent domain
2. Each agent announces what files it will modify before starting
3. Cross-service changes (e.g., new API endpoint) follow this order:
   - AI Agent updates Python schema/router first
   - Backend Agent updates Java client + controller
   - Frontend Agent updates React API calls last
4. After completing a task, each agent writes a short summary:
   ```
   [AGENT: Backend] Added POST /api/emails/export endpoint.
   Modified: EmailController.java, EmailService.java
   New env vars: none
   Breaking changes: none
   ```
5. Orchestrator validates integration and marks task as complete

---

## Common Gotchas

- **Redis**: `RedisConfig.java` exists but Redis is not actively used. Don't add Redis-dependent code without first enabling it in Docker Compose.
- **CORS**: Controlled by `FRONTEND_URL` env var in `CorsConfig.java`. If frontend URL changes, update `.env`.
- **Gmail pagination**: `GmailService.fetchAllEmails()` paginates ALL emails — this can be slow for large inboxes. Don't replace pagination with a single bulk call.
- **Outlook**: `OutlookService.java` exists but may be less tested than Gmail. Test both providers when touching auth or sync code.
- **WebSocket + REST chat**: `ChatController` handles both `POST /api/chat` (REST) and `/app/chat.send` (WebSocket). Both paths must work.
- **Date handling**: AI service parses relative Spanish dates ("esta semana"). If you change the system prompt, test Spanish date expressions.
- **JWT Principal**: The principal stored in `SecurityContext` is `userId` (UUID string), not email. `UserDetailsServiceImpl` loads by ID, not email.
