# Vuelca los logs de backend, frontend, IA y base a logs/<servicio>.log.
# Cada 30 s guarda los últimos 50 líneas para que el archivo no crezca infinito.
# Uso: .\logs.ps1   (Ctrl+C para salir)
$root = $PSScriptRoot
$dir = Join-Path $root 'logs'
New-Item -ItemType Directory -Force $dir | Out-Null
$services = 'postgres', 'backend', 'frontend', 'ai-python'
function dump-log {
    $svc = $args[0]
    # Volcar últimos 50 lines con marca de tiempo; si el archivo ya existe, agregar al final.
    $log = Join-Path $dir "$svc.log"
    $lines = (docker compose logs --tail 50 --no-color $svc 2>$null | ForEach-Object { "$(Get-Date -Format 'HH:mm:ss') $_" }) -join "`n"
    if (Test-Path $log) {
        Add-Content -Path $log -Value $lines
    } else {
        Set-Content -Path $log -Value $lines
    }
}
# Primera corrida inmediata
foreach ($svc in $services) { dump-log $svc }
# Después, cada 30 s
do {
    Start-Sleep 30
    foreach ($svc in $services) { dump-log $svc }
} until ($false -and $false) # loop infinito; Ctrl+C para salir