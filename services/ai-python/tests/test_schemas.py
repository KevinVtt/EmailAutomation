from datetime import datetime

from app.models.schemas import EmailItem


def test_email_item_parses_camel_case():
    item = EmailItem.model_validate(
        {
            "id": "abc-123",
            "provider": "gmail",
            "providerEmailId": "msg-1",
            "threadId": "thread-1",
            "fromAddress": "billing@corp.com",
            "fromName": "Billing",
            "toAddresses": "user@example.com",
            "subject": "Factura vencida",
            "bodyPreview": "Tu factura vencio ayer.",
            "bodyHtml": "<p>Tu factura vencio ayer.</p>",
            "isRead": True,
            "isStarred": False,
            "labels": "IMPORTANT",
            "receivedAt": "2026-09-09T10:00:00Z",
        }
    )

    assert item.provider_email_id == "msg-1"
    assert item.thread_id == "thread-1"
    assert item.from_address == "billing@corp.com"
    assert item.from_name == "Billing"
    assert item.to_addresses == "user@example.com"
    assert item.subject == "Factura vencida"
    assert item.body_preview == "Tu factura vencio ayer."
    assert item.body_html == "<p>Tu factura vencio ayer.</p>"
    assert item.is_read is True
    assert item.is_starred is False
    assert item.labels == "IMPORTANT"
    assert item.received_at == datetime.fromisoformat("2026-09-09T10:00:00+00:00")


def test_email_item_parses_snake_case():
    item = EmailItem.model_validate(
        {
            "id": "abc-123",
            "provider_email_id": "msg-1",
            "from_address": "billing@corp.com",
            "subject": "Factura vencida",
            "body_preview": "Tu factura vencio ayer.",
            "received_at": "2026-09-09T10:00:00Z",
        }
    )

    assert item.provider_email_id == "msg-1"
    assert item.from_address == "billing@corp.com"
    assert item.subject == "Factura vencida"
    assert item.body_preview == "Tu factura vencio ayer."
    assert item.received_at == datetime.fromisoformat("2026-09-09T10:00:00+00:00")


def test_email_item_model_dump_emits_snake_case():
    item = EmailItem(
        provider_email_id="msg-1",
        from_address="billing@corp.com",
        subject="Factura vencida",
        body_preview="Tu factura vencio ayer.",
    )

    dumped = item.model_dump()

    assert "provider_email_id" in dumped
    assert "from_address" in dumped
    assert "body_preview" in dumped
    assert "received_at" in dumped
    assert "providerEmailId" not in dumped
    assert "fromAddress" not in dumped
    assert "bodyPreview" not in dumped