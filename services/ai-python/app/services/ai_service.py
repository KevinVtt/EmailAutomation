import httpx
import json
import logging
import time
from typing import Optional

from app.config import settings

logger = logging.getLogger(__name__)

_instance: Optional["AIService"] = None


class AIService:
    def __init__(self):
        self.api_key = settings.ai_api_key
        self.api_url = settings.ai_api_url.rstrip("/")
        self.model = settings.ai_model
        self.client = httpx.AsyncClient(timeout=120.0)

    @classmethod
    def get_instance(cls) -> "AIService":
        global _instance
        if _instance is None:
            _instance = cls()
        return _instance

    async def generate(self, prompt: str, system_prompt: Optional[str] = None) -> str:
        msgs = []
        if system_prompt:
            msgs.append({"role": "system", "content": system_prompt})
        msgs.append({"role": "user", "content": prompt})
        return await self._call(msgs)

    async def chat(self, messages: list[dict]) -> str:
        return await self._call(messages)

    async def _call(self, messages: list[dict]) -> str:
        headers = {
            "Content-Type": "application/json",
        }
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"

        payload = {
            "model": self.model,
            "messages": messages,
            "temperature": 0.3,
            "stream": False,
        }

        url = f"{self.api_url}/chat/completions"
        max_retries = 3
        last_error = None

        for attempt in range(max_retries):
            logger.info("AI REQUEST: url=%s, model=%s, messages_count=%s, attempt=%d", url, self.model, len(messages), attempt + 1)
            try:
                t0 = time.time()
                response = await self.client.post(url, headers=headers, json=payload)
                t1 = time.time()
                logger.info("AI RESPONSE status=%s, tiempo=%.0fms", response.status_code, (t1-t0)*1000)

                if response.status_code == 429 or response.status_code >= 500:
                    if attempt < max_retries - 1:
                        delay = (2 ** attempt) * 1.0
                        logger.warning("AI response status %d, retrying in %.1fs", response.status_code, delay)
                        time.sleep(delay)
                        continue

                response.raise_for_status()
                data = response.json()
                content = data["choices"][0]["message"]["content"]
                logger.info("AI CONTENT: %s", content[:200])
                return content
            except httpx.HTTPError as e:
                last_error = e
                if attempt < max_retries - 1:
                    delay = (2 ** attempt) * 1.0
                    logger.warning("AI request failed (attempt %d/%d): %s, retrying in %.1fs", attempt + 1, max_retries, e, delay)
                    time.sleep(delay)
                else:
                    logger.error("AI request failed after %d attempts: %s", max_retries, e)

        raise last_error or RuntimeError("AI request failed after all retries")

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        await self.client.aclose()
