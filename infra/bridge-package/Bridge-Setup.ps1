function Get-VisionCertiBridgeStatus {
    param([int]$TimeoutSec = 3)
    $origin = 'https://vision-certi.web.app'
    try {
        $response = Invoke-WebRequest -Uri 'http://127.0.0.1:18187/v1/certificates' -Method Options `
            -Headers @{ Origin = $origin; 'Access-Control-Request-Method' = 'GET' } `
            -UseBasicParsing -TimeoutSec $TimeoutSec
        return [pscustomobject]@{
            Ready = ([int]$response.StatusCode -eq 204 -and $response.Headers['Access-Control-Allow-Origin'] -eq $origin)
            Status = [int]$response.StatusCode
            Error = $null
        }
    } catch {
        return [pscustomobject]@{ Ready = $false; Status = 0; Error = $_.Exception.Message }
    }
}

function Get-VisionCertiBridgeListeners {
    # Include wildcard listeners: they also conflict with the loopback port.
    @(Get-NetTCPConnection -LocalPort 18187 -State Listen -ErrorAction SilentlyContinue)
}

function Stop-InstalledVisionCertiBridge {
    param([Parameter(Mandatory = $true)][string]$InstallRoot)
    $nodePath = [IO.Path]::GetFullPath((Join-Path $InstallRoot 'node.exe'))
    $serverPath = [IO.Path]::GetFullPath((Join-Path $InstallRoot 'server.mjs'))
    $serverArgument = '(?i)(?:^|\s)(?:"' + [regex]::Escape($serverPath) + '"|' + [regex]::Escape($serverPath) + ')(?=\s|$)'
    $owners = @(Get-VisionCertiBridgeListeners | Select-Object -ExpandProperty OwningProcess -Unique)
    # Verify every listener before stopping anything. Never stop an unrelated node/PACS process.
    $verified = @()
    foreach ($ownerId in $owners) {
        $details = Get-CimInstance Win32_Process -Filter "ProcessId = $ownerId" -ErrorAction Stop
        if (-not $details -or -not $details.ExecutablePath -or
            -not [string]::Equals($details.ExecutablePath, $nodePath, [StringComparison]::OrdinalIgnoreCase) -or
            $details.CommandLine -notmatch $serverArgument) {
            throw "Port 18187 is used by another program (PID $ownerId). It was not stopped. Close that program and retry Install.cmd."
        }
        $verified += [pscustomobject]@{ Id = $ownerId; Created = $details.CreationDate }
    }
    foreach ($owner in $verified) {
        # Recheck identity in case the process exited and Windows reused the PID.
        $current = Get-CimInstance Win32_Process -Filter "ProcessId = $($owner.Id)" -ErrorAction Stop
        if (-not $current) { continue }
        if ($current.CreationDate -ne $owner.Created -or
            -not [string]::Equals($current.ExecutablePath, $nodePath, [StringComparison]::OrdinalIgnoreCase) -or
            $current.CommandLine -notmatch $serverArgument) {
            throw 'The port owner changed during installation. No replacement process was stopped. Retry Install.cmd.'
        }
        Stop-Process -Id $owner.Id -ErrorAction Stop
    }
    if ($owners.Count -gt 0) {
        for ($attempt = 0; $attempt -lt 20; $attempt++) {
            if (@(Get-VisionCertiBridgeListeners).Count -eq 0) { return }
            Start-Sleep -Milliseconds 250
        }
        throw 'Port 18187 did not become available. Close the certificate window and retry Install.cmd.'
    }
}
