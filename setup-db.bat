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
    echo [1/4] El rol %POSTGRES_USER% ya existe.
) else (
    echo [1/4] Creando rol %POSTGRES_USER%...
    psql -U %PGSUPERUSER% -h %PGHOST% -p %PGPORT% -v ON_ERROR_STOP=1 -c "CREATE ROLE %POSTGRES_USER% LOGIN PASSWORD '%POSTGRES_PASSWORD%'"
    if errorlevel 1 ( echo ERROR al crear el rol. & pause & exit /b 1 )
)

REM --- 2. Crear la base si no existe ---
set "DB_EXISTS="
for /f "usebackq" %%i in (`psql -U %PGSUPERUSER% -h %PGHOST% -p %PGPORT% -tAc "SELECT 1 FROM pg_database WHERE datname='%POSTGRES_DB%'"`) do set "DB_EXISTS=%%i"
if "%DB_EXISTS%"=="1" (
    echo [2/4] La base %POSTGRES_DB% ya existe.
) else (
    echo [2/4] Creando base %POSTGRES_DB%...
    psql -U %PGSUPERUSER% -h %PGHOST% -p %PGPORT% -v ON_ERROR_STOP=1 -c "CREATE DATABASE %POSTGRES_DB% OWNER %POSTGRES_USER%"
    if errorlevel 1 ( echo ERROR al crear la base. & pause & exit /b 1 )
)

REM --- 3. Aplicar el esquema (idempotente, mismo orden que docker-entrypoint-initdb.d) ---
REM Se aplica como POSTGRES_USER (dueno de la base), no como superusuario: asi las tablas
REM quedan a su nombre y Hibernate (ddl-auto=update) puede alterarlas. Las extensiones
REM pgcrypto y vector son "trusted", por lo que el rol de la app puede crearlas.
echo [3/4] Aplicando esquema...
set "PGPASSWORD=%POSTGRES_PASSWORD%"
psql -U %POSTGRES_USER% -h %PGHOST% -p %PGPORT% -d %POSTGRES_DB% -v ON_ERROR_STOP=1 -f "infrastructure\postgres\init\01-init.sql"
if errorlevel 1 ( echo ERROR en 01-init.sql. & pause & exit /b 1 )
psql -U %POSTGRES_USER% -h %PGHOST% -p %PGPORT% -d %POSTGRES_DB% -v ON_ERROR_STOP=1 -f "infrastructure\postgres\init\02-email-contacts.sql"
if errorlevel 1 ( echo ERROR en 02-email-contacts.sql. & pause & exit /b 1 )
psql -U %POSTGRES_USER% -h %PGHOST% -p %PGPORT% -d %POSTGRES_DB% -v ON_ERROR_STOP=1 -f "infrastructure\postgres\init\02-sync-jobs.sql"
if errorlevel 1 ( echo ERROR en 02-sync-jobs.sql. & pause & exit /b 1 )

REM --- 4. Migraciones sobre bases ya existentes ---
echo [4/4] Aplicando migraciones...
psql -U %POSTGRES_USER% -h %PGHOST% -p %PGPORT% -d %POSTGRES_DB% -v ON_ERROR_STOP=1 -f "infrastructure\postgres\init\03-gmail-history.sql"
if errorlevel 1 ( echo ERROR en 03-gmail-history.sql. & pause & exit /b 1 )
psql -U %POSTGRES_USER% -h %PGHOST% -p %PGPORT% -d %POSTGRES_DB% -v ON_ERROR_STOP=1 -f "infrastructure\postgres\init\03-sent-messages.sql"
if errorlevel 1 ( echo ERROR en 03-sent-messages.sql. & pause & exit /b 1 )
psql -U %POSTGRES_USER% -h %PGHOST% -p %PGPORT% -d %POSTGRES_DB% -v ON_ERROR_STOP=1 -f "infrastructure\postgres\init\04-attachments.sql"
if errorlevel 1 ( echo ERROR en 04-attachments.sql. & pause & exit /b 1 )
psql -U %POSTGRES_USER% -h %PGHOST% -p %PGPORT% -d %POSTGRES_DB% -v ON_ERROR_STOP=1 -f "infrastructure\postgres\init\05-contact-timezone.sql"
if errorlevel 1 ( echo ERROR en 05-contact-timezone.sql. & pause & exit /b 1 )
psql -U %POSTGRES_USER% -h %PGHOST% -p %PGPORT% -d %POSTGRES_DB% -v ON_ERROR_STOP=1 -f "infrastructure\postgres\init\06-email-embeddings.sql"
if errorlevel 1 ( echo ERROR en 06-email-embeddings.sql ^(¿pgvector instalado?^). & pause & exit /b 1 )
psql -U %POSTGRES_USER% -h %PGHOST% -p %PGPORT% -d %POSTGRES_DB% -v ON_ERROR_STOP=1 -f "infrastructure\postgres\init\07-chat-conversations.sql"
if errorlevel 1 ( echo ERROR en 07-chat-conversations.sql. & pause & exit /b 1 )
psql -U %POSTGRES_USER% -h %PGHOST% -p %PGPORT% -d %POSTGRES_DB% -v ON_ERROR_STOP=1 -f "infrastructure\postgres\init\08-contact-manual.sql"
if errorlevel 1 ( echo ERROR en 08-contact-manual.sql. & pause & exit /b 1 )
psql -U %POSTGRES_USER% -h %PGHOST% -p %PGPORT% -d %POSTGRES_DB% -v ON_ERROR_STOP=1 -f "infrastructure\postgres\init\09-email-important.sql"
if errorlevel 1 ( echo ERROR en 09-email-important.sql. & pause & exit /b 1 )

echo.
echo ============================================
echo  Listo. Rol y base configurados correctamente.
echo ============================================
pause
