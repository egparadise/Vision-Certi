param()

$ErrorActionPreference = 'Stop'
if (-not [Environment]::Is64BitOperatingSystem) { throw 'Windows 64비트 PC가 필요합니다.' }

$sourceRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$manifestPath = Join-Path $sourceRoot 'manifest.json'
if (-not (Test-Path -LiteralPath $manifestPath)) { throw '설치 목록이 없습니다. ZIP을 다시 내려받아 압축을 풀어 주세요.' }
$manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding utf8 | ConvertFrom-Json
foreach ($entry in $manifest.files.PSObject.Properties) {
    $filePath = Join-Path $sourceRoot $entry.Name
    if (-not (Test-Path -LiteralPath $filePath -PathType Leaf)) { throw "설치 파일이 없습니다: $($entry.Name)" }
    $actual = (Get-FileHash -LiteralPath $filePath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $entry.Value) { throw "설치 파일 검증에 실패했습니다: $($entry.Name)" }
}

$listener = Get-NetTCPConnection -LocalAddress 127.0.0.1 -LocalPort 18187 -State Listen -ErrorAction SilentlyContinue
if ($listener) { throw '인증서 프로그램이 이미 실행 중입니다. 종료하거나 PC를 다시 시작한 뒤 설치해 주세요.' }

$installRoot = Join-Path $env:LOCALAPPDATA 'Inviz\VisionCerti\Bridge'
New-Item -ItemType Directory -Path $installRoot -Force | Out-Null
foreach ($entry in $manifest.files.PSObject.Properties) {
    $destination = Join-Path $installRoot $entry.Name
    New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $sourceRoot $entry.Name) -Destination $destination -Force
}
Copy-Item -LiteralPath (Join-Path $sourceRoot 'Start-VisionCertiBridge.ps1') -Destination (Join-Path $installRoot 'Start-VisionCertiBridge.ps1') -Force

$startupFolder = [Environment]::GetFolderPath('Startup')
if (-not $startupFolder) { throw 'Windows 시작프로그램 폴더를 찾을 수 없습니다.' }
New-Item -ItemType Directory -Path $startupFolder -Force | Out-Null
$shortcut = (New-Object -ComObject WScript.Shell).CreateShortcut((Join-Path $startupFolder 'INVIZ Vision-Certi Bridge.lnk'))
$shortcut.TargetPath = (Join-Path $PSHOME 'powershell.exe')
$shortcut.Arguments = '-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "' + (Join-Path $installRoot 'Start-VisionCertiBridge.ps1') + '"'
$shortcut.WorkingDirectory = $installRoot
$shortcut.WindowStyle = 7
$shortcut.Save()

& (Join-Path $installRoot 'Start-VisionCertiBridge.ps1')
Write-Output '설치 완료. 다음 Windows 로그인부터 자동 실행됩니다. 인증서 웹 화면에서 「설치 후 다시 검색」을 누르세요.'
