from pydantic import BaseModel, ConfigDict, Field
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
    emails: list[EmailItem] = Field(max_length=50)


class ChatRequest(BaseModel):
    message: str = Field(max_length=2000)
    conversation_id: str = Field(default="", max_length=100)
    context: list[dict] | None = Field(default=None, max_length=50)
    scope: Optional[dict] = None


class ChatResponse(BaseModel):
    response: str
    criteria: Optional[dict] = None
    conversation_id: str


class AnalyzeResponse(BaseModel):
    classifications: list[dict]
    summary: Optional[str] = None


class SummarizeRequest(BaseModel):
    emails: list[EmailItem] = Field(max_length=50)


class SummarizeResponse(BaseModel):
    summary: str


class RewriteRequest(BaseModel):
    draft: str = Field(max_length=4000)
    original_subject: str = Field(default="", max_length=300)
    original_from: str = Field(default="", max_length=300)
    original_body: str = Field(default="", max_length=20000)
    tone: str = Field(default="formal", max_length=20)
    language: str = Field(default="auto", max_length=20)
    custom_rules: str = Field(default="", max_length=2000)
    user_name: str = Field(default="", max_length=300)


class RewriteResponse(BaseModel):
    rewritten: str
    original: str


class ComposeRequest(BaseModel):
    instruction: str = Field(max_length=1000)
    contact_name: str = Field(default="", max_length=300)
    contact_company: str = Field(default="", max_length=300)
    contact_topic: str = Field(default="", max_length=300)
    contact_notes: str = Field(default="", max_length=2000)
    recent_subjects: list[str] = Field(default=[], max_length=50)
    subject_hint: str = Field(default="", max_length=300)
    tone: str = Field(default="formal", max_length=20)
    language: str = Field(default="auto", max_length=20)
    user_name: str = Field(default="", max_length=300)


class ComposeResponse(BaseModel):
    subject: str
    body: str


class ContactProfileRequest(BaseModel):
    address: str = Field(max_length=320)
    display_name: str = Field(default="", max_length=300)
    subjects: list[str] = Field(default=[], max_length=50)


class ContactProfileResponse(BaseModel):
    company: str = ""
    topic: str = ""
