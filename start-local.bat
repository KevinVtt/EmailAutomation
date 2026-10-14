@echo off
setlocal EnableExtensions
title Email Filter AI - Local Dev (sin Docker)
cd /d "%~dp0"

echo ============================================
echo  Email Filter AI - Inicio Local (sin Docker)
echo ============================================
echo.

REM --- Entorno ---
call "%~dp0scripts\load-env.bat"
set "PATH=%~dp0.tools\maven\bin;%PATH%"
set "PATH=C:\Program Files\PostgreSQL\18\bin;%PATH%"

if not defined JAVA_HOME (
    echo ERROR: JAVA_HOME no esta definido. Instala un JDK 17+ y define JAVA_HOME.
    pause
    exit /b 1
)

REM --- 1. PostgreSQL ---
echo [1/4] Verificando PostgreSQL...
pg_isready -h localhost -p 5432 >nul 2>&1
if errorlevel 1 (
    echo     PostgreSQL no responde. Intentando iniciar el servicio...
    net start postgresql-x64-18 >nul 2>&1
    timeout /t 3 /nobreak >nul
)
pg_isready -h localhost -p 5432 >nul 2>&1
if errorlevel 1 (
    echo     ERROR: PostgreSQL no esta disponible en localhost:5432.
    echo     Revisa el servicio 'postgresql-x64-18'.
    pause
    exit /b 1
)
echo     OK - PostgreSQL corriendo en localhost:5432
echo.

REM --- 2. AI Python ---
echo [2/4] Preparando AI Python (FastAPI)...
pushd services\ai-python
set "PYTHON=py"
where py >nul 2>&1 || set "PYTHON=python"
if not exist "venv\" (
    echo     Creando entorno virtual...
    %PYTHON% -m venv venv
)
echo     Instalando dependencias...
call venv\Scripts\pip.exe install -r requirements.txt -q
popd
echo     OK
echo.

REM --- 3. Backend Java ---
echo [3/4] Compilando Backend Java...
pushd services\backend-java
call mvn -q package -Dmaven.test.skip=true
if errorlevel 1 (
    echo     ERROR: Compilacion fallida.
    popd
    pause
    exit /b 1
)
popd
echo     OK
echo.

REM --- Evitar instancias duplicadas (un segundo frontend en 3002 genera una
REM     sesión/auth distinta y dispara un sync duplicado que quema la cuota de Gmail) ---
call :require_free_port 8000 "AI Python"
if errorlevel 1 exit /b 1
call :require_free_port 8080 "Backend Java"
if errorlevel 1 exit /b 1
call :require_free_port 3001 "Frontend React"
if errorlevel 1 exit /b 1

REM --- 4. Lanzar servicios ---
echo [4/4] Lanzando servicios en ventanas separadas...
start "AI Python - Email Filter" cmd /k "%~dp0run-ai.bat"
start "Backend Java - Email Filter" cmd /k "%~dp0run-backend.bat nobuild"
start "Frontend React - Email Filter" cmd /k "%~dp0run-frontend.bat"

echo.
echo ============================================
echo  Servicios en marcha:
echo    AI Python  -^> http://localhost:8000
echo    Backend    -^> http://localhost:8080
echo    Frontend   -^> http://localhost:3001
echo.
echo  Abre http://localhost:3001 en el navegador.
echo  La primera vez, ejecuta setup-db.bat para crear la base.
echo ============================================
endlocal
pause
exit /b 0

:require_free_port
powershell -NoProfile -Command "if (Get-NetTCPConnection -LocalPort %1 -State Listen -ErrorAction SilentlyContinue) { exit 1 }"
if errorlevel 1 (
    echo.
    echo ERROR: el puerto %1 ya esta en uso. Probablemente "%2" ya esta corriendo.
    echo Cierra esa instancia antes de relanzar start-local.bat.
    echo Esto evita un segundo frontend y sesiones/syncs duplicados.
    echo.
    pause
    exit /b 1
)
exit /b 0
