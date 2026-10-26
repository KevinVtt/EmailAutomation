from functools import lru_cache

from fastembed import TextEmbedding

MODEL = "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2"
DIMENSIONS = 384


@lru_cache(maxsize=1)
def _model() -> TextEmbedding:
    # Carga perezosa: el modelo (~220 MB) se descarga la primera vez que se usa.
    return TextEmbedding(model_name=MODEL)


def embed_texts(texts: list[str]) -> list[list[float]]:
    # Texto vacío se reemplaza por un espacio para no desalinear los vectores.
    return [v.tolist() for v in _model().embed([t or " " for t in texts], batch_size=32)]
