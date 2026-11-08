# OPTIONAL (stretch / sharing only): expose the backend on your ngrok static domain. The browser demo does not need it.
# Usage: scripts\tunnel.ps1 <your-static-domain>      Stop with Ctrl+C.
param([Parameter(Mandatory = $true)][string]$Domain)
$port = if ($env:PORT) { $env:PORT } else { '8080' }
Write-Host "Tunnel: https://$Domain -> http://localhost:$port. Ctrl+C to stop."
ngrok http --url=$Domain $port
