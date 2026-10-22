param(
    [string]$InstallDirectory = (Join-Path $env:ProgramFiles 'SaintViewPACS'),
    [switch]$CheckOnly
)
$ErrorActionPreference = 'Stop'
try {
    $packageRoot = $PSScriptRoot
    $targetRoot = [IO.Path]::GetFullPath($InstallDirectory).TrimEnd('\')
    $target = [IO.Path]::GetFullPath((Join-Path $targetRoot 'certificate-auth\SaintViewCertificateAuth.exe'))
    if (-not $target.StartsWith($targetRoot + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid installation path.' }
    $source = Join-Path $packageRoot 'SaintViewCertificateAuth.exe'
    $manifest = Get-Content -LiteralPath (Join-Path $packageRoot 'manifest.json') -Raw | ConvertFrom-Json
    if (-not (Test-Path -LiteralPath $target -PathType Leaf)) { throw 'SaintView PACS certificate helper is not installed in this folder.' }
    if ((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -ne $manifest.sha256) { throw 'Patch checksum mismatch. Download the package again.' }
    $existingHash = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash
    if ($existingHash -eq $manifest.sha256) { Write-Host 'This activity patch is already installed.'; exit 0 }
    if ($existingHash -notin $manifest.compatibleHelperSha256) { throw 'This helper version is not supported. No files were changed.' }
    if ($CheckOnly) { Write-Host 'Package checksum and installed helper compatibility: OK'; exit 0 }
    $admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    if (-not $admin) {
        $arguments = '-NoProfile -ExecutionPolicy Bypass -File "{0}" -InstallDirectory "{1}"' -f $PSCommandPath, $targetRoot
        $elevated = Start-Process -FilePath powershell.exe -ArgumentList $arguments -Verb RunAs -Wait -PassThru
        exit $elevated.ExitCode
    }
    if (Get-Process -Name 'saint-pacs-tauri' -ErrorAction SilentlyContinue) { throw 'Close all PACS and certificate windows, then run this patch again.' }
    Get-Process -Name 'SaintViewCertificateAuth' -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $target } | Stop-Process
    $backup = $target + '.backup-' + (Get-Date -Format 'yyyyMMdd-HHmmss')
    Copy-Item -LiteralPath $target -Destination $backup
    try {
        Copy-Item -LiteralPath $source -Destination $target -Force
        if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ne $manifest.sha256) { throw 'Installed file verification failed.' }
    } catch {
        Copy-Item -LiteralPath $backup -Destination $target -Force
        throw
    }
    Write-Host 'Installed successfully. Start PACS and sign in with your certificate.'
    Write-Host 'Account, computer and approval request records will be sent automatically.'
    Write-Host ('Backup: ' + $backup)
    Read-Host 'Press Enter to close'
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    if (-not $CheckOnly) { Read-Host 'Press Enter to close' }
    exit 1
}
