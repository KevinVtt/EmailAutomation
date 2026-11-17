import logging

from fastapi import APIRouter, HTTPException

from app.models.schemas import AnswerRequest, AnswerResponse
from app.services.answer_agent import answer_question

log = logging.getLogger(__name__)
router = APIRouter()


@router.post("", response_model=AnswerResponse)
async def answer(request: AnswerRequest):
    try:
        text = await answer_question(request.question, [f.model_dump() for f in request.fragments])
        return AnswerResponse(response=text)
    except Exception:
        log.exception("Fallo al responder con fragmentos de mails")
        raise HTTPException(status_code=502, detail="El modelo no respondió")
