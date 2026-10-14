import asyncio
import logging
import time
from typing import Optional

import httpx

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
                t0 = time.perf_counter()
                response = await self.client.post(url, headers=headers, json=payload)
                t1 = time.perf_counter()
                logger.info("AI RESPONSE status=%s, tiempo=%.0fms", response.status_code, (t1 - t0) * 1000)

                # Retry only on 429, >=500, and network/timeout errors
                if response.status_code == 429 or response.status_code >= 500:
                    if attempt < max_retries - 1:
                        delay = (2 ** attempt) * 1.0
                        # Respect Retry-After if numeric (max 30s)
                        retry_after = response.headers.get("Retry-After")
                        if retry_after:
                            try:
                                ra_val = int(retry_after)
                                if ra_val > 0:
                                    delay = min(ra_val, 30.0)
                            except (ValueError, TypeError):
                                pass
                        logger.warning("AI response status %d, retrying in %.1fs", response.status_code, delay)
                        await asyncio.sleep(delay)
                        continue

                # Do not retry 4xx except 429 (we handled above)
                response.raise_for_status()
                data = response.json()
                # Validate response structure
                if not isinstance(data, dict):
                    raise RuntimeError("Invalid AI response: expected dict")
                choices = data.get("choices")
                if not choices or not isinstance(choices, list) or len(choices) == 0:
                    raise RuntimeError("Invalid AI response: missing or empty 'choices'")
                choice = choices[0]
                if not isinstance(choice, dict):
                    raise RuntimeError("Invalid AI response: malformed choice")
                message = choice.get("message") or {}
                if not isinstance(message, dict) or "content" not in message or message.get("content") is None:
                    raise RuntimeError("Invalid AI response: missing 'content' in message")
                content = message["content"]
                logger.debug("AI CONTENT: %s", content[:200])
                return content
            except (httpx.TransportError, httpx.TimeoutException) as e:
                last_error = e
                if attempt < max_retries - 1:
                    delay = (2 ** attempt) * 1.0
                    logger.warning("AI request failed (attempt %d/%d): %s, retrying in %.1fs", attempt + 1, max_retries, e, delay)
                    await asyncio.sleep(delay)
                else:
                    logger.error("AI request failed after %d attempts: %s", max_retries, e)
            except httpx.HTTPStatusError as e:
                # Do not retry 4xx except 429 (already handled). Raise immediately.
                status = e.response.status_code if e.response else 0
                if status == 429:
                    # Should have been retried above
                    last_error = e
                    if attempt < max_retries - 1:
                        delay = (2 ** attempt) * 1.0
                        await asyncio.sleep(delay)
                        continue
                # Non-retryable 4xx (400/401/403/404 etc)
                logger.error("AI request non-retryable HTTPStatusError: %s", e)
                raise
            except httpx.HTTPError as e:
                # Other HTTP errors: retry only if network-like; keep conservative - but spec says TransportError/TimeoutException explicit
                # For safety, don't retry generic HTTPError except as above
                last_error = e
                if attempt < max_retries - 1:
                    # avoid retrying client errors broadly
                    delay = (2 ** attempt) * 1.0
                    await asyncio.sleep(delay)
                else:
                    logger.error("AI request failed after %d attempts: %s", max_retries, e)

        if last_error is not None:
            # If it was an HTTPStatusError we already raised; others re-raise
            raise last_error
        raise RuntimeError("AI request failed after all retries")

    async def close(self):
        await self.client.aclose()

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        await self.client.aclose()
