import logging
import time
from fastapi import APIRouter, HTTPException

from app.models.schemas import ChatRequest, ChatResponse, SummarizeRequest, SummarizeResponse
from app.services.chat_agent import process_chat_message, summarize_emails

logger = logging.getLogger(__name__)

router = APIRouter()


@router.post("", response_model=ChatResponse)
async def chat(request: ChatRequest):
    logger.info("=== PYTHON CHAT INICIO === message=%s, conversation_id=%s", request.message, request.conversation_id)
    try:
        t0 = time.time()
        result = await process_chat_message(request.message, request.conversation_id)
        t1 = time.time()
        logger.info("=== PYTHON CHAT FIN === response=%s, criteria=%s, conversation_id=%s, tiempo=%.0fms",
                     result.get("response","")[:100], result.get("criteria"), result.get("conversation_id",""), (t1-t0)*1000)
        return ChatResponse(
            response=result.get("response", ""),
            criteria=result.get("criteria"),
            conversation_id=result.get("conversation_id", ""),
        )
    except Exception as e:
        logger.error("=== PYTHON CHAT ERROR === %s", str(e), exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/summarize", response_model=SummarizeResponse)
async def summarize(request: SummarizeRequest):
    try:
        emails_data = [e.model_dump() for e in request.emails]
        summary = await summarize_emails(emails_data)
        return SummarizeResponse(summary=summary)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
