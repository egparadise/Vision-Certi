[CmdletBinding()]
param([switch]$RepairCache)

$ErrorActionPreference = 'Stop'
$site = 'https://vision-certi.web.app'
$profileRoot = [IO.Path]::GetFullPath((Join-Path $env:LOCALAPPDATA 'com.saint.v3.app\EBWebView'))
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'

function Test-Url([string]$url, [string]$method = 'GET', [hashtable]$headers = @{}) {
    try {
        $response = Invoke-WebRequest -Uri $url -Method $method -Headers $headers -UseBasicParsing -TimeoutSec 12
        return [pscustomobject]@{ status = [int]$response.StatusCode; body = [string]$response.Content; error = $null }
    } catch {
        $status = 0
        if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
        return [pscustomobject]@{ status = $status; body = ''; error = $_.Exception.Message }
    }
}

$page = Test-Url "$site/"
$scriptPath = $null
$script = [pscustomobject]@{ status = 0; error = 'HTML entrypoint unavailable' }
if ($page.status -eq 200 -and $page.body -match '/assets/index-[A-Za-z0-9_-]+\.js') {
    $scriptPath = $Matches[0]
    $script = Test-Url ($site + $scriptPath) 'HEAD'
}
$api = Test-Url "$site/v1/runtime/capabilities"
$broker = Test-Url 'http://127.0.0.1:5185/health'
$bridge = Test-Url 'http://127.0.0.1:18187/v1/certificates' 'OPTIONS' @{
    Origin = $site
    'Access-Control-Request-Method' = 'GET'
}
$programFilesX86 = ${env:ProgramFiles(x86)}
if (-not $programFilesX86) { $programFilesX86 = $env:ProgramFiles }
$runtimeRoot = Join-Path $programFilesX86 'Microsoft\EdgeWebView\Application'
$runtimeVersions = @(Get-ChildItem -LiteralPath $runtimeRoot -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -match '^\d+\.\d+\.\d+\.\d+$' } | Select-Object -ExpandProperty Name)
$pacsRunning = [bool](Get-Process -Name 'saint-pacs-tauri' -ErrorAction SilentlyContinue)
$moved = @()
$cacheNames = @('Default\Cache', 'Default\Code Cache', 'Default\GPUCache', 'Default\Service Worker\CacheStorage')

if ($RepairCache) {
    if ($pacsRunning) { throw 'SaintView PACS를 완전히 종료한 후 다시 실행해 주세요. 실행 중에는 캐시를 이동하지 않습니다.' }
    if (-not (Test-Path -LiteralPath $profileRoot -PathType Container)) {
        throw "PACS WebView2 프로필을 찾지 못했습니다: $profileRoot"
    }
    if (((Get-Item -LiteralPath $profileRoot -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "PACS WebView2 프로필이 연결된 폴더라 자동 이동하지 않습니다: $profileRoot"
    }
    foreach ($relative in $cacheNames) {
        $source = [IO.Path]::GetFullPath((Join-Path $profileRoot $relative))
        $destination = "$source.before-visioncerti-$stamp"
        $inside = $source.StartsWith($profileRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -and
            $destination.StartsWith($profileRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)
        if (-not $inside) { throw "캐시 경로가 PACS 프로필 밖입니다: $source" }
        if (-not (Test-Path -LiteralPath $source -PathType Container)) { continue }
        $item = Get-Item -LiteralPath $source -Force
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "연결된 폴더는 자동 이동하지 않습니다: $source"
        }
        Move-Item -LiteralPath $source -Destination $destination -ErrorAction Stop
        $moved += [pscustomobject]@{ cache = $source; backup = $destination }
    }
}

[pscustomobject]@{
    checkedAt = (Get-Date).ToString('o')
    certificatePageHttp = $page.status
    certificatePageError = $page.error
    entryScriptPath = $scriptPath
    entryScriptHttp = $script.status
    entryScriptError = $script.error
    certificateApiHttp = $api.status
    localBrokerHttp = $broker.status
    localCertificateBridgeHttp = $bridge.status
    webView2Versions = $runtimeVersions
    webViewProfilePresent = (Test-Path -LiteralPath $profileRoot -PathType Container)
    pacsRunning = $pacsRunning
    cacheBackups = $moved
    diagnosis = if ($page.status -ne 200 -or $script.status -ne 200) { 'WEB_CONTENT_UNREACHABLE' }
        elseif ($runtimeVersions.Count -eq 0) { 'WEBVIEW2_RUNTIME_NOT_FOUND' }
        elseif ($broker.status -ne 200) { 'LOCAL_BROKER_UNREACHABLE' }
        elseif ($bridge.status -ne 204) { 'PC_CERTIFICATE_BRIDGE_UNREACHABLE' }
        elseif ($RepairCache) { 'CACHE_BACKED_UP_RESTART_PACS' }
        else { 'WEB_AND_LOCAL_SERVICES_REACHABLE_REPAIR_CACHE_IF_BLANK' }
} | ConvertTo-Json -Depth 5
