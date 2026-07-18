from pydantic import BaseModel
from typing import Optional
from datetime import datetime


class EmailItem(BaseModel):
    id: Optional[str] = None
    provider: Optional[str] = None
    provider_email_id: Optional[str] = None
    thread_id: Optional[str] = None
    from_address: Optional[str] = None
    from_name: Optional[str] = None
    to_addresses: Optional[str] = None
    subject: Optional[str] = None
    body_preview: Optional[str] = None
    body_html: Optional[str] = None
    is_read: bool = False
    is_starred: bool = False
    labels: Optional[str] = None
    received_at: Optional[datetime] = None


class AnalyzeRequest(BaseModel):
    emails: list[EmailItem]


class ChatRequest(BaseModel):
    message: str
    conversation_id: str = ""
    context: list[dict] | None = None


class ChatResponse(BaseModel):
    response: str
    criteria: Optional[dict] = None
    conversation_id: str


class AnalyzeResponse(BaseModel):
    classifications: list[dict]
    summary: Optional[str] = None


class SummarizeRequest(BaseModel):
    emails: list[EmailItem]


class SummarizeResponse(BaseModel):
    summary: str


class RewriteRequest(BaseModel):
    draft: str
    original_subject: str = ""
    original_from: str = ""
    original_body: str = ""
    tone: str = "formal"
    language: str = "auto"
    custom_rules: str = ""


class RewriteResponse(BaseModel):
    rewritten: str
    original: str
