# EmailFilter AI

Aplicación web fullstack que conecta tu cuenta de Gmail vía OAuth, sincroniza los correos a
PostgreSQL y los deja buscar, filtrar, responder y redactar en lenguaje natural con IA.

---

## Qué hace

- **Sincronización de Gmail**: primera corrida completa (inbox + spam), después incremental con
  `history.list` (solo lo nuevo y los borrados).
- **Chat con IA global**: "muéstrame lo importante de esta semana", "sin leer de ayer",
  "facturas de octubre". La IA devuelve criterios que el backend aplica con JPA Specifications.
- **Asistente flotante**: disponible en Inbox, Destacados y Spam. Busca, da estrellas, manda a
  papelera, responde y redacta correos nuevos sin salir de la pantalla.
- **Contactos frecuentes**: quiénes te escriben y a quiénes les escribís, con ficha editable,
  borradores de IA y enriquecimiento (empresa / tema).
- **Dashboard**: contadores de no leídos, urgentes y pendientes, más actividad de los últimos 7 días.
- **Acciones por lote**: hasta 100 correos por operación (estrella, leído, papelera).
- **Modo claro / oscuro** y actualizaciones en vivo por WebSocket.

> Outlook (Microsoft Graph) sigue en el código pero **está desactivado en la pantalla de login**.
> Si no lo vas a usar, ignorá las variables `OUTLOOK_*`.

---

## Arquitectura

```
Browser (React)
    │
    ├── REST /api/** + WebSocket /ws ──► Java Backend (8080)
    │                                       ├── PostgreSQL (5432 / 5433 en Docker)
    │                                       ├── Gmail API
    │                                       └── HTTP ──► Python AI (8000) ──► Groq
    │
    └── Login OAuth ──► Google ──► /auth/callback ──► POST /api/auth/callback/google
```

### Stack

| Servicio | Tecnología | Puerto local | Puerto Docker |
|----------|------------|--------------|---------------|
| Frontend | React 18 + Vite + TailwindCSS | 3001 | 3001 |
| Backend | Spring Boot 3.2.4 (Java 17) | 8080 | 8080 |
| IA | FastAPI + Groq (`openai/gpt-oss-120b`) | 8000 | interno, sin publicar |
| Base de datos | PostgreSQL 16 | 5432 | 5433 |

---

## Requisitos

### Opción A — Docker (recomendado)

- **Docker Desktop** con WSL2 activado.
- ~4 GB de RAM libre (los contenedores están limitados a 1.9 GB en total).

### Opción B — Local, sin Docker

- **JDK 17 o 21** ([Adoptium](https://adoptium.net/)) con `JAVA_HOME` definido.
- **Maven** en el `PATH` (o una copia portable en `.tools/maven`).
- **Python 3.10+** (el script usa `py` o `python`).
- **Node.js 18+**.
- **PostgreSQL 16+** corriendo en `localhost:5432`.

### En ambos casos

- Un proyecto de **Google Cloud** con OAuth configurado (paso 2).
- Una API key de **Groq**, gratis en [console.groq.com](https://console.groq.com).

---

## Puesta en marcha paso a paso

### 0. Clonar

```bash
git clone https://github.com/KevinVtt/EmailAutomation.git
cd EmailAutomation
```

### 1. Crear el `.env`

```bash
cp .env.example .env      # Windows: copy .env.example .env
```

Completá como mínimo `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `AI_API_KEY`, `JWT_SECRET` y
`TOKEN_ENCRYPTION_KEY`. La tabla completa está en [Variables de entorno](#variables-de-entorno).

> **Ojo con el modo de ejecución.** El `.env.example` viene apuntando a Docker:
> `POSTGRES_HOST=postgres` y `AI_SERVICE_URL=http://ai-python:8000`.
> Si vas a correr **sin Docker**, cambiá esos dos a `localhost` y `http://localhost:8000`.

### 2. Credenciales de Google

1. Entrá a [Google Cloud Console](https://console.cloud.google.com) y creá (o elegí) un proyecto.
2. **APIs y servicios → Biblioteca → Gmail API → Habilitar**.
3. **APIs y servicios → Pantalla de consentimiento de OAuth**:
   - Tipo de usuario: **Externo**.
   - Completá nombre de la app, email de soporte y datos de contacto.
   - Scopes: `.../auth/gmail.readonly` y `.../auth/gmail.modify`.
   - En **Usuarios de prueba**, agregá tu propio correo mientras estés en modo *Testing*.
4. **Credenciales → Crear credenciales → ID de cliente de OAuth → Aplicación web**.
5. En **URIs de redireccionamiento autorizados** agregá exactamente:

   ```
   http://localhost:3001/auth/callback
   ```

6. Copiá los valores al `.env`:

   ```env
   GOOGLE_CLIENT_ID=tu-client-id.apps.googleusercontent.com
   GOOGLE_CLIENT_SECRET=tu-client-secret
   GOOGLE_REDIRECT_URI=http://localhost:3001/auth/callback
   ```

> El redirect tiene que coincidir **carácter por carácter** con `GOOGLE_REDIRECT_URI`, o el login
> falla con `redirect_uri_mismatch`. Si desplegás, agregá también la URL pública.

### 3. API key de Groq

1. Creá una cuenta en [console.groq.com](https://console.groq.com).
2. Generá una API key y ponela en el `.env`:

   ```env
   AI_API_KEY=gsk_tu_api_key
   ```

### 4. Base de datos

- **Con Docker**: no hagas nada. El contenedor de PostgreSQL ejecuta los `.sql` de
  `infrastructure/postgres/init/` la primera vez que se crea el volumen.
- **Sin Docker**: ejecutá una sola vez:

  ```bat
  setup-db.bat MI_PASSWORD_DE_POSTGRES
  ```

  Crea el rol `emailfilter_user`, la base `emailfilter` y aplica el esquema completo
  (`01-init.sql`, `02-email-contacts.sql`, `02-sync-jobs.sql`, `03-gmail-history.sql`).
  Es idempotente: podés correrlo de nuevo sin romper nada.

  Si no pasás la contraseña, `psql` la pide de forma interactiva (la del superusuario `postgres`).

### 5. Levantar los servicios

#### Opción A — Docker

```bash
docker compose up -d --build
```

| Contenedor | Servicio | Puerto |
|------------|----------|--------|
| `emailfilter-db` | PostgreSQL | 5433 |
| `emailfilter-backend` | Spring Boot | 8080 |
| `emailfilter-ai` | FastAPI | interno (no publicado) |
| `emailfilter-frontend` | React + nginx | 3001 |

Ver logs y estado:

```bash
docker compose logs -f
docker ps
```

#### Opción B — Local, todo junto

```bat
start-local.bat
```

El script, en orden:

1. Carga el `.env` (`scripts/load-env.bat`).
2. Verifica PostgreSQL en `localhost:5432` (e intenta iniciar el servicio si está apagado).
3. Crea el virtualenv de Python e instala dependencias.
4. Compila el backend con Maven.
5. Comprueba que 8000, 8080 y 3001 estén libres.
6. Abre una consola por servicio.

Después del primer arranque (ya compilado), usá la botonera:

```bat
servicios.bat            # iniciar / parar / reiniciar / estado
servicios.bat status
```

#### Opción B2 — Local, uno por uno

```bat
run-backend.bat      # Spring Boot  → 8080
run-ai.bat           # FastAPI      → 8000
run-frontend.bat     # Vite         → 3001
```

### 6. Abrir la app y entrar

1. Abrí **http://localhost:3001**.
2. **Continuar con Google** → elegí tu cuenta → aceptá los permisos.
3. Volvés al Dashboard ya logueado.

Si ves *"Acceso bloqueado: … no completó el proceso de verificación"*, tu correo no está en la
lista de usuarios de prueba. Ver [Publicar la app](#publicar-la-app-oauth-en-google).

### 7. Primera sincronización

Al entrar sin correos, la app lanza sola la **sincronización completa** y muestra el progreso por
WebSocket. Puede tardar varios minutos según el tamaño del buzón.

Después, el botón **Sincronizar** de la bandeja ofrece:

- **Actualizar nuevos**: incremental, ~1 segundo.
- **Descargar todo el historial**: corrida completa de nuevo.

### 8. Probar el asistente

En Inbox, Destacados o Spam abrí el botón flotante y probá:

- "Muéstrame los emails importantes de esta semana"
- "¿Qué tengo sin leer de ayer?"
- "Busca facturas de octubre"
- "Respondé a este diciendo que mañana confirmo"

El asistente filtra la lista visible, y en la pestaña **Contactos** permite redactar y enviar.

---

## Estructura del proyecto

```
EmailAutomation/
├── services/
│   ├── backend-java/                 # Spring Boot
│   │   └── src/main/java/com/emailfilter/
│   │       ├── controller/           # REST (auth, emails, contacts, chat) + WebSocket
│   │       ├── service/              # Gmail, Outlook, Contact, SyncJob, AIServiceClient, TokenCrypto
│   │       ├── model/                # Entidades JPA (User, EmailMessage, EmailContact, Visto, SyncJob)
│   │       ├── repository/           # Spring Data + Specifications
│   │       ├── security/             # JWT, filtro, cifrado de tokens OAuth
│   │       └── config/               # Security, CORS, WebSocket, reintentos
│   ├── frontend/                     # React + Vite
│   │   └── src/
│   │       ├── pages/                # Login, Callback, Dashboard, Inbox, Starred, Spam, Settings
│   │       ├── components/           # Layout, EmailListView, EmailDetail, ReplyPanel, assistant/
│   │       ├── context/              # Auth, Theme, ScreenScope
│   │       ├── hooks/                # useWebSocket, useUndoableSend
│   │       └── lib/                  # api.js, websocket.js, auth.js
│   └── ai-python/                    # FastAPI
│       └── app/
│           ├── routers/              # /chat, /analyze, /rewrite, /compose, /contact-profile, /health
│           └── services/             # chat_agent, compose_agent, rewrite_agent, email_analyzer
├── infrastructure/postgres/init/     # Esquema SQL (fuente de verdad de la DB)
├── docker-compose.yml
├── setup-db.bat                      # Rol + base + esquema (sin Docker)
├── start-local.bat                   # Compila y levanta los 3 servicios
├── servicios.bat                     # Botonera de start/stop/restart/status
├── run-backend.bat / run-ai.bat / run-frontend.bat
├── scripts/load-env.bat              # Carga .env en el entorno
└── .env.example
```

---

## Variables de entorno

### Base de datos

| Variable | Descripción | Default |
|----------|-------------|---------|
| `POSTGRES_DB` | Nombre de la base | `emailfilter` |
| `POSTGRES_USER` | Usuario | `emailfilter_user` |
| `POSTGRES_PASSWORD` | Contraseña | `changeme_in_prod` |
| `POSTGRES_HOST` | Host (`postgres` en Docker, `localhost` sin Docker) | `postgres` |
| `POSTGRES_PORT` | Puerto | `5432` |

### Autenticación

| Variable | Descripción | Default |
|----------|-------------|---------|
| `JWT_SECRET` | Secreto HMAC para access y refresh tokens | *(requerido)* |
| `JWT_EXPIRATION_MS` | Duración del access token | `86400000` (24 h) |
| `JWT_REFRESH_EXPIRATION_MS` | Duración del refresh token | `604800000` (7 días) |
| `TOKEN_ENCRYPTION_KEY` | Cifra en reposo los tokens OAuth (AES-GCM). Sin ella quedan en texto plano | *(recomendada)* |

### Sincronización y contactos

| Variable | Descripción | Default |
|----------|-------------|---------|
| `EMAIL_SYNC_MAX_EMAILS` | Tope de correos por corrida (inicial e incremental) | `1000` |
| `EMAIL_SYNC_REQUEST_DELAY_MS` | Pausa entre descargas para respetar la cuota de Gmail | `250` |
| `EMAIL_SYNC_FETCH_CONCURRENCY` | Hilos de descarga de detalle en la sync completa | `6` |
| `CONTACTS_SENT_WINDOW_DAYS` | Días de enviados que se leen al armar contactos | `365` |
| `CONTACTS_SENT_MAX` | Tope de enviados en ese barrido | `2000` |

### Google / Outlook

| Variable | Descripción |
|----------|-------------|
| `GOOGLE_CLIENT_ID` | Client ID de OAuth2 |
| `GOOGLE_CLIENT_SECRET` | Client Secret |
| `GOOGLE_REDIRECT_URI` | Debe coincidir con el autorizado en Google Cloud |
| `OUTLOOK_CLIENT_ID` / `OUTLOOK_CLIENT_SECRET` | Opcionales, Outlook está desactivado en la UI |
| `OUTLOOK_REDIRECT_URI` | Idem |
| `OUTLOOK_TENANT` | `common` por defecto |

### Servicio de IA

| Variable | Descripción | Default |
|----------|-------------|---------|
| `AI_SERVICE_URL` | URL del servicio Python (`http://ai-python:8000` en Docker) | `http://localhost:8000` |
| `AI_API_KEY` | API key de Groq | *(requerido)* |
| `AI_API_URL` | Endpoint compatible con OpenAI | `https://api.groq.com/openai/v1` |
| `AI_MODEL` | Modelo | `openai/gpt-oss-120b` |
| `AI_PROVIDER` | Proveedor | `openai` |

### Frontend

| Variable | Descripción | Default |
|----------|-------------|---------|
| `FRONTEND_URL` | Origen permitido por CORS | `http://localhost:3001` |
| `VITE_API_URL` | Base de la API si el frontend está en otro dominio | vacío (mismo origen) |
| `VITE_WS_URL` | Base del WebSocket si está en otro dominio | vacío (mismo origen) |

---

## Publicar la app (OAuth en Google)

Mientras el proyecto esté en **Testing**, solo entran los correos cargados en *Usuarios de prueba*
(máximo 100) y el refresh token caduca a los 7 días. Cualquier otra cuenta ve
`Error 403: access_denied`.

| Estado | Quién entra | Límite | Aviso |
|--------|-------------|--------|-------|
| Testing | Solo usuarios de prueba | 100 | "app en pruebas" |
| Publicada sin verificar | Cualquier cuenta Google | **100 en total** | Pantalla "no verificada" |
| Publicada y verificada | Cualquier cuenta | Sin límite | Ninguno |

Para publicar (**Google Auth platform → Audience → Publish app**) necesitás:

1. Un dominio público con HTTPS (no `localhost`).
2. Homepage y política de privacidad en ese dominio.
3. El redirect público agregado al cliente OAuth y en `GOOGLE_REDIRECT_URI`.

Publicar sin verificar es gratis. La verificación completa exige revisión de marca, justificación de
los scopes restringidos (`gmail.readonly` y `gmail.modify` lo son) y una auditoría **CASA** anual de
pago.

---

## Deploy (referencia)

Configuración mínima para un deploy gratuito separado por servicio:

| Pieza | Plataforma | Variables |
|-------|------------|-----------|
| Frontend | Vercel | `VITE_API_URL` y `VITE_WS_URL` = URL del backend |
| Backend | Render (Docker) | `POSTGRES_*`, `JWT_SECRET`, `TOKEN_ENCRYPTION_KEY`, `GOOGLE_*`, `FRONTEND_URL`, `AI_SERVICE_URL` |
| IA | Render (Docker) | `AI_API_KEY`, `AI_MODEL` |
| PostgreSQL | Neon | Aplicar los `.sql` de `infrastructure/postgres/init/` |

En Google Cloud, el redirect pasa a `https://<frontend>/auth/callback`, y `FRONTEND_URL` debe ser
`https://<frontend>` para que CORS y el WebSocket funcionen.

Si preferís un solo servidor, el `docker-compose.yml` ya levanta todo junto y el nginx del frontend
hace de proxy para `/api` y `/ws`.

---

## Comandos útiles

```bash
# Docker
docker compose up -d --build
docker compose logs -f backend
docker compose restart backend
docker compose down            # conserva los datos
docker compose down -v         # ⚠️ borra la base

# Local
start-local.bat                # compila y levanta todo
servicios.bat                  # botonera de control
setup-db.bat                   # rol + base + esquema (idempotente)
```

## Tests

```bash
# Backend
cd services/backend-java && mvn test

# Frontend
cd services/frontend && npm test

# IA
cd services/ai-python && python -m pytest
```

El test de integración del backend usa Testcontainers: necesita el daemon de Docker corriendo.

---

## Problemas comunes

### `Error 403: access_denied` al iniciar sesión

La app está en *Testing* y esa cuenta no es usuario de prueba. Agregala en
**Google Auth platform → Audience → Usuarios de prueba**, o publicá la app.

### `redirect_uri_mismatch`

El `GOOGLE_REDIRECT_URI` del `.env` no coincide exactamente con el autorizado en Google Cloud
(esquema, puerto y barra final incluidos).

### El backend no arranca

- Falta `JWT_SECRET` o `GOOGLE_CLIENT_ID` en el `.env`.
- Estás sin Docker con `POSTGRES_HOST=postgres`: cambialo a `localhost`.

### Error de columna o tabla faltante al arrancar (`ddl-auto: validate`)

La base quedó atrás respecto del esquema. Sin Docker, volvé a correr `setup-db.bat`. Con Docker,
aplicá el `.sql` que falte a mano:

```bash
psql -U postgres -d emailfilter -f infrastructure/postgres/init/03-gmail-history.sql
```

En el perfil `dev` (`-Dspring-boot.run.profiles=dev`) Hibernate crea lo que falta.

### La sincronización no trae correos

Verificá que la **Gmail API** esté habilitada, que el redirect coincida y que la cuenta haya
autorizado los scopes `gmail.readonly` + `gmail.modify`.

### El chat o los borradores fallan

`AI_API_KEY` inválida o sin cuota. Probá el servicio:

```bash
curl http://localhost:8000/health
```

### "El puerto 3001 ya está en uso"

Hay otra instancia corriendo; `start-local.bat` se niega a abrir una segunda para no duplicar
sesiones ni sincronizaciones. Cerrá la anterior o usá `servicios.bat stop`.

### La app va lenta en Docker

Los contenedores tienen `mem_limit` y `cpus` en `docker-compose.yml`. Si te sobra RAM, subilos.

---

## Licencia

Proyecto privado.
