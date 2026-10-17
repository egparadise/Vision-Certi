<#
.SYNOPSIS
바탕화면 "INVIZ Vision-Certi" 아이콘이 실행하는 스크립트입니다.

.DESCRIPTION
1. infra/start_for_saintview.py로 이 PC의 시험용 INVIZ 서버(DB, OpenBao, API, 내부망 HTTPS 웹)를 켭니다.
   이미 실행 중인 구성 요소는 그대로 둡니다.
2. PC 인증서 프로그램(127.0.0.1:18187)을 켭니다. 실행 중이면 그대로 두고, 실행한 뒤 프로그램 파일이 바뀌었거나
   다른 웹 주소용으로 실행 중일 때만 다시 시작합니다.
3. 기본 브라우저로 로그인 화면을 엽니다.
아이콘은 infra/New-VisionCertiShortcut.ps1로 만듭니다.
#>
param(
    [ValidatePattern('^\d{1,3}(\.\d{1,3}){3}$')]
    [string]$Address = '192.168.45.160',
    [switch]$NoBrowser,
    [switch]$NoPause
)

$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$origin = "https://${Address}:5184"
$loginUrl = "$origin/#/login"
try { $Host.UI.RawUI.WindowTitle = 'INVIZ Vision-Certi' } catch { }

function Write-Step([string]$Text) {
    Write-Host ''
    Write-Host $Text -ForegroundColor Cyan
}

function Get-BridgeListener {
    Get-NetTCPConnection -LocalAddress 127.0.0.1 -LocalPort 18187 -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
}

function Wait-Until([scriptblock]$Condition, [string]$Failure) {
    $deadline = (Get-Date).AddSeconds(10)
    while (-not (& $Condition)) {
        if ((Get-Date) -gt $deadline) { throw $Failure }
        Start-Sleep -Milliseconds 200
    }
}

function Start-InvizServer {
    $python = Join-Path $workspace 'work\certificate-tools\Scripts\python.exe'
    if (-not (Test-Path -LiteralPath $python)) { throw "Python 실행 환경이 없습니다: $python" }
    # Python reports failures on stderr; show them as they are instead of turning them into PowerShell errors.
    $ErrorActionPreference = 'Continue'
    $output = & $python (Join-Path $workspace 'infra\start_for_saintview.py') --address $Address
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($exitCode -ne 0) {
        throw "서버를 켜지 못했습니다(종료 코드 $exitCode). 실행 기록: $(Join-Path $workspace 'work\saintview-stack')"
    }
    $report = $output | Select-Object -Last 1 | ConvertFrom-Json
    $labels = [ordered]@{
        postgres = 'DB'; openbao = '서명키 보관소(OpenBao)'; openbaoTokens = '서명키 접근 토큰'
        crl = '시험용 인증서 폐지 목록'; api = 'INVIZ API'; web = 'INVIZ 웹 화면'
    }
    $states = @{
        started = '시작함'; running = '실행 중'; restarted = '다시 시작함'; valid = '유효함'
        reissued = '다시 발급함'; fresh = '최신'; refreshed = '갱신함'
    }
    foreach ($key in $labels.Keys) {
        $value = [string]$report.$key
        $state = ($value -split ' ')[0]  # "running (restart it to change ...)" is still "running"
        if ($states.ContainsKey($state)) { $value = $states[$state] }
        Write-Host "  - $($labels[$key]): $value"
    }
    if ($report.signing -ne $true) { Write-Host '  - 주의: 전자서명 기능을 쓸 수 없는 상태입니다.' -ForegroundColor Yellow }
    if ($report.certificate -ne 'FRESH') {
        Write-Host "  - 주의: 인증서 폐지 확인 상태: $($report.certificate)" -ForegroundColor Yellow
    }
}

function Start-CertificateBridge {
    $listener = Get-BridgeListener
    if ($listener) {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
        if (-not $process -or $process.CommandLine -notmatch 'local-bridge[\\/]server\.mjs') {
            throw "다른 프로그램(PID $($listener.OwningProcess))이 127.0.0.1:18187을 쓰고 있어 PC 인증서 프로그램을 켤 수 없습니다."
        }
        $newest = Get-ChildItem -LiteralPath (Join-Path $workspace 'apps\web\local-bridge') -Filter *.mjs |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        $sameOrigin = $process.CommandLine -match ('--origin\s+"?' + [regex]::Escape($origin) + '"?(\s|$)')
        if ($sameOrigin -and $newest.LastWriteTime -le $process.CreationDate) {
            Write-Host '  - PC 인증서 프로그램: 실행 중'
            return
        }
        # The bridge keeps no state beyond the certificate list, which the picker reloads.
        Write-Host '  - PC 인증서 프로그램: 바뀐 내용을 적용하려고 다시 시작합니다.'
        Stop-Process -Id $process.ProcessId -Force
        Wait-Until { -not (Get-BridgeListener) } 'PC 인증서 프로그램을 종료하지 못했습니다.'
    }
    & (Join-Path $PSScriptRoot 'start_local_certificate_bridge.ps1') -Origin $origin | Out-Null
    Wait-Until { Get-BridgeListener } 'PC 인증서 프로그램이 127.0.0.1:18187에서 응답하지 않습니다.'
    Write-Host '  - PC 인증서 프로그램: 시작함'
}

$exitCode = 0
try {
    Write-Host 'INVIZ Vision-Certi를 실행합니다.'
    if (-not (Get-NetIPAddress -AddressFamily IPv4 -IPAddress $Address -ErrorAction SilentlyContinue)) {
        $current = @(Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notmatch '^(127|169\.254)\.' } |
            ForEach-Object { $_.IPAddress }) -join ', '
        throw "이 PC에 INVIZ 서버 주소 $Address 가 없습니다(현재 주소: $current). Wi-Fi(네트워크) 연결을 확인해 주세요."
    }
    Write-Step '[1/3] INVIZ 서버를 확인하고 꺼진 구성 요소만 켭니다. 처음 켤 때는 몇 분 걸릴 수 있습니다.'
    Start-InvizServer
    Write-Step '[2/3] PC 인증서 프로그램을 확인합니다.'
    Start-CertificateBridge
    Write-Step '[3/3] 로그인 화면을 엽니다.'
    Write-Host "  - $loginUrl"
    if (-not $NoBrowser) { Start-Process $loginUrl }
    Write-Host ''
    Write-Host '준비되었습니다. 이 창은 곧 닫힙니다. 창이 닫혀도 서버는 계속 실행됩니다.' -ForegroundColor Green
    if (-not $NoPause) { Start-Sleep -Seconds 5 }
} catch {
    $exitCode = 1
    Write-Host ''
    Write-Host "실행하지 못했습니다: $($_.Exception.Message)" -ForegroundColor Red
    if (-not $NoPause) {
        Write-Host ''
        [void](Read-Host '창을 닫으려면 Enter 키를 누르세요')
    }
}
exit $exitCode
