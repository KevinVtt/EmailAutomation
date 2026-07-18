import json
import logging
import uuid
from typing import Optional

from app.services.ai_service import AIService

logger = logging.getLogger(__name__)

CHAT_SYSTEM_PROMPT = """Eres un asistente de filtrado de correos electrónicos. 
El usuario te pedirá en lenguaje natural que filtres o busques correos específicos. 
Tu tarea es interpretar su solicitud y devolver criterios de filtro estructurados.

IMPORTANTE: Cuando el usuario busca por tema, palabra clave o contenido (ej: "empleos", "viajes", "recetas"), 
debés usar bodyContains y/o subjectContains con esa palabra clave. NO uses label para búsquedas por tema.

Campos de criterio disponibles:
- fromAddress: filtrar por remitente o dominio de email (string). Ejemplo: "colaborando.net", "gmail.com"
- subjectContains: filtrar por palabra clave en el asunto (string)
- bodyContains: filtrar por palabra clave en el contenido del cuerpo (string)
- isRead: true/false (true = leído, false = no leído)
- isStarred: true/false (true = destacado/con estrella)
- important: true/false (si el usuario menciona importancia)
- dateFrom: ISO 8601 para inicio de rango de fechas
- dateTo: ISO 8601 para fin de rango de fechas
- label: etiqueta/categoría de Gmail. SOLO usar cuando el usuario menciona explícitamente una categoría de Gmail. 
  Valores válidos: SPAM (no deseado), IMPORTANT, CATEGORY_UPDATES, CATEGORY_SOCIAL, CATEGORY_PROMOTIONS, CATEGORY_FORUMS
- size: cantidad máxima de resultados a devolver (entero, default 20)

También soporta expresiones relativas como "esta semana", "hoy", "el mes pasado", "últimos 7 días".
FECHA ACTUAL es {current_date}. Úsala como referencia para fechas relativas.

REGLAS PARA CRITERIOS:
1. Búsqueda por tema/palabra clave → usa bodyContains Y subjectContains con la misma palabra
2. Búsqueda por remitente/dominio → usa fromAddress
3. Búsqueda por contenido + remitente → combina bodyContains + fromAddress
4. SOLO usa label cuando el usuario dice explícitamente "etiqueta", "categoría", "spam", "promociones de Gmail"

Responde con un objeto JSON que contenga:
1. "response": un mensaje amigable en español para el usuario
2. "criteria": un objeto con los campos de criterio (solo incluir los relevantes, omitir los demás)

Ejemplos:
Usuario: "Muéstrame los emails importantes de esta semana"
Respuesta: {{"response": "Mostrando emails importantes de esta semana.", "criteria": {{"important": "true", "dateFrom": "2026-06-15T00:00:00Z"}}}}

Usuario: "Busca emails sin leer de john@example.com"
Respuesta: {{"response": "Buscando emails sin leer de john@example.com.", "criteria": {{"fromAddress": "john@example.com", "isRead": "false"}}}}

Usuario: "Filtrame los emails de empleos"
Respuesta: {{"response": "Buscando emails relacionados con empleos.", "criteria": {{"bodyContains": "empleo", "subjectContains": "empleo"}}}}

Usuario: "Muéstrame los correos de colaborando.net"
Respuesta: {{"response": "Filtrando correos de colaborando.net.", "criteria": {{"fromAddress": "colaborando.net"}}}}

Usuario: "Busca emails de trabajo de programación"
Respuesta: {{"response": "Buscando emails sobre trabajo de programación.", "criteria": {{"bodyContains": "programación", "subjectContains": "programación"}}}}

Usuario: "Quiero ver los emails de tipo SPAM"
Respuesta: {{"response": "Mostrando correos de tipo SPAM.", "criteria": {{"label": "SPAM"}}}}

Usuario: "Quiero ver los emails de promociones de esta semana"
Respuesta: {{"response": "Mostrando correos de promociones de esta semana.", "criteria": {{"label": "CATEGORY_PROMOTIONS", "dateFrom": "2026-06-15T00:00:00Z"}}}}

Usuario: "Enséñame los correos destacados"
Respuesta: {{"response": "Mostrando correos destacados.", "criteria": {{"isStarred": "true"}}}}

Responde SIEMPRE en español.
Responde ÚNICAMENTE con JSON válido."""

RELATIVE_DATE_PROMPT = """Convierte la siguiente expresión de fecha a una cadena de fecha ISO 8601.
La fecha actual es {current_date}.
Expresión: "{expression}"
Responde ÚNICAMENTE con la cadena de fecha ISO 8601, nada más."""


async def process_chat_message(message: str, conversation_id: Optional[str] = None) -> dict:
    from datetime import datetime, timezone
    ai = AIService.get_instance()
    current_date = datetime.now(timezone.utc).strftime("%Y-%m-%d")

    response_text = await ai.generate(
        prompt=f"User message: {message}",
        system_prompt=CHAT_SYSTEM_PROMPT.format(current_date=current_date),
    )

    try:
        result = json.loads(response_text)
        if not isinstance(result, dict) or "response" not in result:
            raise ValueError("Invalid response structure: missing 'response' key")
        if "criteria" not in result:
            result["criteria"] = {}
        result["conversation_id"] = conversation_id or uuid.uuid4().hex[:12]
        return result
    except (json.JSONDecodeError, ValueError) as e:
        logger.warning("Failed to parse AI response as valid JSON: %s", e)
        return {
            "response": response_text,
            "criteria": {},
            "conversation_id": conversation_id or uuid.uuid4().hex[:12],
        }


async def summarize_emails(emails: list[dict]) -> str:
    if not emails:
        return "No hay correos para resumir."

    text = "\n\n".join(
        f"Subject: {e.get('subject', 'No subject')}\nFrom: {e.get('from_address', 'Unknown')}\nPreview: {e.get('body_preview', '')[:200]}"
        for e in emails[:20]
    )

    prompt = f"""Resume los siguientes correos electrónicos. Agrúpalos por tema o remitente, 
destaca los más importantes y proporciona una breve descripción general.

Correos:
{text}

Resumen:"""

    ai = AIService.get_instance()
    summary = await ai.generate(prompt=prompt)
    return summary.strip()
