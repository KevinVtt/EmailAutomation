from datetime import datetime

import pytest

from app.models.schemas import EmailItem
from app.services.chat_agent import resolve_date_token, sanitize_criteria
from app.services.email_analyzer import analyze_single_email
from app.services.json_utils import extract_json


# ── extract_json ───────────────────────────────────────────────────────────


def test_extract_json_plain():
    assert extract_json('{"a": 1}') == {"a": 1}


def test_extract_json_with_json_fence():
    assert extract_json('```json\n{"a": 1}\n```') == {"a": 1}


def test_extract_json_with_plain_fence():
    assert extract_json('```\n{"a": 1}\n```') == {"a": 1}


def test_extract_json_with_think_block():
    text = "<think>let me reason about this</think>\n{\"a\": 1}"
    assert extract_json(text) == {"a": 1}


def test_extract_json_with_surrounding_text():
    text = 'Sure! Here is the result: {"a": 1} thanks for asking.'
    assert extract_json(text) == {"a": 1}


def test_extract_json_array():
    assert extract_json("Here: [1, 2, 3] done") == [1, 2, 3]


def test_extract_json_nested_braces():
    text = 'prefix {"a": {"b": 2}} suffix'
    assert extract_json(text) == {"a": {"b": 2}}


def test_extract_json_invalid_raises_value_error():
    with pytest.raises(ValueError):
        extract_json("no json here at all")


# ── sanitize_criteria ──────────────────────────────────────────────────────


def test_sanitize_criteria_whitelist_and_truncate():
    long_value = "x" * 500
    raw = {
        "fromAddress": "  a@b.com  ",
        "subjectContains": long_value,
        "bodyContains": "hello",
        "isRead": True,
        "isStarred": "FALSE",
        "important": False,
        "label": "SPAM",
        "size": 250,
        "dateFrom": "2026-01-01T00:00:00Z",
        "dateTo": "2026-01-02T00:00:00Z",
        "evilKey": "drop-me",
    }
    out = sanitize_criteria(raw)
    assert out["fromAddress"] == "a@b.com"
    assert len(out["subjectContains"]) == 200
    assert out["isRead"] == "true"
    assert out["isStarred"] == "false"
    assert out["important"] == "false"
    assert out["label"] == "SPAM"
    assert out["size"] == 100
    assert "evilKey" not in out


def test_sanitize_criteria_invalid_label_dropped():
    out = sanitize_criteria({"label": "NOT_A_LABEL"})
    assert "label" not in out


def test_sanitize_criteria_invalid_size_and_strings():
    out = sanitize_criteria({"size": "not-a-number", "fromAddress": "", "isRead": "maybe"})
    assert "size" not in out
    assert "fromAddress" not in out
    assert "isRead" not in out


def test_sanitize_criteria_non_dict():
    assert sanitize_criteria("nope") == {}


# ── resolve_date_token ─────────────────────────────────────────────────────


def test_resolve_date_token_today_exclusive_upper_bound():
    now = datetime(2026, 10, 14, 12, 0, 0)
    out = resolve_date_token("today", now)
    # Buenos Aires is UTC-3, so local 00:00 == 03:00Z; upper bound is next day
    assert out["dateFrom"] == "2026-10-14T03:00:00Z"
    assert out["dateTo"] == "2026-10-15T03:00:00Z"


def test_resolve_date_token_yesterday():
    now = datetime(2026, 10, 14, 12, 0, 0)
    out = resolve_date_token("yesterday", now)
    assert out["dateFrom"] == "2026-10-13T03:00:00Z"
    assert out["dateTo"] == "2026-10-14T03:00:00Z"


def test_resolve_date_token_this_week_starts_monday():
    # 2026-10-14 is a Wednesday; week starts Monday 2026-10-12
    now = datetime(2026, 10, 14, 12, 0, 0)
    out = resolve_date_token("this_week", now)
    assert out["dateFrom"] == "2026-10-12T03:00:00Z"
    assert out["dateTo"] == "2026-10-15T03:00:00Z"


def test_resolve_date_token_last_month():
    # March 15 -> last month is February (28 days in 2026)
    now = datetime(2026, 3, 15, 12, 0, 0)
    out = resolve_date_token("last_month", now)
    assert out["dateFrom"] == "2026-02-01T03:00:00Z"
    assert out["dateTo"] == "2026-03-01T03:00:00Z"


def test_resolve_date_token_aware_now_converted():
    from datetime import timezone

    now = datetime(2026, 10, 14, 12, 0, 0, tzinfo=timezone.utc)
    out = resolve_date_token("today", now)
    # 12:00Z == 09:00 local on the same day, so "today" is still 2026-10-14 local
    assert out["dateFrom"] == "2026-10-14T03:00:00Z"


def test_resolve_date_token_unknown_returns_empty():
    assert resolve_date_token("next_century", datetime(2026, 10, 14, 12, 0, 0)) == {}


# ── analyze_single_email normalization ─────────────────────────────────────


@pytest.mark.asyncio
async def test_analyze_single_email_normalizes_malformed_values(httpx_mock):
    email = EmailItem(
        id="fallback-id",
        provider_email_id="provider-42",
        subject="Something",
        from_address="a@b.com",
        body_preview="hi",
    )
    httpx_mock.add_response(
        url="https://api.groq.com/openai/v1/chat/completions",
        method="POST",
        json={
            "choices": [
                {
                    "message": {
                        "content": '[{"id":"model-id","label":"unknown-cat","priority":99,"requires_action":"yes","reason":"x"}]'
                    }
                }
            ]
        },
    )

    result = await analyze_single_email(email)
    assert result["id"] == "provider-42"  # overwritten, not model-id
    assert result["label"] == "other"  # invalid label normalized
    assert result["priority"] == 10  # clamped
    assert result["requires_action"] is True
    assert result["urgency"] == "high"  # computed from priority >= 8


@pytest.mark.asyncio
async def test_analyze_single_email_single_dict_response(httpx_mock):
    email = EmailItem(id="1", subject="Hi", from_address="a@b.com", body_preview="yo")
    httpx_mock.add_response(
        url="https://api.groq.com/openai/v1/chat/completions",
        method="POST",
        json={
            "choices": [
                {"message": {"content": '{"id":"1","label":"personal","priority":2,"requires_action":false,"reason":"r"}'}}
            ]
        },
    )

    result = await analyze_single_email(email)
    assert result["label"] == "personal"
    assert result["priority"] == 2
    assert result["urgency"] == "low"  # <=3 and no action


@pytest.mark.asyncio
async def test_analyze_single_email_generation_error_returns_fallback(httpx_mock):
    email = EmailItem(id="1", subject="Hi", from_address="a@b.com", body_preview="yo")
    httpx_mock.add_response(
        url="https://api.groq.com/openai/v1/chat/completions",
        method="POST",
        status_code=400,
        json={"error": "bad request"},
    )

    result = await analyze_single_email(email)
    assert result["label"] == "other"
    assert result["priority"] == 5
    assert result["urgency"] == "low"
    assert result["id"] == "1"
