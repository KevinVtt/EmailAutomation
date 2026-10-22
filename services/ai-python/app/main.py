import hmac
import logging
import os
from fastapi import Depends, FastAPI, Header, HTTPException
from fastapi.middleware.cors import CORSMiddleware

from app.config import settings
from app.routers import health, analyze, chat, rewrite, compose


logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")


def require_internal_token(x_internal_token: str | None = Header(default=None)):
    expected = settings.ai_internal_token
    if not expected:
        raise HTTPException(status_code=503, detail="AI_INTERNAL_TOKEN no configurado")
    if not x_internal_token or not hmac.compare_digest(x_internal_token.encode(), expected.encode()):
        raise HTTPException(status_code=401, detail="Unauthorized")


app = FastAPI(
    title="Email Filter AI Service",
    description="AI-powered email analysis and chat (OpenAI-compatible provider, Groq)",
    version="0.1.0",
)

frontend_url = os.environ.get("FRONTEND_URL", "http://localhost:3001")
app.add_middleware(
    CORSMiddleware,
    allow_origins=[frontend_url],
    allow_credentials=True,
    allow_methods=["GET", "POST"],
    allow_headers=["Authorization", "Content-Type", "Accept"],
)

app.include_router(health.router, tags=["health"])
auth = [Depends(require_internal_token)]
app.include_router(analyze.router, prefix="/analyze", tags=["analyze"], dependencies=auth)
app.include_router(chat.router, prefix="/chat", tags=["chat"], dependencies=auth)
app.include_router(rewrite.router, prefix="/rewrite", tags=["rewrite"], dependencies=auth)
app.include_router(compose.compose_router, prefix="/compose", tags=["compose"], dependencies=auth)
app.include_router(compose.profile_router, prefix="/contact-profile", tags=["contact-profile"], dependencies=auth)
