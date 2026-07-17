import os
from pathlib import Path
from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    ai_provider: str = "openai"
    ai_api_key: str = ""
    ai_api_url: str = "https://api.groq.com/openai/v1"
    ai_model: str = "llama3-8b-8192"

    class Config:
        env_file = os.environ.get("ENV_FILE", str(Path(__file__).resolve().parent.parent.parent.parent / ".env"))
        env_prefix = ""
        extra = "ignore"


settings = Settings()
