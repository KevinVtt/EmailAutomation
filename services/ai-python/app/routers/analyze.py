from fastapi import APIRouter, HTTPException

from app.models.schemas import AnalyzeRequest, AnalyzeResponse
from app.services.email_analyzer import analyze_emails_batch

router = APIRouter()


@router.post("", response_model=AnalyzeResponse)
async def analyze_emails(request: AnalyzeRequest):
    try:
        classifications = await analyze_emails_batch(request.emails)
        return AnalyzeResponse(
            classifications=classifications,
            summary=f"Classified {len(classifications)} emails.",
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
