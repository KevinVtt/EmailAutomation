import json

import pytest

from app.services.rewrite_agent import rewrite_email

URL = "https://api.groq.com/openai/v1/chat/completions"


def _mock(httpx_mock, content):
    httpx_mock.add_response(
        url=URL, method="POST",
        json={"choices": [{"message": {"content": content}}]},
    )


@pytest.mark.asyncio
async def test_name_reaches_the_system_prompt(httpx_mock):
    _mock(httpx_mock, "Hola,\n\nJuan Pérez")
    await rewrite_email("Hola,\n\n[Nombre] [Apellido]", user_name="Juan Pérez")

    system = json.loads(httpx_mock.get_request().content)["messages"][0]["content"]
    assert "Juan Pérez" in system


@pytest.mark.asyncio
async def test_leftover_placeholders_are_replaced_with_the_name(httpx_mock):
    _mock(httpx_mock, "Estimados,\n\nQuedo atento.\n\n[Nombre] [Apellido]")
    result = await rewrite_email("...", user_name="Juan Pérez")

    assert "[]" not in result["rewritten"] and "[Nombre]" not in result["rewritten"]
    assert result["rewritten"].rstrip().endswith("Juan Pérez")


@pytest.mark.asyncio
@pytest.mark.parametrize("content", [
    "Saludos,\n[Nombre] [Apellido]",
    "Saludos,\n[Nombre]",
    "Saludos,\n[Apellido]",
    "Saludos,\n[tu nombre]",
    "Saludos,\n{nombre}",
    "Saludos,\n[Nombre Apellido]",
])
async def test_all_known_placeholder_forms_are_replaced(httpx_mock, content):
    _mock(httpx_mock, content)
    result = await rewrite_email("...", user_name="Juan Pérez")
    assert result["rewritten"].rstrip().endswith("Juan Pérez")
    # The pair forms must collapse to one signature, not repeat the name.
    assert result["rewritten"].count("Juan Pérez") == 1


@pytest.mark.asyncio
async def test_no_name_leaves_the_text_untouched(httpx_mock):
    _mock(httpx_mock, "Hola,\n\n[Nombre] [Apellido]")
    result = await rewrite_email("...", user_name="")
    assert result["rewritten"] == "Hola,\n\n[Nombre] [Apellido]"


@pytest.mark.asyncio
async def test_no_placeholder_means_no_injection(httpx_mock):
    _mock(httpx_mock, "Hola, gracias por tu respuesta.")
    result = await rewrite_email("...", user_name="Juan Pérez")
    assert result["rewritten"] == "Hola, gracias por tu respuesta."


@pytest.mark.asyncio
async def test_empty_draft_short_circuits(httpx_mock):
    assert await rewrite_email("", user_name="Juan Pérez") == {"rewritten": "", "original": ""}
