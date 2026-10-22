import pytest
from httpx import AsyncClient, ASGITransport

from app.main import app
from app.config import settings
from app.services.json_utils import extract_json


def _plain_client():
    return AsyncClient(transport=ASGITransport(app=app), base_url="http://test")


@pytest.mark.asyncio
async def test_missing_or_wrong_token_is_rejected():
    async with _plain_client() as c:
        r = await c.post("/analyze", json={"emails": []})
        assert r.status_code == 401
        r = await c.post("/analyze", json={"emails": []}, headers={"X-Internal-Token": "wrong"})
        assert r.status_code == 401


@pytest.mark.asyncio
async def test_empty_server_token_fails_closed(monkeypatch):
    monkeypatch.setattr(settings, "ai_internal_token", "")
    async with _plain_client() as c:
        r = await c.post("/analyze", json={"emails": []}, headers={"X-Internal-Token": ""})
        assert r.status_code == 503


@pytest.mark.asyncio
async def test_correct_token_passes(client):
    r = await client.post("/analyze", json={"emails": []})
    assert r.status_code not in (401, 503)


@pytest.mark.asyncio
async def test_health_stays_open():
    async with _plain_client() as c:
        r = await c.get("/health")
        assert r.status_code == 200


def test_json_object_wins_over_earlier_bracket():
    assert extract_json('see [note] {"a": 1}') == {"a": 1}
