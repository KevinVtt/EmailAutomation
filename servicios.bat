@echo off
setlocal EnableExtensions
title Email Filter AI - Control de servicios
cd /d "%~dp0"

REM ---------------------------------------------------------------------------
REM Botonera de los 3 servicios locales (backend, AI, frontend).
REM Usa los run-*.bat existentes; 'start-local.bat' sigue siendo el de la
REM primera vez (verifica PostgreSQL y compila el backend).
REM
REM Tambien acepta un argumento para usarlo sin el menu:
REM    servicios.bat start | stop | restart | status
REM ---------------------------------------------------------------------------

if /i "%~1"=="start"   (call :start_services & exit /b 0)
if /i "%~1"=="stop"    (call :stop_services  & exit /b 0)
if /i "%~1"=="restart" (call :restart_services & exit /b 0)
if /i "%~1"=="status"  (call :status & exit /b 0)

:menu
cls
echo ==================================================
echo   Email Filter AI - Control de servicios
echo ==================================================
call :status
echo.
echo   Abri http://localhost:3001 en el navegador.
echo.
echo   [1] Iniciar servicios
echo   [2] Parar servicios
echo   [3] Reiniciar servicios
echo   [4] Ver estado
echo   [5] Salir (los servicios siguen corriendo)
echo   [6] Salir y parar todo
echo.
choice /C 123456 /N /M "Opcion: "
if errorlevel 6 goto :salir_parar
if errorlevel 5 goto :fin
if errorlevel 4 goto :menu
if errorlevel 3 call :restart_services & timeout /t 4 /nobreak >nul & goto :menu
if errorlevel 2 call :stop_services    & timeout /t 2 /nobreak >nul & goto :menu
call :start_services & timeout /t 4 /nobreak >nul
goto :menu

:fin
endlocal
exit /b 0

:salir_parar
call :stop_services
timeout /t 2 /nobreak >nul
endlocal
exit /b 0

:start_services
echo.
echo Iniciando servicios...
if not exist "services\backend-java\target\email-filter-backend-0.0.1-SNAPSHOT.jar" (
    echo   AVISO: no existe el jar del backend.
    echo   Corre start-local.bat una vez para compilarlo y despues volve a esta botonera.
)
REM Cada run-*.bat ya se niega a arrancar si su puerto esta ocupado; aca lo
REM chequeamos antes para no pagar el pip install / el build al pedo.
call :isportfree 8000
if errorlevel 1 (echo   AI Python: ya estaba corriendo) else start "AI Python - Email Filter" cmd /k "%~dp0run-ai.bat"
call :isportfree 8080
if errorlevel 1 (echo   Backend Java: ya estaba corriendo) else start "Backend Java - Email Filter" cmd /k "%~dp0run-backend.bat nobuild"
call :isportfree 3001
if errorlevel 1 (echo   Frontend React: ya estaba corriendo) else start "Frontend React - Email Filter" cmd /k "%~dp0run-frontend.bat"
exit /b 0

:stop_services
echo.
echo Parando servicios...
REM Se matan por marca en la linea de comandos, no por PID guardado: asi
REM tambien funciona si los arrancaste a mano o desde otra consola.
REM Ojo con las comillas: el wildcard se arma con '*'+$_+'*' porque el
REM payload va dentro de comillas dobles y '*'$_'*' seria un patron literal.
REM Solo java/node/python/cmd (servicios y lanzadores), nunca powershell.exe.
powershell -NoProfile -Command "$m='email-filter-backend','run-backend.bat','uvicorn','run-ai.bat','vite','run-frontend.bat'; $n='java.exe','node.exe','python.exe','cmd.exe'; Get-CimInstance Win32_Process | Where-Object { $c=$_.CommandLine; $n -contains $_.Name -and $c -and ($m | Where-Object { $c -like ('*'+$_+'*') }) } | ForEach-Object { taskkill /F /T /PID $_.ProcessId }"
exit /b 0

:restart_services
call :stop_services
timeout /t 3 /nobreak >nul
call :start_services
exit /b 0

:isportfree
powershell -NoProfile -Command "if (Get-NetTCPConnection -LocalPort %1 -State Listen -ErrorAction SilentlyContinue) { exit 1 }"
if errorlevel 1 exit /b 1
exit /b 0

:status
powershell -NoProfile -Command "foreach ($s in @('8000:AI Python','8080:Backend Java','3001:Frontend React')) { $p,$n = $s.Split(':'); $c = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue; if ($c) { Write-Host ('  ' + $n + ': ARRIBA (PID ' + $c.OwningProcess + ')') } else { Write-Host ('  ' + $n + ': parado') } }"
exit /b 0
