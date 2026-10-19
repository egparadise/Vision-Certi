param()
$ErrorActionPreference='Stop'
# Run in a terminal kept open. Configure separately after the health endpoint is ready.
python (Join-Path $PSScriptRoot 'server_local.py')