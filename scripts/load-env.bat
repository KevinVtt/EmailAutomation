@echo off
REM ---------------------------------------------------------------------------
REM Carga el archivo .env de la raiz del repo en el entorno del proceso actual.
REM Pensado para usarse con:  call "%~dp0load-env.bat"
REM Los valores quedan disponibles como variables de entorno reales, que es lo
REM que necesita Spring Boot (placeholders ${...} y binding relajado).
REM ---------------------------------------------------------------------------

set "ENV_FILE=%~dp0..\.env"

if not exist "%ENV_FILE%" (
    echo [load-env] ADVERTENCIA: no se encontro "%ENV_FILE%"
    exit /b 0
)

REM eol=# salta comentarios; tokens=1,* conserva el resto de la linea como valor
REM (importante para secretos con '=' como el padding de base64).
for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%ENV_FILE%") do (
    if not "%%~A"=="" set "%%~A=%%~B"
)

exit /b 0
