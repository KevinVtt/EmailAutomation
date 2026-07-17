@echo off
title AI Python - Email Filter
cd /d "%~dp0services\ai-python"

if not exist "venv\" (
    echo Creando entorno virtual...
    python -m venv venv
)
echo Instalando dependencias...
call venv\Scripts\pip.exe install -r requirements.txt -q

echo Iniciando AI Service en http://localhost:8000
call venv\Scripts\activate && uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
pause
