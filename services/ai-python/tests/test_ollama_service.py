import pytest
from app.services.ai_service import AIService


@pytest.mark.asyncio
async def test_ai_service_initialization():
    service = AIService()
    assert service.model is not None
    assert service.api_url is not None


@pytest.mark.asyncio
async def test_openai_chat_success(httpx_mock, monkeypatch):
    monkeypatch.setattr("app.config.settings.ai_provider", "openai")
    monkeypatch.setattr("app.config.settings.ai_api_key", "test-key")
    monkeypatch.setattr("app.config.settings.ai_api_url", "https://api.groq.com/openai/v1")
    service = AIService()
    httpx_mock.add_response(
        url="https://api.groq.com/openai/v1/chat/completions",
        method="POST",
        json={"choices": [{"message": {"content": "Groq response"}}]},
    )

    result = await service.chat([{"role": "user", "content": "Hello"}])
    assert result == "Groq response"


@pytest.mark.asyncio
async def test_openai_generate_success(httpx_mock, monkeypatch):
    monkeypatch.setattr("app.config.settings.ai_provider", "openai")
    monkeypatch.setattr("app.config.settings.ai_api_key", "test-key")
    monkeypatch.setattr("app.config.settings.ai_api_url", "https://api.groq.com/openai/v1")
    service = AIService()
    httpx_mock.add_response(
        url="https://api.groq.com/openai/v1/chat/completions",
        method="POST",
        json={"choices": [{"message": {"content": "Generated response"}}]},
    )

    result = await service.generate("Test prompt", system_prompt="Be concise")
    assert result == "Generated response"


@pytest.mark.asyncio
async def test_http_error(httpx_mock, monkeypatch):
    monkeypatch.setattr("app.config.settings.ai_provider", "openai")
    monkeypatch.setattr("app.config.settings.ai_api_key", "test-key")
    service = AIService()
    httpx_mock.add_response(
        url="https://api.groq.com/openai/v1/chat/completions",
        method="POST",
        status_code=500,
        is_reusable=True,
    )

    with pytest.raises(Exception):
        await service.generate("Test prompt")
