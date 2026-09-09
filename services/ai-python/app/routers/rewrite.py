import logging
from fastapi import APIRouter, HTTPException

from app.models.schemas import RewriteRequest, RewriteResponse
from app.services.rewrite_agent import rewrite_email

logger = logging.getLogger(__name__)

router = APIRouter()


@router.post("", response_model=RewriteResponse)
async def rewrite(request: RewriteRequest):
    logger.info("=== REWRITE INICIO === tone=%s, language=%s, draft_len=%d", request.tone, request.language, len(request.draft))
    try:
        result = await rewrite_email(
            draft=request.draft,
            original_subject=request.original_subject,
            original_from=request.original_from,
            original_body=request.original_body,
            tone=request.tone,
            language=request.language,
            custom_rules=request.custom_rules,
        )
        logger.info("=== REWRITE FIN === rewritten_len=%d", len(result["rewritten"]))
        return RewriteResponse(
            rewritten=result["rewritten"],
            original=result["original"],
        )
    except Exception as e:
        logger.error("=== REWRITE ERROR === %s", str(e), exc_info=True)
        raise HTTPException(status_code=500, detail="Internal server error")
