# Prompt: Auditoría completa y ligera de EmailFilter AI

> Copiá y pegá esto en el TUI de OpenCode (con `ultrawork` adelante), o guardalo como
> `.opencode/commands/audit.md` para tenerlo como slash command reutilizable (`/audit`).

---

```
ultrawork Necesito una auditoría COMPLETA pero LIGERA del proyecto EmailFilter AI.
Leé @AGENTS.md primero para tener el contexto completo de la arquitectura.

═══════════════════════════════════════════
OBJETIVO
═══════════════════════════════════════════
Revisar el proyecto de punta a punta (los 3 servicios: backend-java, frontend,
ai-python) buscando bugs, código roto, inconsistencias entre contratos de API,
vulnerabilidades básicas y deuda técnica. Documentar TODO en logs estructurados
para que un agente de IA (vos mismo en una sesión futura, u otro) pueda leerlos
y arreglar los problemas sin tener que re-analizar el proyecto entero.

NO tenés que arreglar nada en esta pasada. Esta tarea es SOLO diagnóstico.

═══════════════════════════════════════════
RESTRICCIONES DE RECURSOS (CRÍTICO — NO IGNORAR)
═══════════════════════════════════════════
Esta máquina tiene recursos limitados (16 GB RAM, Windows + WSL2 + Docker Desktop
corriendo en paralelo). Para no colgar la PC:

1. NO lances más de 2 sub-agentes en paralelo (background tasks) al mismo tiempo.
   Preferí secuencial: terminá de analizar un servicio antes de pasar al siguiente.
2. NO corras `mvn clean install`, `npm run build`, ni builds completos como parte
   de este análisis. Solo lectura estática de archivos (grep, AST, linters si ya
   están instalados). Nada que compile o levante contenedores.
3. NO uses herramientas que indexen node_modules, target/, .venv, ni carpetas de
   dependencias. Excluí explícitamente: node_modules/, target/, dist/, build/,
   .venv/, __pycache__/, .git/.
4. Si necesitás correr algo pesado (tests, build), PREGUNTAME antes de ejecutarlo.
   No asumas que puedo bancar una compilación completa mientras hago otra cosa.
5. Trabajá archivo por archivo o módulo por módulo, no cargues todo el repo en
   memoria de una — así el análisis es liviano aunque sea completo.

═══════════════════════════════════════════
ORDEN DE ANÁLISIS (secuencial, un servicio a la vez)
═══════════════════════════════════════════

### 1. Backend Java (services/backend-java/)
Recorré en este orden: config/ → model/ → repository/ → security/ → service/ →
controller/ → dto/. Por cada archivo:
- ¿Hay lógica muerta, imports sin usar, o TODOs olvidados?
- ¿El manejo de excepciones es consistente? (revisar si hay try/catch vacíos
  o que solo hacen printStackTrace)
- ¿Las queries JPA tienen riesgo de N+1 o fetch innecesario?
- ¿Los endpoints en controller/ validan input antes de llegar a service/?
- ¿JwtTokenProvider y JwtAuthenticationFilter tienen algún path donde un token
  inválido no sea rechazado correctamente?
- ¿GmailService.fetchAllEmails() maneja bien la paginación y los rate limits
  de la API de Gmail (errores 429)?
- ¿Hay credenciales, tokens o secrets hardcodeados en el código (no en .env)?

### 2. AI Service Python (services/ai-python/)
Recorré: config.py → models/ → services/ → routers/. Por cada archivo:
- ¿chat_agent.py garantiza SIEMPRE devolver el JSON {response, criteria} válido,
  incluso si el modelo de Groq responde texto libre o malformado?
- ¿Hay manejo de timeout/retry en las llamadas httpx a la API de Groq?
- ¿email_analyzer.py tiene fallback si la clasificación falla para un email?
- ¿Los schemas de Pydantic validan correctamente los campos opcionales?

### 3. Frontend React (services/frontend/)
Recorré: lib/ → context/ → hooks/ → components/ → pages/. Por cada archivo:
- ¿api.js maneja bien el caso de refresh token expirado (loop infinito de 401)?
- ¿useWebSocket.js tiene memory leaks (listeners no limpiados en useEffect)?
- ¿ChatPanel.jsx puede duplicar mensajes si WebSocket y REST responden ambos?
- ¿Hay estados de loading/error faltantes en componentes que hacen fetch?
- ¿EmailList.jsx pagina correctamente o hay riesgo de no traer todos los emails?

### 4. Contratos cruzados (usar @AGENTS.md como referencia)
Verificá que estos contratos definidos en AGENTS.md se cumplan en el código real:
- Contrato de /api/chat (backend ↔ AI service)
- Estructura JWT y claims
- Endpoints y payloads de WebSocket
- Constraint UNIQUE(userId, providerEmailId) en email_messages

Reportá cualquier divergencia entre lo documentado en AGENTS.md y lo que el
código realmente hace.

═══════════════════════════════════════════
BUENAS PRÁCTICAS DE TESTING A APLICAR
═══════════════════════════════════════════
No escribas tests todavía (eso es para una segunda pasada), pero para cada
problema detectado, anotá qué tipo de test lo hubiera atrapado, usando esta
pirámide como referencia:

**Backend Java (JUnit 5 + Mockito + Testcontainers):**
- Unit tests para lógica de negocio pura (EmailService.filterEmails,
  JwtTokenProvider) — sin Spring context, rápidos
- Integration tests con @SpringBootTest + Testcontainers (PostgreSQL real
  en contenedor efímero) para repository/ y flujos completos de controller
- Contract tests para AIServiceClient — mockear el AI service con WireMock
  para no depender de que Python esté corriendo
- Nunca testear contra la base de datos de desarrollo real

**AI Service Python (pytest + pytest-asyncio + respx):**
- Unit tests para chat_agent.py mockeando la respuesta de Groq con respx
  (httpx mock), incluyendo casos de respuesta malformada
- Tests de los Pydantic schemas con inputs inválidos/edge cases
- Test de timeout/retry simulando latencia alta del proveedor AI

**Frontend React (Vitest + React Testing Library + MSW):**
- Unit tests de hooks (useAuth, useWebSocket) con mocks de WebSocket
- Component tests de ChatPanel/EmailList con Mock Service Worker (MSW)
  interceptando las llamadas a /api
- Nunca testear contra el backend real corriendo — todo mockeado

**Regla general de la pirámide:**
70% unit tests (rápidos, aislados) / 20% integration (con dependencias reales
pero acotadas) / 10% e2e (flujo completo, solo para los happy paths críticos:
login OAuth, sync de emails, chat con filtro).

═══════════════════════════════════════════
FORMATO DE LOGS DE SALIDA (para consumo por IA)
═══════════════════════════════════════════
Generá un archivo `audit-log.json` en la raíz del proyecto con esta estructura
exacta, para que cualquier agente de IA lo pueda parsear directamente sin
tener que releer todo el código:

{
  "audit_date": "ISO 8601 timestamp",
  "project": "emailfilter",
  "summary": {
    "total_files_reviewed": number,
    "total_issues_found": number,
    "critical": number,
    "high": number,
    "medium": number,
    "low": number
  },
  "issues": [
    {
      "id": "incremental, ej. ISSUE-001",
      "service": "backend-java | ai-python | frontend | cross-service",
      "file": "ruta relativa exacta del archivo",
      "line_range": "ej. 45-52 (si aplica)",
      "severity": "critical | high | medium | low",
      "category": "bug | security | performance | dead-code | contract-mismatch | missing-error-handling",
      "description": "qué está mal, en 1-2 frases claras",
      "impact": "qué rompe o qué riesgo genera si no se arregla",
      "suggested_fix": "descripción concreta y accionable de la solución, no solo 'revisar esto'",
      "test_that_would_catch_it": "qué tipo de test (unit/integration/e2e) y qué caso específico lo hubiera detectado",
      "estimated_effort": "small | medium | large"
    }
  ],
  "cross_service_contract_violations": [
    {
      "contract": "nombre del contrato según AGENTS.md",
      "documented_behavior": "qué dice AGENTS.md",
      "actual_behavior": "qué hace el código realmente",
      "affected_files": ["lista de archivos"]
    }
  ],
  "recommended_fix_order": [
    "ISSUE-003 (bloquea sync de emails, alta prioridad)",
    "ISSUE-007 (...)"
  ]
}

Además, generá un `audit-summary.md` en texto plano y legible con:
- Un resumen ejecutivo de 3-4 líneas
- Tabla de issues ordenada por severidad
- La sección "recommended_fix_order" explicada en prosa simple

═══════════════════════════════════════════
REGLAS DE SEGURIDAD
═══════════════════════════════════════════
- NO modifiques ningún archivo de código durante esta tarea. Es solo lectura
  y generación de los dos archivos de log mencionados arriba.
- NO borres ni sobreescribas .env, .env.example, ni ningún archivo de config
  con secrets.
- Si encontrás una credencial o secret hardcodeado en el código, NO lo repitas
  textual en el log — anotá solo el archivo, la línea, y "credential hardcoded
  detected" sin exponer el valor.
- Si en algún punto el análisis requiere más de ~5 minutos de cómputo intensivo
  (ej. escaneo de seguridad pesado), pausá y preguntame si querés continuar.

Al terminar, dame un resumen corto en el chat (no el JSON completo) con:
1. Cuántos issues encontraste por severidad
2. Los 3 problemas más urgentes
3. Confirmación de que audit-log.json y audit-summary.md están listos
```

---

## Cómo usarlo

**Opción rápida** — pegalo tal cual en el TUI:
```bash
opencode
# pegás todo el bloque de arriba
```

**Opción reutilizable** — guardalo como comando:
```bash
mkdir -p .opencode/commands
# guardá el contenido del bloque (sin el "ultrawork" inicial) en:
# .opencode/commands/audit.md
```

Después simplemente:
```
/audit
```

## Siguiente paso natural

Una vez que tengas `audit-log.json`, el siguiente prompt que le vas a querer dar es algo tipo:

```
ultrawork Leé @audit-log.json. Arreglá los issues marcados como "critical"
y "high" en orden según recommended_fix_order. Por cada fix, escribí el test
correspondiente indicado en "test_that_would_catch_it" ANTES de tocar el código
(TDD). Corré solo los tests nuevos, no la suite completa, para no saturar la PC.
```

Así separás diagnóstico (liviano, sin tocar nada) de arreglo (con tests, controlado).
