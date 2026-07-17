import json
import logging
from typing import Any

from app.models.schemas import EmailItem
from app.services.ai_service import AIService

logger = logging.getLogger(__name__)

CLASSIFY_SYSTEM_PROMPT = """You are an email classification assistant. 
Analyze the following emails and classify each one into one of these categories:
- important: urgent or requires immediate attention
- work: work-related emails
- personal: personal correspondence
- spam: unsolicited or promotional
- social: social network notifications
- promotions: marketing, deals, offers
- updates: newsletters, digests, notifications
- finance: banking, billing, invoices
- travel: travel bookings, itineraries
- other: anything else

For each email, determine:
1. The category label
2. A priority score (1-10)
3. Whether it requires action
4. A brief reason for the classification

Respond ONLY with a valid JSON array of objects, each with keys: id, label, priority, requires_action, reason."""


async def analyze_single_email(email: EmailItem) -> dict[str, Any]:
    text = f"Subject: {email.subject}\nFrom: {email.from_address}\nPreview: {email.body_preview}\n"
    ai = AIService.get_instance()
    response = await ai.generate(prompt=text, system_prompt=CLASSIFY_SYSTEM_PROMPT)
    try:
        result = json.loads(response)
        if isinstance(result, list) and len(result) > 0:
            return result[0]
    except json.JSONDecodeError:
        logger.warning("Failed to parse AI classification JSON")
    return {
        "id": email.provider_email_id or email.id,
        "label": "other",
        "priority": 5,
        "requires_action": False,
        "reason": "Classification failed",
    }


async def analyze_emails_batch(emails: list[EmailItem]) -> list[dict[str, Any]]:
    import asyncio

    semaphore = asyncio.Semaphore(5)

    async def analyze_with_semaphore(email: EmailItem) -> dict[str, Any]:
        async with semaphore:
            return await analyze_single_email(email)

    tasks = [analyze_with_semaphore(email) for email in emails]
    return await asyncio.gather(*tasks)
