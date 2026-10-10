param([Parameter(Mandatory = $true)][string[]]$Hostnames)
$ErrorActionPreference = 'Stop'
if (-not (Get-Command mkcert -ErrorAction SilentlyContinue)) { throw 'Install mkcert first; see docs/guides/P2P_LAN.md.' }
$certificateDirectory = Join-Path (Split-Path $PSScriptRoot -Parent) 'data/tls'
New-Item -ItemType Directory -Path $certificateDirectory -Force | Out-Null
# Trust is installed only when this explicit setup command is run by the operator.
& mkcert -install
if ($LASTEXITCODE -ne 0) { throw 'Local certificate authority setup failed.' }
& mkcert -cert-file (Join-Path $certificateDirectory 'lan.pem') -key-file (Join-Path $certificateDirectory 'lan-key.pem') @Hostnames localhost 127.0.0.1 ::1
if ($LASTEXITCODE -ne 0) { throw 'LAN certificate creation failed.' }
Write-Output 'Certificates created in ignored data/tls. Trust the CA certificate on each LAN client; never share the CA private key.'
