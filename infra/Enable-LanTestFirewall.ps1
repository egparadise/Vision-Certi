param([Parameter(Mandatory=$true)][System.Net.IPAddress]$Address)
$ErrorActionPreference = 'Stop'
$parsed = Get-NetIPAddress -AddressFamily IPv4 -IPAddress $Address.IPAddressToString
if (-not $parsed) { throw 'Address must belong to this PC.' }
$ruleName = 'VisionCerti-LAN-Test-HTTPS-5174'
# Windows may already block node.exe on Private networks. Preserve the block on
# every other TCP port; the narrow allow rule below controls the 5174 exception.
$nodePath = (Get-Command node -ErrorAction Stop).Source
Get-NetFirewallRule -Enabled True -Direction Inbound -Action Block | Where-Object {
    $_.Profile.ToString() -eq 'Private'
} | ForEach-Object {
    $app = $_ | Get-NetFirewallApplicationFilter
    $port = $_ | Get-NetFirewallPortFilter
    if ($app.Program -ieq $nodePath -and $port.Protocol -eq 'TCP' -and $port.LocalPort -eq 'Any') {
        Set-NetFirewallRule -Name $_.Name -LocalPort @('1-5173', '5175-65535') | Out-Null
    }
}
$arguments = @{
    DisplayName = 'Vision-Certi LAN test HTTPS 5174'
    Direction = 'Inbound'
    Action = 'Allow'
    Protocol = 'TCP'
    LocalPort = 5174
    LocalAddress = $Address.IPAddressToString
    RemoteAddress = 'LocalSubnet'
    InterfaceAlias = $parsed.InterfaceAlias
    Profile = @('Private', 'Domain')
    Enabled = 'True'
}
if (Get-NetFirewallRule -Name $ruleName -ErrorAction SilentlyContinue) {
    Set-NetFirewallRule -Name $ruleName @arguments | Out-Null
} else {
    New-NetFirewallRule -Name $ruleName @arguments | Out-Null
}
Write-Output 'LAN HTTPS allowed on Private/Domain networks, local subnet only.'
@{ success = $true; address = $Address.IPAddressToString; port = 5174 } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $PSScriptRoot '../work/lan-test/firewall-result.json') -Encoding UTF8
