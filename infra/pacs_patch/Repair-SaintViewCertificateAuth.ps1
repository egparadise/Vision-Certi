[CmdletBinding()]
param(
    [switch]$Apply,
    [string]$ConfigDir = (Join-Path $env:LOCALAPPDATA 'SaintViewPACS\CertificateAuth')
)

$ErrorActionPreference = 'Stop'
$expectedBase = 'https://vision-certi.web.app'
$expectedClient = 'vision-certi-pacs'
$expectedTenant = 'inviz'
$allowedOldBases = @($expectedBase, 'https://hahattalk-vision-certi.web.app')

$resolvedDir = [IO.Path]::GetFullPath($ConfigDir)
$file = Join-Path $resolvedDir 'broker-config.json'
if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
    throw "SaintView 인증 브로커 설정을 찾지 못했습니다: $file"
}
$config = Get-Content -LiteralPath $file -Raw -Encoding UTF8 | ConvertFrom-Json
if (-not $config -or [int]$config.CERTIFICATE_PROOF_PROTOCOL_VERSION -ne 1) {
    throw '지원하지 않는 인증 브로커 설정 버전입니다. 설치된 PACS 버전을 확인하세요.'
}
if ($config.VISIONCERTI_BASE_URL -and $allowedOldBases -notcontains $config.VISIONCERTI_BASE_URL.TrimEnd('/')) {
    throw '기존 인증 서버 주소가 예상한 운영 주소와 다릅니다. 자동 변경을 중단합니다.'
}
if ($config.VISIONCERTI_PACS_CLIENT_ID -and $config.VISIONCERTI_PACS_CLIENT_ID -ne $expectedClient) {
    throw '기존 PACS 클라이언트 ID가 다릅니다. 자동 변경을 중단합니다.'
}
if ($config.VISIONCERTI_PACS_TENANT_ID -and $config.VISIONCERTI_PACS_TENANT_ID -ne $expectedTenant) {
    throw '기존 기관 ID가 다릅니다. 자동 변경을 중단합니다.'
}
if (-not $config.BROKER_PACS_ORIGIN -or -not $config.BROKER_PACS_ORIGIN.StartsWith('https://')) {
    throw 'PACS 서버 주소가 HTTPS가 아니거나 비어 있습니다. 자동 변경을 중단합니다.'
}

function Test-Endpoint([string]$url, [string]$method = 'GET', [string]$body = '') {
    try {
        $args = @{ Uri = $url; Method = $method; UseBasicParsing = $true; TimeoutSec = 12 }
        if ($body) {
            $args.ContentType = 'application/json'
            $args.Body = $body
        }
        $response = Invoke-WebRequest @args
        return [int]$response.StatusCode
    } catch {
        if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode }
        return 0
    }
}

function Test-PcCertificateBridge {
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Method OPTIONS -Uri 'http://127.0.0.1:18187/v1/certificates' `
            -Headers @{ Origin = 'https://vision-certi.web.app'; 'Access-Control-Request-Method' = 'GET' } -TimeoutSec 5
        return [int]$response.StatusCode
    } catch {
        if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode }
        return 0
    }
}

$secretPresent = [bool]$config.VISIONCERTI_PACS_CLIENT_SECRET_FILE -and
    (Test-Path -LiteralPath $config.VISIONCERTI_PACS_CLIENT_SECRET_FILE -PathType Leaf)
$before = [ordered]@{
    baseUrl = $config.VISIONCERTI_BASE_URL
    apiBaseUrl = $config.VISIONCERTI_API_BASE_URL
    clientId = $config.VISIONCERTI_PACS_CLIENT_ID
    tenantId = $config.VISIONCERTI_PACS_TENANT_ID
    pacsOrigin = $config.BROKER_PACS_ORIGIN
    hasClientSecretFile = [bool]$secretPresent
}
$backup = $null
if ($Apply) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $backup = Join-Path $resolvedDir "broker-config.before-vision-certi-$stamp.json"
    $config.VISIONCERTI_BASE_URL = $expectedBase
    $config.VISIONCERTI_API_BASE_URL = $expectedBase
    $config.VISIONCERTI_PACS_CLIENT_ID = $expectedClient
    $config.VISIONCERTI_PACS_TENANT_ID = $expectedTenant
    $tempFile = Join-Path $resolvedDir ('.broker-config-' + [guid]::NewGuid().ToString('N') + '.tmp')
    try {
        $json = $config | ConvertTo-Json -Depth 12
        [IO.File]::WriteAllText($tempFile, $json + [Environment]::NewLine, (New-Object Text.UTF8Encoding($false)))
        [IO.File]::Replace($tempFile, $file, $backup)
    } finally {
        if (Test-Path -LiteralPath $tempFile) { Remove-Item -LiteralPath $tempFile -Force }
    }
}

$brokerStatus = Test-Endpoint 'http://127.0.0.1:5185/health'
$pcBridgeStatus = Test-PcCertificateBridge
$pacsStatus = Test-Endpoint ($config.BROKER_PACS_ORIGIN.TrimEnd('/') + '/api/health')
$pacsConfigStatus = Test-Endpoint ($config.BROKER_PACS_ORIGIN.TrimEnd('/') + '/api/vc/config')
$serverStatus = Test-Endpoint ($expectedBase + '/v1/runtime/capabilities')
$challengeBody = @{ audience = $expectedClient } | ConvertTo-Json -Compress
$challengeStatus = Test-Endpoint ($expectedBase + '/v1/pacs-certificate-proofs/challenges') 'POST' $challengeBody
$public = $null
try { $public = Invoke-RestMethod 'http://127.0.0.1:5185/vision-certi/public-config' -TimeoutSec 5 } catch { }

[pscustomobject]@{
    applied = [bool]$Apply
    configFile = $file
    backupFile = $backup
    before = $before
    configuredClientId = $config.VISIONCERTI_PACS_CLIENT_ID
    configuredTenantId = $config.VISIONCERTI_PACS_TENANT_ID
    runningBrokerClientId = if ($public) { $public.clientId } else { $null }
    brokerHealthHttp = $brokerStatus
    pcCertificateBridgeHealthHttp = $pcBridgeStatus
    pacsHealthHttp = $pacsStatus
    pacsVcConfigHttp = $pacsConfigStatus
    visionCertiCapabilitiesHttp = $serverStatus
    visionCertiChallengeHttp = $challengeStatus
    needsBrokerRestart = [bool]$Apply
    needsHmacCredential = -not [bool]$secretPresent
    loginReady = ($brokerStatus -eq 200 -and $pacsStatus -eq 200 -and $serverStatus -eq 200 -and
        $challengeStatus -eq 201 -and [bool]$secretPresent -and
        $public -and $public.clientId -eq $expectedClient)
} | ConvertTo-Json -Depth 6
