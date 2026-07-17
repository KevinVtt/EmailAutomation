@echo off
title Frontend React - Email Filter
cd /d "%~dp0services\frontend"

echo Instalando dependencias...
call npm install

echo Iniciando Frontend en http://localhost:5173
npm run dev
pause
