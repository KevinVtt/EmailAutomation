@echo off
setlocal EnableExtensions
title Email Filter AI - Setup Base de Datos
cd /d "%~dp0"

REM ---------------------------------------------------------------------------
REM Crea el rol y la base PostgreSQL que usa el backend y aplica el esquema.
REM Es idempotente: se puede ejecutar varias veces sin romper nada.
REM
REM La contrasena del superusuario 'postgres' se toma de:
REM   1) la variable de entorno PGPASSWORD, si esta definida
REM   2) el primer argumento del script, ej:  setup-db.bat mi_password
REM   3) si no, psql la pedira de forma interactiva
REM ---------------------------------------------------------------------------

call "%~dp0scripts\load-env.bat"

REM PostgreSQL no esta en el PATH del sistema: agregamos su carpeta bin.
set "PGBIN=C:\Program Files\PostgreSQL\18\bin"
if exist "%PGBIN%\psql.exe" set "PATH=%PGBIN%;%PATH%"

if not defined PGPASSWORD if not "%~1"=="" set "PGPASSWORD=%~1"

set "PGHOST=localhost"
set "PGPORT=5432"
set "PGSUPERUSER=postgres"

if not defined POSTGRES_DB set "POSTGRES_DB=emailfilter"
if not defined POSTGRES_USER set "POSTGRES_USER=emailfilter_user"
if not defined POSTGRES_PASSWORD set "POSTGRES_PASSWORD=changeme_in_prod"

where psql >nul 2>&1
if errorlevel 1 (
    echo ERROR: no se encontro psql. Verifica que PostgreSQL este instalado en "%PGBIN%".
    pause
    exit /b 1
)

echo ============================================
echo  Setup de base de datos
echo  Host: %PGHOST%:%PGPORT%   DB: %POSTGRES_DB%   Rol: %POSTGRES_USER%
echo ============================================
echo.
echo Nota: se pedira (o usara) la contrasena del superusuario '%PGSUPERUSER%'.
echo.

REM --- 1. Crear el rol si no existe ---
set "ROLE_EXISTS="
for /f "usebackq" %%i in (`psql -U %PGSUPERUSER% -h %PGHOST% -p %PGPORT% -tAc "SELECT 1 FROM pg_roles WHERE rolname='%POSTGRES_USER%'"`) do set "ROLE_EXISTS=%%i"
if "%ROLE_EXISTS%"=="1" (
    echo [1/3] El rol %POSTGRES_USER% ya existe.
) else (
    echo [1/3] Creando rol %POSTGRES_USER%...
    psql -U %PGSUPERUSER% -h %PGHOST% -p %PGPORT% -v ON_ERROR_STOP=1 -c "CREATE ROLE %POSTGRES_USER% LOGIN PASSWORD '%POSTGRES_PASSWORD%'"
    if errorlevel 1 ( echo ERROR al crear el rol. & pause & exit /b 1 )
)

REM --- 2. Crear la base si no existe ---
set "DB_EXISTS="
for /f "usebackq" %%i in (`psql -U %PGSUPERUSER% -h %PGHOST% -p %PGPORT% -tAc "SELECT 1 FROM pg_database WHERE datname='%POSTGRES_DB%'"`) do set "DB_EXISTS=%%i"
if "%DB_EXISTS%"=="1" (
    echo [2/3] La base %POSTGRES_DB% ya existe.
) else (
    echo [2/3] Creando base %POSTGRES_DB%...
    psql -U %PGSUPERUSER% -h %PGHOST% -p %PGPORT% -v ON_ERROR_STOP=1 -c "CREATE DATABASE %POSTGRES_DB% OWNER %POSTGRES_USER%"
    if errorlevel 1 ( echo ERROR al crear la base. & pause & exit /b 1 )
)

REM --- 3. Aplicar el esquema (idempotente) ---
echo [3/3] Aplicando esquema...
psql -U %PGSUPERUSER% -h %PGHOST% -p %PGPORT% -d %POSTGRES_DB% -v ON_ERROR_STOP=1 -f "infrastructure\postgres\init\01-init.sql"
if errorlevel 1 ( echo ERROR en 01-init.sql. & pause & exit /b 1 )
psql -U %PGSUPERUSER% -h %PGHOST% -p %PGPORT% -d %POSTGRES_DB% -v ON_ERROR_STOP=1 -f "infrastructure\postgres\init\02-sync-jobs.sql"
if errorlevel 1 ( echo ERROR en 02-sync-jobs.sql. & pause & exit /b 1 )

echo.
echo ============================================
echo  Listo. Rol y base configurados correctamente.
echo ============================================
pause
