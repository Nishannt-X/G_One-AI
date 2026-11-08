# Stops the backend started by scripts\run-backend.ps1.
$runDir = if ($env:G_ONE_RUN_DIR) { $env:G_ONE_RUN_DIR } else { Join-Path $env:TEMP 'g-one-demo' }
$pidFile = Join-Path $runDir 'backend.pid'
if (-not (Test-Path $pidFile)) { Write-Host "No pid file at $pidFile; nothing to stop."; exit 0 }
$id = [int](Get-Content $pidFile)
if (Get-Process -Id $id -ErrorAction SilentlyContinue) { Stop-Process -Id $id; Write-Host "Stopped backend (pid $id)." }
else { Write-Host "Process $id was not running." }
Remove-Item $pidFile -Force
