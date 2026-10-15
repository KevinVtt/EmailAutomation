# EmailFilter AI

Aplicación web fullstack que conecta a Gmail/Outlook vía OAuth, sincroniza tus emails a una base de datos PostgreSQL local, y expone un chat con IA para buscar, filtrar y clasificar emails en lenguaje natural.

---

## Arquitectura

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

### Flujo principal

1. **Login OAuth** — El usuario elige Google o Outlook, se redirige al proveedor, y al volver recibe un JWT.
2. **Sincronización de emails** — Se descargan todos los emails del buzón (inbox + spam) y se guardan en PostgreSQL con deduplicación por `(userId, providerEmailId)`.
3. **Sincronización incremental** — Las corridas posteriores detectan cambios con la API de historial de Gmail (`history.list`) en lugar de re-descargar el buzón: solo traen emails nuevos y detectan los borrados.
4. **Sincronización completa** — Para buzones grandes, se puede descargar todo el historial con barra de progreso en tiempo real vía WebSocket.
5. **Chat con IA** — El usuario escribe en lenguaje natural ("muéstrame los emails importantes de esta semana") y la IA extrae criterios de filtro que se aplican vía JPA Specifications.

### Stack tecnológico

| Servicio | Tecnología | Puerto |
|----------|-----------|--------|
| Frontend | React 18 + Vite + TailwindCSS | 3001 |
| Backend | Spring Boot 3.2.4 (Java 17) | 8080 |
| IA | FastAPI + Groq (openai/gpt-oss-120b) | 8000 (solo red interna en Docker) |
| Base de datos | PostgreSQL 16 (Docker) / 18 (local) | 5433 (Docker) / 5432 (local) |

---

## Requisitos previos

### Opción A: Docker (recomendado)

- **Docker Desktop** (con WSL2 activado en Windows)
- **16 GB de RAM** mínimo (el sistema usa ~3 GB con todos los contenedores)

### Opción B: Ejecución local (sin Docker)

Si preferís correr sin Docker, necesitás tener instalado:

- **Java 17 o 21** (JDK) — [Descargar](https://adoptium.net/)
- **Maven** — [Descargar](https://maven.apache.org/download.cgi). *(También podés dejar una copia portable en `.tools/maven`; los scripts la usan automáticamente.)*
- **Python 3.10+** — [Descargar](https://www.python.org/downloads/)
- **Node.js 18+** — [Descargar](https://nodejs.org/)
- **PostgreSQL** instalado y corriendo en `localhost:5432`

### Para ambas opciones

- Una cuenta de **Google Cloud** o **Microsoft Azure** con OAuth configurado
- Una API key de **Groq** (gratis en [console.groq.com](https://console.groq.com))

---

## Paso a paso: levantar el proyecto

### Opción A: Con Docker (recomendado)

#### 1. Clonar el repositorio

```bash
git clone https://github.com/KevinVtt/EmailAutomation.git
cd EmailAutomation
```

#### 2. Configurar variables de entorno

```bash
cp .env.example .env
```

Abrí el archivo `.env` y completá las siguientes variables:

##### Google OAuth2

1. Andá a [Google Cloud Console](https://console.cloud.google.com)
2. Creá un proyecto (o usá uno existente)
3. Habilitá la **Google People API** y la **Gmail API**
4. Andá a **APIs & Services > Credentials**
5. Creá un **OAuth 2.0 Client ID** (tipo Web Application)
6. Agregá como **Authorized redirect URI**: `http://localhost:3001/auth/callback`
7. Copiá el Client ID y Client Secret en el `.env`:

```
GOOGLE_CLIENT_ID=tu-client-id.apps.googleusercontent.com
GOOGLE_CLIENT_SECRET=tu-client-secret
GOOGLE_REDIRECT_URI=http://localhost:3001/auth/callback
```

> El `redirect URI` debe coincidir **exactamente** con `GOOGLE_REDIRECT_URI` del `.env`
> (y con el `OUTLOOK_REDIRECT_URI` para Microsoft). Si no coincide, el login falla con
> `redirect_uri_mismatch`.

##### Groq API

1. Andá a [console.groq.com](https://console.groq.com)
2. Creá una cuenta gratuita y generá una API key
3. Ponela en el `.env`:

```
AI_API_KEY=gsk_tu-api-key
```

##### JWT Secret

Cambialo por un string seguro para producción:

```
JWT_SECRET=un-string-largo-y-aleatorio-de-al-menos-32-caracteres
```

#### 3. Levantar con Docker Compose

```bash
docker compose up -d --build
```

Esto levanta los 4 servicios:

| Contenedor | Servicio | Puerto |
|------------|----------|--------|
| `emailfilter-db` | PostgreSQL | 5433 |
| `emailfilter-backend` | Spring Boot | 8080 |
| `emailfilter-ai` | FastAPI | interno (no publicado) |
| `emailfilter-frontend` | React (nginx) | 3001 |

Para ver los logs en tiempo real:

```bash
docker compose logs -f
```

Para ver el estado:

```bash
docker ps
```

#### 4. Abrir la aplicación

Abrí en tu navegador: **http://localhost:3001**

---

### Opción B: Sin Docker (ejecución local)

#### 1. Clonar el repositorio

```bash
git clone https://github.com/KevinVtt/EmailAutomation.git
cd EmailAutomation
```

#### 2. Configurar variables de entorno

Igual que la opción Docker: copiá `.env.example` a `.env` y completá las variables.

#### 3. Crear la base de datos

Ejecutá una sola vez **`setup-db.bat`** (doble clic o por consola). Esto:

1. Crea el rol `emailfilter_user` y la base `emailfilter` si no existen.
2. Aplica el esquema (`01-init.sql` + `02-sync-jobs.sql` + `03-gmail-history.sql`) de forma idempotente.

```bat
setup-db.bat MI_PASSWORD_DE_POSTGRES
```

Si no pasás la contraseña como argumento, `psql` la pedirá de forma interactiva
(la del usuario superusuario `postgres`).

#### 4. Levantar todo con un solo comando

Doble clic en **`start-local.bat`** — esto automáticamente:

1. Carga las variables del `.env` en el entorno
2. Verifica que PostgreSQL esté corriendo
3. Crea el entorno virtual de Python e instala dependencias
4. Compila el backend Java con Maven (portable en `.tools/maven` o del PATH)
5. Abre 3 ventanas de consola, una por servicio

| Servicio | Puerto | Ventana |
|----------|--------|---------|
| Backend Java (Spring Boot) | http://localhost:8080 | Se abre solo |
| AI Python (FastAPI) | http://localhost:8000 | Se abre solo |
| Frontend (Vite dev server) | http://localhost:3001 | Se abre solo |

#### Alternativa: levantar servicios individualmente

Si preferís levantar uno por uno, usá estos archivos `.bat`:

```bash
# Terminal 1 - Backend
run-backend.bat

# Terminal 2 - Servicio de IA
run-ai.bat

# Terminal 3 - Frontend
run-frontend.bat
```

Cada `.bat` compila e inicia su servicio en una ventana separada.

#### Control del día a día: `servicios.bat`

Una vez que ya corriste `start-local.bat` al menos una vez, usá **`servicios.bat`**:
es una botonera que inicia, para, reinicia y muestra el estado de los 3 servicios
sin compilar nada (arranca al instante).

```bash
servicios.bat            # abre la botonera
servicios.bat status     # también acepta: start | stop | restart | status
```

La opción `[6] Salir y parar todo` para los 3 servicios y cierra la botonera.

#### 5. Abrir la aplicación

Abrí en tu navegador: **http://localhost:3001**

---

### Pasos comunes (ambas opciones)

#### 6. Iniciar sesión

1. Hacé clic en **"Continuar con Google"** (o Outlook si lo configuraste)
2. Autorizá la aplicación para acceder a tu correo
3. Serás redirigido al Dashboard

#### 7. Sincronizar emails

Al iniciar sesión por primera vez, la app detecta que no hay emails y lanza automáticamente una **sincronización completa** del historial.

- La barra de progreso muestra el avance en tiempo real
- Para buzones grandes, puede tardar varios minutos
- Las sincronizaciones posteriores son **incrementales** (solo emails nuevos, ~1 segundo)

También podés usar el botón **"Sincronizar"** en la bandeja de entrada:

- **Actualizar nuevos emails** — rápido, solo trae lo nuevo
- **Descargar todo el historial** — descarga completa del buzón

#### 8. Usar el chat con IA

En el panel derecho, escribí frases como:

- "Muéstrame los emails importantes de esta semana"
- "Busca emails de facturación"
- "¿Qué emails tengo sin leer de ayer?"
- "Filtrá por remitentes de Google"

La IA interpreta tu consulta y filtra los emails automáticamente.

---

## Estructura del proyecto

```
EmailAutomation/
├── services/
│   ├── backend-java/            # Spring Boot API
│   │   ├── src/main/java/com/emailfilter/
│   │   │   ├── controller/      # REST + WebSocket endpoints
│   │   │   ├── service/         # Lógica de negocio (auth, emails, chat, Gmail/Outlook)
│   │   │   ├── model/           # JPA entities
│   │   │   ├── repository/      # Spring Data repos
│   │   │   ├── security/        # JWT auth filters
│   │   │   └── config/          # Security, CORS, WebSocket config
│   │   └── Dockerfile
│   ├── frontend/                # React + Vite
│   │   ├── src/
│   │   │   ├── pages/           # Login, Callback, Dashboard
│   │   │   ├── components/      # Layout, EmailList, EmailCard, EmailDetail, ChatPanel
│   │   │   ├── context/         # AuthContext, ThemeContext (dark mode)
│   │   │   ├── hooks/           # useAuth, useWebSocket
│   │   │   └── lib/             # api.js (REST client + JWT refresh)
│   │   └── Dockerfile
│   └── ai-python/               # FastAPI IA service
│       ├── app/
│       │   ├── routers/         # /chat, /analyze, /health
│       │   ├── services/        # chat_agent.py, email_analyzer.py
│       │   └── models/          # Pydantic schemas
│       └── Dockerfile
├── infrastructure/
│   └── postgres/init/           # Scripts de inicialización SQL
├── docker-compose.yml           # Levantar todo con Docker
├── start-local.bat              # Levantar todo sin Docker (un solo clic)
├── servicios.bat                # Botonera: iniciar / parar / reiniciar / estado
├── run-backend.bat              # Levantar solo el backend
├── run-frontend.bat             # Levantar solo el frontend
├── run-ai.bat                   # Levantar solo el servicio de IA
├── .env.example                 # Template de variables de entorno
└── AGENTS.md                    # Documentación para agentes de código
```

---

## Funcionalidades

### Sincronización de emails

- **Primera vez**: descarga completa del historial (inbox + spam) con barra de progreso
- **Posteriores**: sincronización incremental (solo emails nuevos, y detecta los borrados hechos en Gmail)
- **Deduplicación**: garantizada por constraint `UNIQUE(userId, providerEmailId)`
- **Soporte**: Gmail y Outlook (Microsoft Graph)

### Chat con IA

- Lenguaje natural en español e inglés
- Extrae criterios de filtro automáticamente
- Soporta fechas relativas ("esta semana", "ayer", "este mes")
- Powered by openai/gpt-oss-120b vía Groq (gratis)

### Filtros

- Por remitente, asunto, contenido, fecha, estrella, leído/no leído
- Por etiqueta (SPAM, IMPORTANT, CATEGORY_UPDATES, etc.)
- Combinación de filtros manuales + IA

### Interfaz

- **Modo claro / oscuro** con toggle en el header (persiste en localStorage)
- **Panel de chat** con historial de conversación
- **Vista detalle** de email con acciones (star, read/unread, trash)
- **Paginación** y **filtros por fecha**
- **WebSocket** para actualizaciones en tiempo real

---

## Comandos útiles

### Docker

```bash
# Levantar todo
docker compose up -d --build

# Ver logs de un servicio específico
docker compose logs -f backend
docker compose logs -f ai-python

# Reiniciar un servicio
docker compose restart backend

# Detener todo
docker compose down

# Detener y borrar datos (⚠️ borra la base de datos)
docker compose down -v

# Reconstruir un solo servicio
docker compose build frontend --no-cache
docker compose up -d frontend
```

### Local (sin Docker)

```bash
# Levantar todo junto
start-local.bat

# Controlar los servicios ya compilados (iniciar/parar/reiniciar/estado)
servicios.bat

# O uno por uno:
run-backend.bat      # Backend Java (puerto 8080)
run-ai.bat           # Servicio IA (puerto 8000)
run-frontend.bat     # Frontend Vite (puerto 3001)

# Crear rol, base de datos y esquema (idempotente)
setup-db.bat
```

---

## Variables de entorno

| Variable | Descripción | Default |
|----------|-------------|---------|
| `POSTGRES_DB` | Nombre de la base de datos | `emailfilter` |
| `POSTGRES_USER` | Usuario de PostgreSQL | `emailfilter_user` |
| `POSTGRES_PASSWORD` | Contraseña de PostgreSQL | `changeme_in_prod` |
| `JWT_SECRET` | Secreto para firmar JWTs | *(requerido)* |
| `JWT_EXPIRATION_MS` | Duración del access token | `86400000` (24h) |
| `JWT_REFRESH_EXPIRATION_MS` | Duración del refresh token | `604800000` (7 días) |
| `TOKEN_ENCRYPTION_KEY` | Clave para cifrar en reposo los tokens OAuth (AES-GCM). Sin ella se guardan en texto plano (solo dev) | *(recomendada)* |
| `GOOGLE_CLIENT_ID` | Client ID de Google OAuth2 | *(requerido para Gmail)* |
| `GOOGLE_CLIENT_SECRET` | Client Secret de Google OAuth2 | *(requerido para Gmail)* |
| `OUTLOOK_CLIENT_ID` | Client ID de Outlook OAuth2 | *(requerido para Outlook)* |
| `OUTLOOK_CLIENT_SECRET` | Client Secret de Outlook OAuth2 | *(requerido para Outlook)* |
| `AI_API_KEY` | API key de Groq | *(requerido)* |
| `AI_API_URL` | URL de la API de IA | `https://api.groq.com/openai/v1` |
| `AI_MODEL` | Modelo de IA a usar | `openai/gpt-oss-120b` |
| `FRONTEND_URL` | URL del frontend (para CORS) | `http://localhost:3001` |

---

## Solución de problemas

### El backend no arranca

```bash
docker compose logs backend
```

Causa común: falta configurar `GOOGLE_CLIENT_ID` o `JWT_SECRET` en `.env`.

### Error de columna `history_id` al arrancar el backend

Una base creada antes de la sincronización incremental no tiene la columna `history_id` y el
perfil de producción (`ddl-auto: validate`) rechaza el arranque. Aplicá la migración (idempotente)
y reiniciá:

```bash
psql -U postgres -d emailfilter -f infrastructure/postgres/init/03-gmail-history.sql
```

En el perfil `dev` la columna se crea sola (`ddl-auto: update`), así que no hace falta.

### La sincronización no funciona

Verificá que la API de Gmail/People esté habilitada en Google Cloud Console y que el redirect URI coincida exactamente con lo que está en `.env`.

### El chat con IA devuelve error

Verificá que `AI_API_KEY` sea válida. Podés testear directamente (el puerto 8000 solo está publicado en desarrollo local; en Docker el servicio vive en la red interna):

```bash
curl http://localhost:8000/health
```

### La app se siente lenta en Docker

Los contenedores están limitados en RAM/CPU por `docker-compose.yml`. Si tu PC tiene recursos de sobra, podés aumentar los límites editando los valores de `mem_limit` y `cpus`.

---

## Licencia

Proyecto privado.
