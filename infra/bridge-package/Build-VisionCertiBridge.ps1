param()

$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$webRoot = Join-Path $workspace 'apps\web'
$nodeExe = (Get-Command node -ErrorAction Stop).Source
$nodeVersion = (& $nodeExe --version).Trim()
if ($nodeVersion -ne 'v22.23.3') { throw "검증된 Node v22.23.3이 필요합니다. 현재 버전: $nodeVersion" }
$forgeRoot = Join-Path $webRoot 'node_modules\korea-forge'
if (-not (Test-Path -LiteralPath (Join-Path $forgeRoot 'lib\index.js'))) { throw 'apps/web에서 npm ci를 먼저 실행해 주세요.' }

$stageRoot = Join-Path $workspace ('work\bridge-package\stage-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path (Join-Path $stageRoot 'node_modules\korea-forge') -Force | Out-Null
Copy-Item -LiteralPath $nodeExe -Destination (Join-Path $stageRoot 'node.exe')
Copy-Item -LiteralPath (Join-Path $webRoot 'local-bridge\server.mjs') -Destination $stageRoot
Copy-Item -LiteralPath (Join-Path $webRoot 'local-bridge\certificates.mjs') -Destination $stageRoot
Copy-Item -LiteralPath (Join-Path $forgeRoot 'package.json') -Destination (Join-Path $stageRoot 'node_modules\korea-forge')
Copy-Item -LiteralPath (Join-Path $forgeRoot 'LICENSE') -Destination (Join-Path $stageRoot 'node_modules\korea-forge')
Copy-Item -LiteralPath (Join-Path $forgeRoot 'lib') -Destination (Join-Path $stageRoot 'node_modules\korea-forge\lib') -Recurse
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Install-VisionCertiBridge.ps1') -Destination $stageRoot
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Start-VisionCertiBridge.ps1') -Destination $stageRoot
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Bridge-Setup.ps1') -Destination $stageRoot
foreach ($name in @('Install-VisionCertiBridge.ps1', 'Start-VisionCertiBridge.ps1', 'Bridge-Setup.ps1')) {
    $path = Join-Path $stageRoot $name
    $contents = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
    [System.IO.File]::WriteAllText($path, $contents, (New-Object System.Text.UTF8Encoding($true)))
}

$nodeLicenseUrl = "https://raw.githubusercontent.com/nodejs/node/$nodeVersion/LICENSE"
Invoke-WebRequest -Uri $nodeLicenseUrl -OutFile (Join-Path $stageRoot 'NODE-LICENSE.txt') -UseBasicParsing
$cmdText = "@echo off`r`npowershell.exe -NoProfile -ExecutionPolicy Bypass -File `"%~dp0Install-VisionCertiBridge.ps1`"`r`nif errorlevel 1 (`r`n  echo Installation failed. Check the message above.`r`n) else (`r`n  echo Installation complete. Return to the certificate page and click retry.`r`n)`r`npause`r`n"
[System.IO.File]::WriteAllText((Join-Path $stageRoot 'Install.cmd'), $cmdText, [System.Text.Encoding]::ASCII)

$hashes = [ordered]@{}
Get-ChildItem -LiteralPath $stageRoot -Recurse -File | Where-Object { $_.Name -ne 'manifest.json' -and $_.Name -ne 'Install.cmd' } | ForEach-Object {
    $relative = $_.FullName.Substring($stageRoot.Length + 1)
    $hashes[$relative] = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
}
@{ version = '0.1.1'; nodeVersion = $nodeVersion; files = $hashes } | ConvertTo-Json -Depth 5 |
    Set-Content -LiteralPath (Join-Path $stageRoot 'manifest.json') -Encoding utf8

$downloads = Join-Path $webRoot 'public\downloads'
New-Item -ItemType Directory -Path $downloads -Force | Out-Null
$archive = Join-Path $downloads 'vision-certi-bridge-win-x64.zip'
Compress-Archive -Path (Join-Path $stageRoot '*') -DestinationPath $archive -Force
$digest = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
Write-Output "archive=$archive"
Write-Output "sha256=$digest"
Write-Output "bytes=$((Get-Item -LiteralPath $archive).Length)"

# The certificate page publishes this checksum next to the download link; keep it in step with the archive.
$envFile = Join-Path $webRoot '.env.production'
if (Test-Path -LiteralPath $envFile) {
    $envText = [System.IO.File]::ReadAllText($envFile)
    $updated = [regex]::Replace($envText, '(?m)^VITE_CERT_INSTALLER_SHA256=.*$', 'VITE_CERT_INSTALLER_SHA256=' + $digest)
    if ($updated -ne $envText) {
        [System.IO.File]::WriteAllText($envFile, $updated, (New-Object System.Text.UTF8Encoding($false)))
        Write-Output "env=$envFile"
    }
}
