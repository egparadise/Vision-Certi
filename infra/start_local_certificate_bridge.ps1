param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^https://[^/]+$|^http://(127\.0\.0\.1|localhost)(:\d+)?$')]
    [string]$Origin,
    [ValidatePattern('^$|^https://[^/]+$')]
    [string]$AdditionalOrigin = ''
)

$workspace = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$web = Join-Path $workspace 'apps\web'
$script = Join-Path $web 'local-bridge\server.mjs'
$node = (Get-Command node -ErrorAction Stop).Source
if (-not (Test-Path -LiteralPath (Join-Path $web 'node_modules\korea-forge'))) {
    throw 'apps/web에서 npm ci를 먼저 실행해야 합니다.'
}
if (Get-NetTCPConnection -LocalAddress 127.0.0.1 -LocalPort 18187 -State Listen -ErrorAction SilentlyContinue) {
    throw '인비즈 PC 인증서 프로그램이 이미 127.0.0.1:18187에서 실행 중입니다.'
}
$arguments = @($script, '--origin', $Origin)
if ($AdditionalOrigin) { $arguments += @('--origin', $AdditionalOrigin) }
$process = Start-Process -FilePath $node -ArgumentList $arguments -WorkingDirectory $web -WindowStyle Hidden -PassThru
Start-Sleep -Milliseconds 800
if ($process.HasExited) { throw "PC 인증서 프로그램 시작에 실패했습니다. 종료 코드: $($process.ExitCode)" }
$runtime = Join-Path $workspace 'work\lan-test'
New-Item -ItemType Directory -Path $runtime -Force | Out-Null
Set-Content -LiteralPath (Join-Path $runtime 'local-certificate-bridge.pid') -Value $process.Id -Encoding ascii
Write-Output "PC 인증서 프로그램 실행 중: PID $($process.Id), Origin $Origin $AdditionalOrigin"
