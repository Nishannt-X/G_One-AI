# Checks the backend health endpoint. Usage: scripts\check-health.ps1 [base-url]   (default http://localhost:8080)
param([string]$Base = "http://localhost:$(if ($env:PORT) { $env:PORT } else { '8080' })")
$res = Invoke-RestMethod -Uri "$Base/api/health" -TimeoutSec 10
Write-Host "$Base/api/health -> $($res | ConvertTo-Json -Compress)"
if ($res.ok -ne $true) { exit 1 }
