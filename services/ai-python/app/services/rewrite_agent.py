import logging
from app.services.ai_service import AIService

logger = logging.getLogger(__name__)

REWRITE_SYSTEM_PROMPT = """Eres un asistente experto en redacción de correos electrónicos profesionales. 
Tu tarea es mejorar el borrador que el usuario te proporcione,使其 más claro, profesional y efectivo.

INSTRUCCIONES:
1. Mantén el mensaje principal y la intención del borrador original.
2. Mejora la gramática, ortografía y estilo.
3. Adapta el tono según las indicaciones del usuario.
4. Responde en el idioma especificado. Si el idioma es "auto", detecta el idioma del email original y responde en el mismo.
5. Si se proporcionan reglas personalizadas, respétalas fielmente.
6. No inventes información que no esté en el borrador original.
7. Mantén la extensión razonable — no hagas el texto significativamente más largo ni más corto.

TONOS DISPONIBLES:
- formal: profesional, respetuoso, corporativo
- casual: relajado, amigable, informal
- amigable: cálido, cercano, personal
- directo: conciso, al punto, sin rodeos

FORMATO DE RESPUESTA:
Devuelve ÚNICAMENTE el texto mejorado, sin explicaciones adicionales ni formato markdown."""


async def rewrite_email(
    draft: str,
    original_subject: str = "",
    original_from: str = "",
    original_body: str = "",
    tone: str = "formal",
    language: str = "auto",
    custom_rules: str = "",
) -> dict:
    ai = AIService.get_instance()

    system_prompt = REWRITE_SYSTEM_PROMPT

    if custom_rules:
        system_prompt += f"\n\nREGLAS PERSONALIZADAS DEL USUARIO:\n{custom_rules}"

    context_parts = []
    if original_subject:
        context_parts.append(f"Asunto del email original: {original_subject}")
    if original_from:
        context_parts.append(f"De: {original_from}")
    if original_body:
        preview = original_body[:500]
        context_parts.append(f"Contenido original (preview): {preview}")

    context_str = "\n".join(context_parts) if context_parts else "Sin contexto del email original."

    prompt = f"""CONTEXTO DEL EMAIL:
{context_str}

TONO SOLICITADO: {tone}
IDIOMA DE RESPUESTA: {language}

BORRADOR DEL USUARIO:
{draft}

Mejora el borrador anterior siguiendo las instrucciones del system prompt."""

    logger.info("Rewrite request: tone=%s, language=%s, draft_len=%d", tone, language, len(draft))

    rewritten = await ai.generate(prompt=prompt, system_prompt=system_prompt)

    return {
        "rewritten": rewritten.strip(),
        "original": draft,
    }
