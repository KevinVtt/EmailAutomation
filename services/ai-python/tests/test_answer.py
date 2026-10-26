import asyncio

from app.services import answer_agent


class FakeAI:
    def __init__(self):
        self.messages = None

    async def chat(self, messages):
        self.messages = messages
        return "  Steam te envió la factura. No tengo información de otros mails.  "


def test_answer_question_sends_fragments_as_data_and_strips_reply(monkeypatch):
    fake = FakeAI()
    monkeypatch.setattr(answer_agent.AIService, "get_instance", classmethod(lambda cls: fake))
    fragment = {"subject": "Factura", "sender": "Steam <a@s.com>", "date": "2026-10-01", "content": "Documento.docx"}

    reply = asyncio.run(answer_agent.answer_question("mail de steam con word", [fragment]))

    assert reply == "Steam te envió la factura. No tengo información de otros mails."
    user_msg = fake.messages[1]["content"]
    assert "<<<mail de steam con word>>>" in user_msg and "[MAIL 1] asunto: Factura" in user_msg
