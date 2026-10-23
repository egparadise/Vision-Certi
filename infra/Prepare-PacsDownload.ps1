param(
    [Parameter(Mandatory=$true)][string]$InstallerPath,
    [Parameter(Mandatory=$true)][ValidatePattern('^\d+\.\d+\.\d+$')][string]$Version,
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-f0-9]{40}$')][string]$SourceCommit
)
$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$installer = Get-Item -LiteralPath $InstallerPath
if ($installer.VersionInfo.ProductVersion -ne $Version) { throw 'Installer product version mismatch.' }
$downloads = Join-Path $workspace 'apps\web\public\downloads'
New-Item -ItemType Directory -Path $downloads -Force | Out-Null
$fileName = "saintview-pacs-$Version-setup.exe"
$destination = Join-Path $downloads $fileName
$sha256 = (Get-FileHash -LiteralPath $installer.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
Copy-Item -LiteralPath $installer.FullName -Destination $destination -Force
if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant() -ne $sha256) { throw 'Installer copy checksum mismatch.' }
$manifest = [ordered]@{
    version = $Version
    sourceCommit = $SourceCommit
    publishedAt = (Get-Date).ToUniversalTime().ToString('o')
    platform = 'windows-x86_64'
    url = "/downloads/$fileName"
    bytes = $installer.Length
    sha256 = $sha256
    accountLeaseHours = 72
    ownerTimeoutSeconds = 90
}
[IO.File]::WriteAllText((Join-Path $downloads 'pacs-release.json'), ($manifest | ConvertTo-Json) + "`n", [Text.UTF8Encoding]::new($false))
Write-Output "installer=$destination"
Write-Output "sha256=$sha256"
