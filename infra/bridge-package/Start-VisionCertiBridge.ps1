param()

$ErrorActionPreference = 'Stop'
$installRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$nodeExe = Join-Path $installRoot 'node.exe'
$serverFile = Join-Path $installRoot 'server.mjs'
$origin = 'https://vision-certi.web.app'

if (-not (Test-Path -LiteralPath $nodeExe) -or -not (Test-Path -LiteralPath $serverFile)) {
    throw 'INVIZ 인증서 프로그램 파일이 없습니다. 설치를 다시 실행해 주세요.'
}

$listener = Get-NetTCPConnection -LocalAddress 127.0.0.1 -LocalPort 18187 -State Listen -ErrorAction SilentlyContinue
if (-not $listener) {
    $arguments = @('"' + $serverFile + '"', '--origin', $origin)
    $process = Start-Process -FilePath $nodeExe -ArgumentList $arguments -WorkingDirectory $installRoot -WindowStyle Hidden -PassThru
    Start-Sleep -Milliseconds 1000
    if ($process.HasExited) { throw 'INVIZ 인증서 프로그램이 시작 직후 종료되었습니다.' }
}

try {
    $response = Invoke-WebRequest -Uri 'http://127.0.0.1:18187/v1/certificates' -Headers @{ Origin = $origin } -UseBasicParsing -TimeoutSec 120
    if ($response.StatusCode -ne 200) { throw '인증서 목록 조회에 실패했습니다.' }
    $certificates = @((ConvertFrom-Json $response.Content).certificates)
    Write-Output "INVIZ 인증서 프로그램 연결됨. 이 PC에서 찾은 인증서: $($certificates.Count)건"
} catch {
    throw '로컬 인증서 프로그램에 연결되지 않았습니다. 18187 포트를 다른 프로그램이 사용 중인지 확인해 주세요.'
}
