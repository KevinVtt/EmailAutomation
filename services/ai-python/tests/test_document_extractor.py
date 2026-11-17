import io
import zipfile

from app.services.document_extractor import extract_text


def _docx_bytes(text: str) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        zf.writestr("word/document.xml", f"<w:document><w:t>{text}</w:t></w:document>")
    return buf.getvalue()


def test_extract_text_plain():
    text, truncated = extract_text("notas.txt", "text/plain", b"presupuesto 2026")
    assert text == "presupuesto 2026"
    assert truncated is False


def test_extract_docx_keeps_words():
    text, _ = extract_text("contrato.docx", "", _docx_bytes("Contrato de servicios"))
    assert "Contrato de servicios" in text


def test_extract_unknown_type_returns_empty():
    text, truncated = extract_text("foto.jpg", "image/jpeg", b"\xff\xd8\xff")
    assert text == ""
    assert truncated is False


def test_extract_drops_nul_bytes():
    # PostgreSQL rechaza el byte NUL: si llega, el UPDATE del backend falla y se pierde el texto.
    text, _ = extract_text("raro.txt", "text/plain", b"factura\x00 2026")
    assert "\x00" not in text
    assert "factura" in text


def test_extract_truncates_long_text():
    text, truncated = extract_text("largo.txt", "text/plain", b"a" * 30000)
    assert truncated is True
    assert len(text) == 20000
