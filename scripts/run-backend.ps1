# Builds the backend jar, copies it to a private location and runs the copy with env from .env.local.
# Rebuilding under a running jar hangs it, so the running server never uses backend\target directly.
# Stop with Ctrl+C, or scripts\stop-backend.ps1 from another window. No secrets are printed.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$runDir = if ($env:G_ONE_RUN_DIR) { $env:G_ONE_RUN_DIR } else { Join-Path $env:TEMP 'g-one-demo' }
$envFile = Join-Path $root '.env.local'
if (-not (Test-Path $envFile)) { throw "Missing $envFile. Copy .env.example to .env.local and fill in values." }

Get-Content $envFile | ForEach-Object {
  if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
    [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2].Trim('"'), 'Process')
  }
}

Push-Location (Join-Path $root 'backend')
try {
  & .\mvnw.cmd -q -DskipTests package
  if ($LASTEXITCODE -ne 0) { throw 'Maven build failed.' }
  $jar = Get-ChildItem 'target\g_one-*.jar' | Where-Object { $_.Name -notlike '*.original' } |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
  New-Item -ItemType Directory -Force $runDir | Out-Null
  Copy-Item $jar.FullName (Join-Path $runDir 'g-one.jar') -Force
  $port = if ($env:PORT) { $env:PORT } else { '8080' }
  Write-Host "Running a private copy of the jar: $runDir\g-one.jar (port $port). Ctrl+C to stop."
  # Run from backend\ so the default data dir ./data (SQLite) is backend\data, which git ignores.
  $proc = Start-Process java -ArgumentList '-jar', (Join-Path $runDir 'g-one.jar') -NoNewWindow -PassThru
  Set-Content (Join-Path $runDir 'backend.pid') $proc.Id
  $proc.WaitForExit()
} finally {
  Pop-Location
}
