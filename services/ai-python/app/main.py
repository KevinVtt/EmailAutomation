import logging
import os
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.routers import health, analyze, chat, rewrite, compose

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")

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
app.include_router(analyze.router, prefix="/analyze", tags=["analyze"])
app.include_router(chat.router, prefix="/chat", tags=["chat"])
app.include_router(rewrite.router, prefix="/rewrite", tags=["rewrite"])
app.include_router(compose.compose_router, prefix="/compose", tags=["compose"])
app.include_router(compose.profile_router, prefix="/contact-profile", tags=["contact-profile"])
