@echo off
title AI Python - Email Filter
cd /d "%~dp0services\ai-python"

REM Carga variables del .env (AI_API_KEY, AI_MODEL, FRONTEND_URL, etc.).
call "%~dp0scripts\load-env.bat"

REM 'python' es el alias roto de la Microsoft Store en este equipo; usamos el
REM launcher oficial 'py' cuando este disponible.
set "PYTHON=py"
where py >nul 2>&1 || set "PYTHON=python"

if not exist "venv\" (
    echo Creando entorno virtual...
    %PYTHON% -m venv venv
    if errorlevel 1 (
        echo ERROR: no se pudo crear el entorno virtual con "%PYTHON%".
        pause
        exit /b 1
    )
)

echo Instalando dependencias...
call venv\Scripts\pip.exe install -r requirements.txt -q
if errorlevel 1 (
    echo ERROR: fallo la instalacion de dependencias.
    pause
    exit /b 1
)

echo Iniciando AI Service en http://localhost:8000
powershell -NoProfile -Command "if (Get-NetTCPConnection -LocalPort 8000 -State Listen -ErrorAction SilentlyContinue) { exit 1 }"
if errorlevel 1 (
    echo ERROR: el puerto 8000 ya esta en uso. Cierra la instancia anterior.
    pause
    exit /b 1
)
call venv\Scripts\activate
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
pause
