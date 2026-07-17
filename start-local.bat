@echo off
title Email Filter AI - Local Dev
cd /d "%~dp0"

echo ============================================
echo  Email Filter AI - Inicio Local (sin Docker)
echo ============================================
echo.

:: --- Verificar PostgreSQL ---
echo [1/4] Verificando PostgreSQL...
pg_isready -h localhost -p 5432 >nul 2>&1
if %errorlevel% neq 0 (
    echo     PostgreSQL no esta corriendo. Intentando iniciar servicio...
    net start postgresql-x64-18 >nul 2>&1
    if %errorlevel% neq 0 (
        echo     ERROR: No se pudo iniciar PostgreSQL.
        echo     Asegurate de que PostgreSQL 18 este instalado y el servicio este configurado.
        pause
        exit /b 1
    )
    timeout /t 3 /nobreak >nul
)
echo     OK - PostgreSQL esta corriendo en localhost:5432
echo.

:: --- AI Python ---
echo [2/4] Preparando AI Python (FastAPI)...
cd services\ai-python
if not exist "venv\" (
    echo     Creando entorno virtual...
    python -m venv venv
)
echo     Instalando dependencias...
call venv\Scripts\pip.exe install -r requirements.txt -q
echo     OK - AI Python listo
echo.

:: --- Backend Java ---
echo [3/4] Compilando Backend Java (Spring Boot)...
cd /d "%~dp0services\backend-java"
call mvn package -Dmaven.test.skip=true -q
if %errorlevel% neq 0 (
    echo     ERROR: Fallo la compilacion del backend.
    pause
    exit /b 1
)
echo     OK - Backend compilado
echo.

:: --- Frontend ---
echo [4/4] Instalando Frontend (React + Vite)...
cd /d "%~dp0services\frontend"
call npm install --silent
echo     OK - Frontend listo
echo.

:: --- Iniciar servicios ---
cd /d "%~dp0"
echo ============================================
echo  Iniciando servicios en ventanas separadas...
echo ============================================
echo.
echo  Backend Java  -> http://localhost:8080
echo  AI Python     -> http://localhost:8000
echo  Frontend      -> http://localhost:5173
echo.

:: AI Python
start "AI Python" cmd /c "cd /d "%~dp0services\ai-python" && call venv\Scripts\activate && uvicorn app.main:app --reload --host 0.0.0.0 --port 8000"

:: Backend Java
start "Backend Java" cmd /c "cd /d "%~dp0services\backend-java" && java -jar target\email-filter-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev"

:: Frontend
start "Frontend" cmd /c "cd /d "%~dp0services\frontend" && npm run dev"

echo.
echo  Todas las ventanas fueron abiertas.
echo  Cerra esta ventana cuando quieras detener todo.
echo.
echo  Para detener los servicios, cerra cada ventana individualmente.
echo.

pause
