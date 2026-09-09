import pytest
from app.main import app


@pytest.mark.asyncio
async def test_health_endpoint(client):
    response = await client.get("/health")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "healthy"


@pytest.mark.asyncio
async def test_analyze_endpoint(client, httpx_mock):
    httpx_mock.add_response(
        url="https://api.groq.com/openai/v1/chat/completions",
        method="POST",
        json={"choices": [{"message": {"content": '[{"id":"1","label":"work","priority":5}]'}}]},
    )

    response = await client.post(
        "/analyze",
        json={
            "emails": [
                {
                    "id": "1",
                    "subject": "Test",
                    "from_address": "user@test.com",
                    "body_preview": "Test body",
                }
            ]
        },
    )
    assert response.status_code == 200
    data = response.json()
    assert "classifications" in data


@pytest.mark.asyncio
async def test_analyze_endpoint_no_emails(client):
    response = await client.post("/analyze", json={"emails": []})
    assert response.status_code == 200
    data = response.json()
    assert len(data["classifications"]) == 0


@pytest.mark.asyncio
async def test_chat_endpoint(client, httpx_mock):
    httpx_mock.add_response(
        url="https://api.groq.com/openai/v1/chat/completions",
        method="POST",
        json={"choices": [{"message": {"content": '{"response":"Hello!","criteria":{}}'}}]},
    )

    response = await client.post(
        "/chat",
        json={"message": "Hello", "conversation_id": ""},
    )
    assert response.status_code == 200
    data = response.json()
    assert "response" in data


@pytest.mark.asyncio
async def test_chat_endpoint_invalid_request(client):
    response = await client.post("/chat", json={})
    assert response.status_code == 422


@pytest.mark.asyncio
async def test_chat_endpoint_internal_error_sanitized(client, monkeypatch):
    async def boom(message, conversation_id=None):
        raise RuntimeError("secret db password: hunter2")

    monkeypatch.setattr("app.routers.chat.process_chat_message", boom)

    response = await client.post("/chat", json={"message": "Hello", "conversation_id": ""})
    assert response.status_code == 500
    data = response.json()
    assert data["detail"] == "Internal server error"
    assert "hunter2" not in str(data)


@pytest.mark.asyncio
async def test_analyze_endpoint_internal_error_sanitized(client, monkeypatch):
    async def boom(emails):
        raise RuntimeError("secret db password: hunter2")

    monkeypatch.setattr("app.routers.analyze.analyze_emails_batch", boom)

    response = await client.post("/analyze", json={"emails": [{"id": "1"}]})
    assert response.status_code == 500
    data = response.json()
    assert data["detail"] == "Internal server error"
    assert "hunter2" not in str(data)


@pytest.mark.asyncio
async def test_summarize_endpoint_internal_error_sanitized(client, monkeypatch):
    async def boom(emails):
        raise RuntimeError("secret db password: hunter2")

    monkeypatch.setattr("app.routers.chat.summarize_emails", boom)

    response = await client.post("/chat/summarize", json={"emails": [{"id": "1"}]})
    assert response.status_code == 500
    data = response.json()
    assert data["detail"] == "Internal server error"
    assert "hunter2" not in str(data)


@pytest.mark.asyncio
async def test_summarize_endpoint(client, httpx_mock):
    httpx_mock.add_response(
        url="https://api.groq.com/openai/v1/chat/completions",
        method="POST",
        json={"choices": [{"message": {"content": "Summary of emails."}}]},
    )

    response = await client.post(
        "/chat/summarize",
        json={"emails": [{"subject": "Test", "from_address": "a@b.com", "body_preview": "Body"}]},
    )
    assert response.status_code == 200
    data = response.json()
    assert "summary" in data
