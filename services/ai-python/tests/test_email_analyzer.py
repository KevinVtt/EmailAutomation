import pytest
from app.services.email_analyzer import analyze_single_email, analyze_emails_batch
from app.models.schemas import EmailItem


@pytest.mark.asyncio
async def test_analyze_single_email_success(httpx_mock):
    email = EmailItem(
        id="1",
        subject="Meeting tomorrow",
        from_address="boss@example.com",
        body_preview="Reminder: team meeting at 10am",
    )

    httpx_mock.add_response(
        url="https://api.groq.com/openai/v1/chat/completions",
        method="POST",
        json={
            "choices": [{"message": {"content": '[{"id":"1","label":"work","priority":8,"requires_action":true,"reason":"Reunión de trabajo","urgency":"medium","urgency_reason":"Requiere atención pero sin fecha límite dura"}]'}}]
        },
    )

    result = await analyze_single_email(email)
    assert result["label"] == "work"
    assert result["priority"] == 8
    assert result["requires_action"] is True
    assert result["urgency"] == "medium"
    assert "urgency_reason" in result


@pytest.mark.asyncio
async def test_analyze_single_email_high_urgency(httpx_mock):
    email = EmailItem(
        id="3",
        subject="URGENTE: vencimiento factura hoy",
        from_address="billing@empresa.com",
        body_preview="La factura #1234 vence hoy, pago urgente requerido ASAP",
    )

    httpx_mock.add_response(
        url="https://api.groq.com/openai/v1/chat/completions",
        method="POST",
        json={
            "choices": [{"message": {"content": '[{"id":"3","label":"finance","priority":9,"requires_action":true,"reason":"Factura con vencimiento inmediato","urgency":"high","urgency_reason":"Vencimiento de factura hoy — pago urgente"}]'}}]
        },
    )

    result = await analyze_single_email(email)
    assert result["urgency"] == "high"
    assert result["priority"] >= 8
    assert result["label"] == "finance"


@pytest.mark.asyncio
async def test_analyze_single_email_low_urgency(httpx_mock):
    email = EmailItem(
        id="4",
        subject="Newsletter semanal",
        from_address="news@techblog.com",
        body_preview="Resumen de tecnología de la semana",
    )

    httpx_mock.add_response(
        url="https://api.groq.com/openai/v1/chat/completions",
        method="POST",
        json={
            "choices": [{"message": {"content": '[{"id":"4","label":"updates","priority":2,"requires_action":false,"reason":"Newsletter informativo","urgency":"low","urgency_reason":"Contenido informativo, sin acción requerida"}]'}}]
        },
    )

    result = await analyze_single_email(email)
    assert result["urgency"] == "low"
    assert result["requires_action"] is False


@pytest.mark.asyncio
async def test_analyze_single_email_invalid_json_fallback_urgency(httpx_mock):
    email = EmailItem(
        id="2",
        subject="Promo",
        from_address="ads@spam.com",
        body_preview="Buy now!",
    )

    httpx_mock.add_response(
        url="https://api.groq.com/openai/v1/chat/completions",
        method="POST",
        json={"choices": [{"message": {"content": "Not valid JSON"}}]},
    )

    result = await analyze_single_email(email)
    assert result["label"] == "other"
    assert result["priority"] == 5
    assert result["urgency"] == "low"
    assert result["urgency_reason"] == "Clasificación fallida — respuesta IA no válida"


@pytest.mark.asyncio
async def test_analyze_single_email_partial_json_no_urgency_fallback(httpx_mock):
    """When AI returns valid JSON but without urgency fields, the result still passes through."""
    email = EmailItem(
        id="5",
        subject="Quick update",
        from_address="ci@team.com",
        body_preview="Build passed on main",
    )

    httpx_mock.add_response(
        url="https://api.groq.com/openai/v1/chat/completions",
        method="POST",
        json={
            "choices": [{"message": {"content": '[{"id":"5","label":"updates","priority":3,"requires_action":false,"reason":"Build notification"}]'}}]
        },
    )

    result = await analyze_single_email(email)
    # When AI returns valid JSON without urgency fields, the result passes through as-is
    # (backward compat: caller should handle missing urgency gracefully)
    assert result["label"] == "updates"
    assert result["priority"] == 3


@pytest.mark.asyncio
async def test_analyze_emails_batch_empty():
    results = await analyze_emails_batch([])
    assert results == []


@pytest.mark.asyncio
async def test_analyze_emails_batch_multiple(httpx_mock):
    emails = [
        EmailItem(id="1", subject="Urgent", from_address="a@b.com", body_preview="Urgent matter"),
        EmailItem(id="2", subject="Hello", from_address="c@d.com", body_preview="Just saying hi"),
    ]

    httpx_mock.add_response(
        url="https://api.groq.com/openai/v1/chat/completions",
        method="POST",
        json={"choices": [{"message": {"content": '[{"id":"1","label":"important","priority":10,"requires_action":true,"reason":"Asunto urgente","urgency":"high","urgency_reason":"Requiere atención inmediata"}]'}}]},
    )
    httpx_mock.add_response(
        url="https://api.groq.com/openai/v1/chat/completions",
        method="POST",
        json={"choices": [{"message": {"content": '[{"id":"2","label":"personal","priority":3,"requires_action":false,"reason":"Saludo casual","urgency":"low","urgency_reason":"Informativo"}]'}}]},
    )

    results = await analyze_emails_batch(emails)
    assert len(results) == 2
    assert results[0]["urgency"] == "high"
    assert results[1]["urgency"] == "low"
