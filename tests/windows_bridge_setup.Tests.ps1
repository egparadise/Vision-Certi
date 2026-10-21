$global:visionCertiSetupTest = @{}
$repoRoot = Split-Path -Parent $PSScriptRoot
$setupSource = Join-Path $repoRoot 'infra\bridge-package\Bridge-Setup.ps1'
$installerSource = Join-Path $repoRoot 'infra\bridge-package\Install-VisionCertiBridge.ps1'

Describe 'Windows certificate bridge reinstallation' {
    BeforeEach {
        $global:visionCertiSetupTest.originalLocalAppData = $env:LOCALAPPDATA
        $global:visionCertiSetupTest.fixtureRoot = Join-Path $TestDrive ([guid]::NewGuid().ToString('N'))
        $env:LOCALAPPDATA = Join-Path $global:visionCertiSetupTest.fixtureRoot 'Local App Data'
        $global:visionCertiSetupTest.installRoot = Join-Path $env:LOCALAPPDATA 'Inviz\VisionCerti\Bridge'
        $global:visionCertiSetupTest.packageRoot = Join-Path $global:visionCertiSetupTest.fixtureRoot 'Downloaded Package'
        New-Item -ItemType Directory -Path $global:visionCertiSetupTest.packageRoot,$global:visionCertiSetupTest.installRoot -Force | Out-Null
        $utf8Bom = [System.Text.UTF8Encoding]::new($true)
        foreach ($file in @('Bridge-Setup.ps1', 'Install-VisionCertiBridge.ps1')) {
            $contents = [IO.File]::ReadAllText((Join-Path $repoRoot ('infra\bridge-package\' + $file)))
            [IO.File]::WriteAllText((Join-Path $global:visionCertiSetupTest.packageRoot $file), $contents, $utf8Bom)
        }
        Set-Content -LiteralPath (Join-Path $global:visionCertiSetupTest.packageRoot 'node.exe') -Value 'new-runtime'
        Set-Content -LiteralPath (Join-Path $global:visionCertiSetupTest.packageRoot 'server.mjs') -Value 'new-server'
        # Do not launch an actual process or read any certificates in installer tests.
        Set-Content -LiteralPath (Join-Path $global:visionCertiSetupTest.packageRoot 'Start-VisionCertiBridge.ps1') -Value "Write-Output 'TEST_BRIDGE_STARTED'"
        Set-Content -LiteralPath (Join-Path $global:visionCertiSetupTest.installRoot 'server.mjs') -Value 'old-server'
        $files = [ordered]@{}
        Get-ChildItem -LiteralPath $global:visionCertiSetupTest.packageRoot -File | ForEach-Object {
            $files[$_.Name] = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        }
        @{ files = $files } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $global:visionCertiSetupTest.packageRoot 'manifest.json') -Encoding UTF8
        $global:visionCertiSetupTest.running = $true
        $global:visionCertiSetupTest.processReads = 0
        $global:visionCertiSetupTest.shortcutSaved = $false
        Mock Get-NetTCPConnection { if ($global:visionCertiSetupTest.running) { [pscustomobject]@{ OwningProcess = 12345 } } }
        Mock Get-CimInstance {
            [pscustomobject]@{
                ExecutablePath = Join-Path $global:visionCertiSetupTest.installRoot 'node.exe'
                CommandLine = 'node.exe "' + (Join-Path $global:visionCertiSetupTest.installRoot 'server.mjs') + '" --origin https://vision-certi.web.app'
                CreationDate = 'fixed-creation-time'
            }
        }
        Mock Stop-Process { $global:visionCertiSetupTest.running = $false }
        Mock Start-Sleep {}
        $global:visionCertiSetupTest.fakeShortcut = [pscustomobject]@{ TargetPath=''; Arguments=''; WorkingDirectory=''; WindowStyle=0 }
        $global:visionCertiSetupTest.fakeShortcut | Add-Member -MemberType ScriptMethod -Name Save -Value { $global:visionCertiSetupTest.shortcutSaved = $true }
        $global:visionCertiSetupTest.fakeShell = [pscustomobject]@{}
        $global:visionCertiSetupTest.fakeShell | Add-Member -MemberType ScriptMethod -Name CreateShortcut -Value { param($path) $global:visionCertiSetupTest.fakeShortcut }
        Mock New-Object -ParameterFilter { $ComObject -eq 'WScript.Shell' } { $global:visionCertiSetupTest.fakeShell }
    }
    AfterEach { $env:LOCALAPPDATA = $global:visionCertiSetupTest.originalLocalAppData }

    It 'replaces its own running bridge and restarts it, including paths with spaces' {
        $output = & (Join-Path $global:visionCertiSetupTest.packageRoot 'Install-VisionCertiBridge.ps1')
        ($output -contains 'TEST_BRIDGE_STARTED') | Should Be $true
        (Get-Content -LiteralPath (Join-Path $global:visionCertiSetupTest.installRoot 'server.mjs')) | Should Be 'new-server'
        $global:visionCertiSetupTest.shortcutSaved | Should Be $true
        Assert-MockCalled Stop-Process -Scope It -Times 1 -Exactly -ParameterFilter { $Id -eq 12345 }
    }
    It 'supports a fresh installation when no program is listening' {
        $global:visionCertiSetupTest.running = $false
        $output = & (Join-Path $global:visionCertiSetupTest.packageRoot 'Install-VisionCertiBridge.ps1')
        ($output -contains 'TEST_BRIDGE_STARTED') | Should Be $true
        Assert-MockCalled Stop-Process -Scope It -Times 0 -Exactly
    }
    It 'does not stop another program or overwrite the old install' {
        Mock Get-CimInstance { [pscustomobject]@{ ExecutablePath='C:\Other\node.exe'; CommandLine='node other.mjs'; CreationDate='fixed' } }
        { & (Join-Path $global:visionCertiSetupTest.packageRoot 'Install-VisionCertiBridge.ps1') } | Should Throw 'another program'
        Assert-MockCalled Stop-Process -Scope It -Times 0 -Exactly
        (Get-Content -LiteralPath (Join-Path $global:visionCertiSetupTest.installRoot 'server.mjs')) | Should Be 'old-server'
    }
    It 'rejects another script even if it uses the installed node executable' {
        Mock Get-CimInstance { [pscustomobject]@{ ExecutablePath=(Join-Path $global:visionCertiSetupTest.installRoot 'node.exe'); CommandLine='node other.mjs'; CreationDate='fixed' } }
        { & (Join-Path $global:visionCertiSetupTest.packageRoot 'Install-VisionCertiBridge.ps1') } | Should Throw 'another program'
        Assert-MockCalled Stop-Process -Scope It -Times 0 -Exactly
    }
    It 'does not stop a replacement process after PID reuse' {
        Mock Get-CimInstance {
            $global:visionCertiSetupTest.processReads++
            [pscustomobject]@{ ExecutablePath=(Join-Path $global:visionCertiSetupTest.installRoot 'node.exe'); CommandLine=('node "' + (Join-Path $global:visionCertiSetupTest.installRoot 'server.mjs') + '"'); CreationDate=$global:visionCertiSetupTest.processReads }
        }
        { & (Join-Path $global:visionCertiSetupTest.packageRoot 'Install-VisionCertiBridge.ps1') } | Should Throw 'owner changed'
        Assert-MockCalled Stop-Process -Scope It -Times 0 -Exactly
    }
    It 'validates package integrity before stopping the current bridge' {
        Add-Content -LiteralPath (Join-Path $global:visionCertiSetupTest.packageRoot 'server.mjs') -Value 'tampered'
        { & (Join-Path $global:visionCertiSetupTest.packageRoot 'Install-VisionCertiBridge.ps1') } | Should Throw
        Assert-MockCalled Stop-Process -Scope It -Times 0 -Exactly
        (Get-Content -LiteralPath (Join-Path $global:visionCertiSetupTest.installRoot 'server.mjs')) | Should Be 'old-server'
    }
    It 'waits for the port to close before copying files' {
        Mock Stop-Process {}
        { & (Join-Path $global:visionCertiSetupTest.packageRoot 'Install-VisionCertiBridge.ps1') } | Should Throw 'did not become available'
        (Get-Content -LiteralPath (Join-Path $global:visionCertiSetupTest.installRoot 'server.mjs')) | Should Be 'old-server'
    }
}

Describe 'Bridge connectivity probe' {
    . $setupSource
    It 'checks OPTIONS and the allowed origin without scanning certificates' {
        Mock Invoke-WebRequest { [pscustomobject]@{ StatusCode=204; Headers=@{ 'Access-Control-Allow-Origin'='https://vision-certi.web.app' } } }
        (Get-VisionCertiBridgeStatus).Ready | Should Be $true
        Assert-MockCalled Invoke-WebRequest -Scope It -Times 1 -Exactly -ParameterFilter { $Method -eq 'Options' -and $Headers.Origin -eq 'https://vision-certi.web.app' }
    }
    It 'does not treat an unrelated HTTP responder as ready' {
        Mock Invoke-WebRequest { [pscustomobject]@{ StatusCode=204; Headers=@{} } }
        (Get-VisionCertiBridgeStatus).Ready | Should Be $false
    }
    It 'reports connection failure without exposing certificate data' {
        Mock Invoke-WebRequest { throw 'Connection refused' }
        $status = Get-VisionCertiBridgeStatus
        $status.Ready | Should Be $false
        $status.Error | Should Be 'Connection refused'
    }
}

Describe 'Bridge startup after installation' {
    BeforeEach {
        $global:visionCertiSetupTest.startRoot = Join-Path $TestDrive ([guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $global:visionCertiSetupTest.startRoot | Out-Null
        foreach ($name in @('Start-VisionCertiBridge.ps1', 'Bridge-Setup.ps1')) {
            $contents = [IO.File]::ReadAllText((Join-Path $repoRoot ('infra\bridge-package\' + $name)))
            [IO.File]::WriteAllText((Join-Path $global:visionCertiSetupTest.startRoot $name), $contents, [Text.UTF8Encoding]::new($true))
        }
        Set-Content -LiteralPath (Join-Path $global:visionCertiSetupTest.startRoot 'node.exe') -Value 'test'
        Set-Content -LiteralPath (Join-Path $global:visionCertiSetupTest.startRoot 'server.mjs') -Value 'test'
        Mock Get-NetTCPConnection {}
        Mock Start-Process { [pscustomobject]@{ HasExited=$false } }
        Mock Start-Sleep {}
        Mock Invoke-WebRequest { [pscustomobject]@{ StatusCode=204; Headers=@{ 'Access-Control-Allow-Origin'='https://vision-certi.web.app' } } }
    }
    It 'starts hidden and confirms connection without scanning the disk' {
        & (Join-Path $global:visionCertiSetupTest.startRoot 'Start-VisionCertiBridge.ps1')
        Assert-MockCalled Start-Process -Scope It -Times 1 -Exactly -ParameterFilter {
            $WindowStyle -eq 'Hidden' -and $ArgumentList.Count -eq 3 -and $ArgumentList[1] -eq '--origin' -and $ArgumentList[2] -eq 'https://vision-certi.web.app'
        }
        Assert-MockCalled Invoke-WebRequest -Scope It -Times 0 -Exactly -ParameterFilter { $Method -ne 'Options' }
    }
    It 'reuses an already healthy bridge without starting another process' {
        Mock Get-NetTCPConnection { [pscustomobject]@{ OwningProcess=12345 } }
        & (Join-Path $global:visionCertiSetupTest.startRoot 'Start-VisionCertiBridge.ps1')
        Assert-MockCalled Start-Process -Scope It -Times 0 -Exactly
    }
    It 'fails installation readiness when the origin is not allowed' {
        Mock Invoke-WebRequest { [pscustomobject]@{ StatusCode=204; Headers=@{} } }
        { & (Join-Path $global:visionCertiSetupTest.startRoot 'Start-VisionCertiBridge.ps1') } | Should Throw
    }
}

Remove-Variable -Name visionCertiSetupTest -Scope Global
