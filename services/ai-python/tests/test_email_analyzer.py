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
            "choices": [{"message": {"content": '[{"id":"1","label":"work","priority":8,"requires_action":true,"reason":"Work meeting"}]'}}]
        },
    )

    result = await analyze_single_email(email)
    assert result["label"] == "work"
    assert result["priority"] == 8
    assert result["requires_action"] is True


@pytest.mark.asyncio
async def test_analyze_single_email_invalid_json(httpx_mock):
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
        json={"choices": [{"message": {"content": '[{"id":"1","label":"important","priority":10}]'}}]},
    )
    httpx_mock.add_response(
        url="https://api.groq.com/openai/v1/chat/completions",
        method="POST",
        json={"choices": [{"message": {"content": '[{"id":"2","label":"personal","priority":3}]'}}]},
    )

    results = await analyze_emails_batch(emails)
    assert len(results) == 2
