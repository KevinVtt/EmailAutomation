# TESTING.md – Guía de Pruebas

## Resumen de cobertura

| Componente | Tipo | Archivos | Estado |
|-----------|------|----------|--------|
| Backend Java | Unitarias | `AuthServiceTest`, `JwtTokenProviderTest`, `AIServiceClientTest`, `FilterServiceTest`, `WebSocketServiceTest` | ✅ Creadas |
| Backend Java | Controller | `AuthControllerTest`, `EmailControllerTest` | ✅ Creadas |
| Backend Java | Integración | `EmailFilterIntegrationTest` (Testcontainers + PostgreSQL) | ✅ Creado |
| AI Service (Python) | Unitarias | `test_ollama_service`, `test_email_analyzer`, `test_chat_agent` | ✅ Creadas |
| AI Service (Python) | API/Routers | `test_routers` | ✅ Creadas |
| Frontend (React) | Unitarias | `auth.test.js`, `api.test.js`, `useAuth.test.js` | ✅ Creadas |
| Frontend (React) | Componentes | `EmailCard.test.jsx`, `ChatPanel.test.jsx` | ✅ Creadas |
| Frontend (React) | Context | `AuthContext.test.jsx` | ✅ Creadas |
| Frontend (React) | Páginas | `Login.test.jsx` | ✅ Creada |

---

## Ejecución de pruebas

### Backend Java (Maven)
```bash
cd services/backend-java

# Pruebas unitarias
mvn test

# Pruebas de integración (requiere Docker para Testcontainers)
mvn verify

# Pruebas específicas
mvn test -Dtest=AuthServiceTest
mvn test -Dtest=EmailFilterIntegrationTest
```

**Nota:** Las pruebas de integración usan Testcontainers y requieren Docker Desktop corriendo.

### AI Service (Python + pytest)
```bash
cd services/ai-python

# Instalar dependencias de test
pip install -r requirements.txt

# Ejecutar todas las pruebas
pytest tests/ -v

# Con cobertura
pytest tests/ --cov=app --cov-report=term-missing -v

# Pruebas específicas
pytest tests/test_ollama_service.py -v
pytest tests/test_routers.py -v
```

### Frontend (Vitest)
```bash
cd services/frontend

# Instalar dependencias
npm install

# Ejecutar pruebas
npm test

# Modo watch
npx vitest

# Con cobertura
npx vitest --coverage
```

---

## Estructura de archivos de prueba

```
services/backend-java/src/test/java/com/emailfilter/
├── service/
│   ├── AuthServiceTest.java
│   ├── AIServiceClientTest.java
│   ├── FilterServiceTest.java
│   └── WebSocketServiceTest.java
├── security/
│   └── JwtTokenProviderTest.java
├── controller/
│   ├── AuthControllerTest.java
│   └── EmailControllerTest.java
├── integration/
│   └── EmailFilterIntegrationTest.java
└── resources/
    └── application-test.yml

services/ai-python/tests/
├── conftest.py
├── test_ollama_service.py
├── test_email_analyzer.py
├── test_chat_agent.py
└── test_routers.py

services/frontend/src/__tests__/
├── auth.test.js
├── api.test.js
├── useAuth.test.js
├── AuthContext.test.jsx
├── EmailCard.test.jsx
├── ChatPanel.test.jsx
└── Login.test.jsx
```

---

## Dependencias de prueba

### Backend (pom.xml)
- `spring-boot-starter-test` – JUnit 5, Mockito, MockMvc
- `testcontainers:postgresql:1.19.7` – PostgreSQL container para integración
- `h2` – Base de datos en memoria para pruebas rápidas (opcional)

### AI Service (requirements.txt)
- `pytest==8.1.1` – Framework de testing
- `pytest-asyncio==0.23.6` – Soporte para tests asíncronos
- `pytest-httpx==0.30.0` – Mock HTTP para httpx

### Frontend (package.json)
- `vitest^1.4.0` – Framework de testing (bundled con Vite)
- `@testing-library/react^14.2.2` – Testing de componentes React
- `@testing-library/jest-dom^6.4.2` – Matchers DOM adicionales
- `jsdom^24.0.0` – Entorno DOM para Node.js
