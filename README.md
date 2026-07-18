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
    │                                                        └── Groq API (llama3-8b-8192)
```

### Flujo principal

1. **Login OAuth** — El usuario elige Google o Outlook, se redirige al proveedor, y al volver recibe un JWT.
2. **Sincronización de emails** — Se descargan todos los emails del buzón (inbox + spam) y se guardan en PostgreSQL con deduplicación por `(userId, providerEmailId)`.
3. **Filtro incremental** — Sincronizaciones posteriores solo traen emails nuevos (1 llamada a la API de Gmail).
4. **Sincronización completa** — Para buzones grandes, se puede descargar todo el historial con barra de progreso en tiempo real vía WebSocket.
5. **Chat con IA** — El usuario escribe en lenguaje natural ("muéstrame los emails importantes de esta semana") y la IA extrae criterios de filtro que se aplican vía JPA Specifications.

### Stack tecnológico

| Servicio | Tecnología | Puerto |
|----------|-----------|--------|
| Frontend | React 18 + Vite + TailwindCSS | 3001 |
| Backend | Spring Boot 3.2.4 (Java 17) | 8080 |
| IA | FastAPI + Groq (llama3-8b-8192) | 8000 |
| Base de datos | PostgreSQL 16 | 5433 |

---

## Requisitos previos

### Opción A: Docker (recomendado)

- **Docker Desktop** (con WSL2 activado en Windows)
- **16 GB de RAM** mínimo (el sistema usa ~3 GB con todos los contenedores)

### Opción B: Ejecución local (sin Docker)

Si preferís correr sin Docker, necesitás tener instalado:

- **Java 17** (JDK) — [Descargar](https://adoptium.net/)
- **Maven** — [Descargar](https://maven.apache.org/download.cgi)
- **Python 3.10+** — [Descargar](https://www.python.org/downloads/)
- **Node.js 18+** — [Descargar](https://nodejs.org/)
- **PostgreSQL 16** instalado y corriendo en `localhost:5432`

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
6. Agregá como **Authorized redirect URI**: `http://localhost:8080/api/auth/callback/google`
7. Copiá el Client ID y Client Secret en el `.env`:

```
GOOGLE_CLIENT_ID=tu-client-id.apps.googleusercontent.com
GOOGLE_CLIENT_SECRET=tu-client-secret
GOOGLE_REDIRECT_URI=http://localhost:8080/api/auth/callback/google
```

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
| `emailfilter-ai` | FastAPI | 8000 |
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

```bash
psql -U postgres -f infrastructure/postgres/init/01-init.sql
```

O si usás el usuario por defecto del `.env`:

```bash
psql -U emailfilter_user -d emailfilter -f infrastructure/postgres/init/01-init.sql
```

#### 4. Levantar todo con un solo comando

Doble clic en **`start-local.bat`** — esto automáticamente:

1. Verifica que PostgreSQL esté corriendo
2. Crea el entorno virtual de Python e instala dependencias
3. Compila el backend Java con Maven
4. Instala las dependencias del frontend
5. Abre 3 ventanas de consola, una por servicio

| Servicio | Puerto | Ventana |
|----------|--------|---------|
| Backend Java (Spring Boot) | http://localhost:8080 | Se abre solo |
| AI Python (FastAPI) | http://localhost:8000 | Se abre solo |
| Frontend (Vite dev server) | http://localhost:5173 | Se abre solo |

> **Nota**: en modo local el frontend corre en el puerto **5173** (Vite dev server) en vez de 3001 (nginx).

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

#### 5. Abrir la aplicación

Abrí en tu navegador: **http://localhost:5173**

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
- **Posteriores**: sincronización incremental (~1 API call, solo emails nuevos)
- **Deduplicación**: garantizada por constraint `UNIQUE(userId, providerEmailId)`
- **Soporte**: Gmail y Outlook (Microsoft Graph)

### Chat con IA

- Lenguaje natural en español e inglés
- Extrae criterios de filtro automáticamente
- Soporta fechas relativas ("esta semana", "ayer", "este mes")
- Powered by llama3-8b-8192 vía Groq (gratis)

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

# O uno por uno:
run-backend.bat      # Backend Java (puerto 8080)
run-ai.bat           # Servicio IA (puerto 8000)
run-frontend.bat     # Frontend Vite (puerto 5173)

# Crear base de datos manualmente
psql -U postgres -f infrastructure/postgres/init/01-init.sql
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
| `GOOGLE_CLIENT_ID` | Client ID de Google OAuth2 | *(requerido para Gmail)* |
| `GOOGLE_CLIENT_SECRET` | Client Secret de Google OAuth2 | *(requerido para Gmail)* |
| `OUTLOOK_CLIENT_ID` | Client ID de Outlook OAuth2 | *(requerido para Outlook)* |
| `OUTLOOK_CLIENT_SECRET` | Client Secret de Outlook OAuth2 | *(requerido para Outlook)* |
| `AI_API_KEY` | API key de Groq | *(requerido)* |
| `AI_API_URL` | URL de la API de IA | `https://api.groq.com/openai/v1` |
| `AI_MODEL` | Modelo de IA a usar | `llama-3.1-8b-instant` |
| `FRONTEND_URL` | URL del frontend (para CORS) | `http://localhost:3001` |

---

## Solución de problemas

### El backend no arranca

```bash
docker compose logs backend
```

Causa común: falta configurar `GOOGLE_CLIENT_ID` o `JWT_SECRET` en `.env`.

### La sincronización no funciona

Verificá que la API de Gmail/People esté habilitada en Google Cloud Console y que el redirect URI coincida exactamente con lo que está en `.env`.

### El chat con IA devuelve error

Verificá que `AI_API_KEY` sea válida. Podés testear directamente:

```bash
curl http://localhost:8000/health
```

### La app se siente lenta en Docker

Los contenedores están limitados en RAM/CPU por `docker-compose.yml`. Si tu PC tiene recursos de sobra, podés aumentar los límites editando los valores de `mem_limit` y `cpus`.

---

## Licencia

Proyecto privado.
