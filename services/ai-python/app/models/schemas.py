from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel
from typing import Optional
from datetime import datetime


class EmailItem(BaseModel):
    # Java backend sends camelCase (bodyPreview, fromAddress, providerEmailId);
    # populate_by_name keeps snake_case working for direct/curl usage.
    # model_dump() still emits snake_case keys (no serialization_alias).
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

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
    scope: Optional[dict] = None


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
    user_name: str = ""


class RewriteResponse(BaseModel):
    rewritten: str
    original: str


class ComposeRequest(BaseModel):
    instruction: str
    contact_name: str = ""
    contact_company: str = ""
    contact_topic: str = ""
    contact_notes: str = ""
    recent_subjects: list[str] = []
    subject_hint: str = ""
    tone: str = "formal"
    language: str = "auto"
    user_name: str = ""


class ComposeResponse(BaseModel):
    subject: str
    body: str


class ContactProfileRequest(BaseModel):
    address: str
    display_name: str = ""
    subjects: list[str] = []


class ContactProfileResponse(BaseModel):
    company: str = ""
    topic: str = ""
