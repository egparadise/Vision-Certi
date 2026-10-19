param(
    [System.Net.IPAddress]$Address = [System.Net.IPAddress]'100.68.224.44'
)
$ErrorActionPreference = 'Stop'
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) { throw 'Run this script in an elevated PowerShell window to change Windows Firewall.' }
$ip = $Address.IPAddressToString
$addressBytes = $Address.GetAddressBytes()
if ($addressBytes.Length -ne 4 -or $addressBytes[0] -ne 100 -or ($addressBytes[1] -band 192) -ne 64) {
    throw 'Use an IPv4 address in the Tailscale 100.64.0.0/10 range.'
}
$assigned = Get-NetIPAddress -AddressFamily IPv4 -IPAddress $ip -ErrorAction SilentlyContinue |
    Where-Object { $_.InterfaceAlias -eq 'Tailscale' } | Select-Object -First 1
if (-not $assigned) { throw "The address $ip is not assigned to the Tailscale interface on this PC." }
$ruleName = 'VisionCerti-Tailscale-HTTPS-5184'
$arguments = @{
    DisplayName = 'Vision-Certi Tailscale HTTPS 5184'
    Direction = 'Inbound'
    Action = 'Allow'
    Protocol = 'TCP'
    LocalPort = 5184
    LocalAddress = $ip
    RemoteAddress = '100.64.0.0/10'
    InterfaceAlias = 'Tailscale'
    Profile = 'Any'
    Enabled = 'True'
}
if (Get-NetFirewallRule -Name $ruleName -ErrorAction SilentlyContinue) {
    Set-NetFirewallRule -Name $ruleName -DisplayName $arguments.DisplayName -Direction Inbound -Action Allow -Profile Any -Enabled True -ErrorAction Stop | Out-Null
    Get-NetFirewallRule -Name $ruleName | Get-NetFirewallAddressFilter |
        Set-NetFirewallAddressFilter -LocalAddress $ip -RemoteAddress '100.64.0.0/10' -ErrorAction Stop | Out-Null
    Get-NetFirewallRule -Name $ruleName | Get-NetFirewallInterfaceFilter |
        Set-NetFirewallInterfaceFilter -InterfaceAlias Tailscale -ErrorAction Stop | Out-Null
    Get-NetFirewallRule -Name $ruleName | Get-NetFirewallPortFilter |
        Set-NetFirewallPortFilter -Protocol TCP -LocalPort 5184 -ErrorAction Stop | Out-Null
} else {
    New-NetFirewallRule -Name $ruleName @arguments -ErrorAction Stop | Out-Null
}
if (-not (Get-NetFirewallRule -Name $ruleName -ErrorAction SilentlyContinue)) {
    throw "Firewall rule $ruleName was not created. Run this script in an elevated PowerShell window."
}
Write-Output "Allowed TCP 5184 on Tailscale address $ip for Tailscale peers only."
