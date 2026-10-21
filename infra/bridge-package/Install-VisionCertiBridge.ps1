param()

$ErrorActionPreference = 'Stop'
if (-not [Environment]::Is64BitOperatingSystem) { throw 'Windows 64비트 PC가 필요합니다.' }

$sourceRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$installRoot = [IO.Path]::GetFullPath((Join-Path $env:LOCALAPPDATA 'Inviz\VisionCerti\Bridge'))
if ([string]::Equals($sourceRoot, $installRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw '다운로드한 ZIP을 별도 폴더에 압축 해제한 뒤 Install.cmd를 실행해 주세요.'
}
$manifestPath = Join-Path $sourceRoot 'manifest.json'
if (-not (Test-Path -LiteralPath $manifestPath)) { throw '설치 목록이 없습니다. ZIP을 다시 내려받아 압축을 풀어 주세요.' }
$manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding utf8 | ConvertFrom-Json
foreach ($entry in $manifest.files.PSObject.Properties) {
    $filePath = [IO.Path]::GetFullPath((Join-Path $sourceRoot $entry.Name))
    $destination = [IO.Path]::GetFullPath((Join-Path $installRoot $entry.Name))
    if (-not $filePath.StartsWith($sourceRoot + '\', [StringComparison]::OrdinalIgnoreCase) -or
        -not $destination.StartsWith($installRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw '설치 목록에 잘못된 파일 경로가 있습니다. ZIP을 다시 내려받아 주세요.'
    }
    if (-not (Test-Path -LiteralPath $filePath -PathType Leaf)) { throw "설치 파일이 없습니다: $($entry.Name)" }
    $actual = (Get-FileHash -LiteralPath $filePath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $entry.Value) { throw "설치 파일 검증에 실패했습니다: $($entry.Name)" }
}

foreach ($required in @('node.exe', 'server.mjs', 'Bridge-Setup.ps1', 'Start-VisionCertiBridge.ps1')) {
    if (-not $manifest.files.PSObject.Properties[$required]) { throw "설치 목록에 필수 파일이 없습니다: $required" }
}
. (Join-Path $sourceRoot 'Bridge-Setup.ps1')
Write-Output '기존 인증서 프로그램을 확인하고 설치합니다. 인증서 창은 잠시 닫아 주세요.'
Stop-InstalledVisionCertiBridge -InstallRoot $installRoot

New-Item -ItemType Directory -Path $installRoot -Force | Out-Null
foreach ($entry in $manifest.files.PSObject.Properties) {
    $destination = Join-Path $installRoot $entry.Name
    New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $sourceRoot $entry.Name) -Destination $destination -Force
}

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
Write-Output '설치 및 연결 확인 완료. 다음 Windows 로그인부터 자동 실행됩니다. PACS를 다시 열어 공동인증서 로그인을 진행하세요.'
