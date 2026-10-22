import logging

from fastapi import APIRouter, HTTPException

from app.models.schemas import (
    ComposeRequest,
    ComposeResponse,
    ContactProfileRequest,
    ContactProfileResponse,
)
from app.services.compose_agent import EmptyComposeError, compose_email, contact_profile

logger = logging.getLogger(__name__)

compose_router = APIRouter()
profile_router = APIRouter()


@compose_router.post("", response_model=ComposeResponse)
async def compose(request: ComposeRequest):
    logger.info("=== COMPOSE INICIO === tone=%s, instruction_len=%d", request.tone, len(request.instruction))
    try:
        result = await compose_email(
            instruction=request.instruction,
            contact_name=request.contact_name,
            contact_company=request.contact_company,
            contact_topic=request.contact_topic,
            contact_notes=request.contact_notes,
            recent_subjects=request.recent_subjects,
            subject_hint=request.subject_hint,
            tone=request.tone,
            language=request.language,
            user_name=request.user_name,
        )
        logger.info("=== COMPOSE FIN === subject_len=%d, body_len=%d", len(result["subject"]), len(result["body"]))
        return ComposeResponse(subject=result["subject"], body=result["body"])
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except EmptyComposeError as e:
        logger.warning("=== COMPOSE SIN CUERPO === %s", str(e))
        raise HTTPException(status_code=502, detail="El modelo no devolvió un correo")
    except Exception as e:
        logger.error("=== COMPOSE ERROR === %s", str(e), exc_info=True)
        raise HTTPException(status_code=500, detail="Internal server error")


@profile_router.post("", response_model=ContactProfileResponse)
async def profile(request: ContactProfileRequest):
    logger.info("=== CONTACT PROFILE INICIO === address=%s", request.address)
    try:
        result = await contact_profile(
            address=request.address,
            display_name=request.display_name,
            subjects=request.subjects,
        )
        logger.info("=== CONTACT PROFILE FIN ===")
        return ContactProfileResponse(company=result["company"], topic=result["topic"])
    except Exception as e:
        logger.error("=== CONTACT PROFILE ERROR === %s", str(e), exc_info=True)
        raise HTTPException(status_code=500, detail="Internal server error")
