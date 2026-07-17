import pytest
from app.services.chat_agent import process_chat_message, summarize_emails


@pytest.mark.asyncio
async def test_process_chat_message_valid_json(httpx_mock):
    httpx_mock.add_response(
        url="https://api.groq.com/openai/v1/chat/completions",
        method="POST",
        json={
            "choices": [{"message": {"content": '{"response":"Mostrando emails importantes.","criteria":{"important":"true"}}'}}]
        },
    )

    result = await process_chat_message("Show important emails")
    assert result["response"] == "Mostrando emails importantes."
    assert result["criteria"]["important"] == "true"
    assert "conversation_id" in result


@pytest.mark.asyncio
async def test_process_chat_message_invalid_json(httpx_mock):
    httpx_mock.add_response(
        url="https://api.groq.com/openai/v1/chat/completions",
        method="POST",
        json={"choices": [{"message": {"content": "Te ayudaré con eso"}}]},
    )

    result = await process_chat_message("Help me")
    assert result["response"] == "Te ayudaré con eso"
    assert result["criteria"] == {}


@pytest.mark.asyncio
async def test_summarize_emails_empty():
    result = await summarize_emails([])
    assert result == "No hay correos para resumir."


@pytest.mark.asyncio
async def test_summarize_emails_with_data(httpx_mock):
    emails = [
        {"subject": "Project Update", "from_address": "alice@example.com",
         "body_preview": "The project is on track."},
    ]

    httpx_mock.add_response(
        url="https://api.groq.com/openai/v1/chat/completions",
        method="POST",
        json={"choices": [{"message": {"content": "Resumen: El proyecto va por buen camino."}}]},
    )

    result = await summarize_emails(emails)
    assert "Resumen" in result
