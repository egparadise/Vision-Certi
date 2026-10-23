param()

$ErrorActionPreference = 'Stop'
$installRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$nodeExe = Join-Path $installRoot 'node.exe'
$serverFile = Join-Path $installRoot 'server.mjs'
$origin = 'https://vision-certi.web.app'
. (Join-Path $installRoot 'Bridge-Setup.ps1')

if (-not (Test-Path -LiteralPath $nodeExe) -or -not (Test-Path -LiteralPath $serverFile)) {
    throw 'INVIZ 인증서 프로그램 파일이 없습니다. 설치를 다시 실행해 주세요.'
}

$listener = @(Get-VisionCertiBridgeListeners)
$process = $null
if (-not $listener) {
    $arguments = @(('"' + $serverFile + '"'), '--origin', $origin)
    $process = Start-Process -FilePath $nodeExe -ArgumentList $arguments -WorkingDirectory $installRoot -WindowStyle Hidden -PassThru
}

# Check connectivity without starting a full-disk certificate scan during installation.
for ($attempt = 0; $attempt -lt 10; $attempt++) {
    if ($process -and $process.HasExited) { throw 'INVIZ 인증서 프로그램이 시작 직후 종료되었습니다. Install.cmd를 다시 실행해 주세요.' }
    $status = Get-VisionCertiBridgeStatus
    if ($status.Ready) {
        Write-Output 'INVIZ 인증서 프로그램 연결됨 (HTTP 204). 인증서 웹 화면에서 자동 검색할 수 있습니다.'
        return
    }
    Start-Sleep -Milliseconds 500
}
throw "로컬 인증서 프로그램 연결 확인에 실패했습니다 (HTTP $($status.Status)). Install.cmd를 다시 실행해 주세요. $($status.Error)"
