import logging

from fastapi import APIRouter, HTTPException

from app.models.schemas import EmbedRequest, EmbedResponse
from app.services.embedder import embed_texts

log = logging.getLogger(__name__)
router = APIRouter()


@router.post("", response_model=EmbedResponse)
def embed(request: EmbedRequest):
    # def y no async: el modelo local es CPU-bound (igual que /extract).
    if not request.texts or any(len(t) > 8000 for t in request.texts):
        raise HTTPException(status_code=400, detail="texts vacío o demasiado largo")
    try:
        return EmbedResponse(vectors=embed_texts(request.texts))
    except Exception:
        log.exception("Fallo del modelo de embeddings")
        raise HTTPException(status_code=503, detail="Modelo de embeddings no disponible")
