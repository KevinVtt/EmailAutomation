@echo off
title Backend Java - Email Filter
cd /d "%~dp0services\backend-java"

echo Compilando Backend...
call mvn package -Dmaven.test.skip=true
if %errorlevel% neq 0 (
    echo ERROR: Compilacion fallida
    pause
    exit /b 1
)

echo Iniciando Backend en http://localhost:8080
java -jar target\email-filter-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
pause
