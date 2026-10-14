@echo off
title Frontend React - Email Filter
cd /d "%~dp0services\frontend"

REM Carga variables del .env (FRONTEND_URL, etc.).
call "%~dp0scripts\load-env.bat"

if not exist "node_modules\" (
    echo Instalando dependencias...
    call npm install
    if errorlevel 1 (
        echo ERROR: fallo npm install.
        pause
        exit /b 1
    )
)

echo Iniciando Frontend en http://localhost:3001
powershell -NoProfile -Command "if (Get-NetTCPConnection -LocalPort 3001 -State Listen -ErrorAction SilentlyContinue) { exit 1 }"
if errorlevel 1 (
    echo ERROR: el puerto 3001 ya esta en uso. Cierra la instancia anterior para evitar un frontend duplicado.
    pause
    exit /b 1
)
npm run dev
pause
