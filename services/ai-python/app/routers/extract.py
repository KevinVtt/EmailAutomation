import binascii
import base64

from fastapi import APIRouter, HTTPException

from app.models.schemas import ExtractRequest, ExtractResponse
from app.services.document_extractor import extract_text

router = APIRouter()


@router.post("", response_model=ExtractResponse)
def extract(request: ExtractRequest):
    # def y no async: la extracción es CPU-bound y en async bloqueaba el event
    # loop (un PDF congelaba /chat y /health durante segundos).
    try:
        raw = base64.b64decode(request.content_base64 or "", validate=True)
    except (binascii.Error, ValueError):
        raise HTTPException(status_code=400, detail="content_base64 inválido")
    text, truncated = extract_text(request.filename, request.mime_type, raw)
    return ExtractResponse(text=text, truncated=truncated)
