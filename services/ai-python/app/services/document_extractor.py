"""Extrae texto plano de los adjuntos para que el buscador pueda mirar dentro.

Alcance v1: PDF, DOCX y texto plano (txt/csv/html). Formatos viejos de Office
(.doc/.xls) y cualquier binario quedan sin texto: `extract_text` devuelve "".
"""

import io
import logging
import re
import zipfile
from html import unescape

log = logging.getLogger(__name__)

MAX_CHARS = 20_000
MAX_UNCOMPRESSED = 5_000_000  # un .docx de 10 MB puede expandir a GB (zip-bomb)
MAX_PDF_PAGES = 50
TEXT_EXTENSIONS = (".txt", ".csv", ".md", ".html", ".htm", ".log", ".json", ".xml")


def extract_text(filename: str, mime_type: str, raw: bytes) -> tuple[str, bool]:
    """Devuelve (texto, truncado). Nunca lanza: un adjunto roto no corta el sync."""
    name = (filename or "").lower()
    mime = (mime_type or "").lower()
    try:
        if name.endswith(".pdf") or mime == "application/pdf":
            text = _pdf(raw)
        elif name.endswith(".docx") or "wordprocessingml" in mime:
            text = _docx(raw)
        elif name.endswith(TEXT_EXTENSIONS) or mime.startswith("text/"):
            text = raw.decode("utf-8", errors="replace")
        else:
            return "", False
    except Exception as exc:  # noqa: BLE001 - cualquier fallo de parseo se ignora
        log.warning("No se pudo extraer texto de %s: %s", filename, exc)
        return "", False

    # PostgreSQL rechaza el byte NUL en columnas de texto: un PDF lo arrastra y aborta el guardado.
    text = re.sub(r"\s+", " ", unescape(text or "").replace("\x00", "")).strip()
    if len(text) > MAX_CHARS:
        return text[:MAX_CHARS], True
    return text, False


def _pdf(raw: bytes) -> str:
    try:
        from pypdf import PdfReader
    except ImportError:  # servicio sin pypdf: se guarda el mail igual, sin texto
        log.warning("pypdf no instalado; adjuntos PDF quedan sin texto")
        return ""
    reader = PdfReader(io.BytesIO(raw))
    # Sin tope, un PDF de miles de páginas cuelga el proceso (contenedor de 512 MB).
    pages = reader.pages[:MAX_PDF_PAGES]
    return "\n".join((page.extract_text() or "") for page in pages)


def _docx(raw: bytes) -> str:
    with zipfile.ZipFile(io.BytesIO(raw)) as zf:
        info = zf.getinfo("word/document.xml")
        if info.file_size > MAX_UNCOMPRESSED:
            log.warning("docx demasiado grande al descomprimir (%s bytes): sin texto", info.file_size)
            return ""
        xml = zf.read("word/document.xml").decode("utf-8", errors="replace")
    return re.sub(r"<[^>]+>", " ", xml)
