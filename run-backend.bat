@echo off
title Backend Java - Email Filter
cd /d "%~dp0services\backend-java"

REM Carga variables del .env (JWT_SECRET, GOOGLE_*, POSTGRES_*, etc.).
call "%~dp0scripts\load-env.bat"

REM Maven portable incluido en .tools (no requiere instalacion global).
set "PATH=%~dp0.tools\maven\bin;%PATH%"

if not defined JAVA_HOME (
    echo ADVERTENCIA: JAVA_HOME no esta definido. Maven usara 'java' del PATH si esta disponible.
)

REM Permite a start-local.bat saltar la compilacion cuando ya construyo el jar.
if /i "%~1"=="nobuild" goto :run

echo Compilando Backend...
call mvn package -Dmaven.test.skip=true
if errorlevel 1 (
    echo ERROR: Compilacion fallida
    pause
    exit /b 1
)

:run
echo Iniciando Backend en http://localhost:8080
powershell -NoProfile -Command "if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) { exit 1 }"
if errorlevel 1 (
    echo ERROR: el puerto 8080 ya esta en uso. Cierra la instancia anterior.
    pause
    exit /b 1
)
java -Xmx512m -jar target\email-filter-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
pause
