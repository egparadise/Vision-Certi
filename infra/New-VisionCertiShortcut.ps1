<#
.SYNOPSIS
바탕화면에 "INVIZ Vision-Certi" 실행 아이콘을 만듭니다. 이미 있으면 새로 만듭니다.

.DESCRIPTION
INVIZ 로고의 심볼로 아이콘 파일(work\desktop\vision-certi.ico)을 만들고, infra\Start-VisionCerti.ps1을 실행하는
바로가기를 현재 사용자의 바탕화면에 둡니다. 관리자 권한은 필요하지 않습니다.
#>
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$workspace = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$launcher = Join-Path $PSScriptRoot 'Start-VisionCerti.ps1'
$iconFolder = Join-Path $workspace 'work\desktop'
$iconPath = Join-Path $iconFolder 'vision-certi.ico'
New-Item -ItemType Directory -Path $iconFolder -Force | Out-Null

# The wordmark is too wide for an icon, so use the square symbol at its right end. It is located from the alpha
# channel: the right-most run of non-transparent columns, then the non-transparent rows within it.
$logo = [System.Drawing.Bitmap]::FromFile((Join-Path $workspace 'apps\web\public\inviz-logo-transparent.png'))
try {
    $width = $logo.Width
    $height = $logo.Height
    $locked = $logo.LockBits((New-Object System.Drawing.Rectangle 0, 0, $width, $height),
        [System.Drawing.Imaging.ImageLockMode]::ReadOnly, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $stride = $locked.Stride
    $pixels = New-Object byte[] ($stride * $height)
    [System.Runtime.InteropServices.Marshal]::Copy($locked.Scan0, $pixels, 0, $pixels.Length)
    $logo.UnlockBits($locked)
    function Test-Column([int]$X) {
        for ($y = 0; $y -lt $height; $y++) { if ($pixels[$y * $stride + $X * 4 + 3] -gt 64) { return $true } }
        $false
    }
    function Test-Row([int]$Y) {
        for ($x = $left; $x -le $right; $x++) { if ($pixels[$Y * $stride + $x * 4 + 3] -gt 64) { return $true } }
        $false
    }
    $right = $width - 1
    while ($right -gt 0 -and -not (Test-Column $right)) { $right-- }
    $left = $right
    while ($left -gt 0 -and (Test-Column ($left - 1))) { $left-- }
    $top = 0
    while ($top -lt $height - 1 -and -not (Test-Row $top)) { $top++ }
    $bottom = $height - 1
    while ($bottom -gt $top -and -not (Test-Row $bottom)) { $bottom-- }
    $symbol = New-Object System.Drawing.RectangleF $left, $top, ($right - $left + 1), ($bottom - $top + 1)

    $sizes = 256, 64, 48, 32, 24, 16
    $frames = foreach ($size in $sizes) {
        $bitmap = New-Object System.Drawing.Bitmap $size, $size, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
        $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
        $graphics.InterpolationMode = 'HighQualityBicubic'
        $graphics.PixelOffsetMode = 'HighQuality'
        $graphics.CompositingQuality = 'HighQuality'
        $scale = $size * 0.92 / [Math]::Max($symbol.Width, $symbol.Height)
        $w = [single]($symbol.Width * $scale)
        $h = [single]($symbol.Height * $scale)
        $target = New-Object System.Drawing.RectangleF ([single](($size - $w) / 2)), ([single](($size - $h) / 2)), $w, $h
        $graphics.DrawImage($logo, $target, $symbol, [System.Drawing.GraphicsUnit]::Pixel)
        $graphics.Dispose()
        $png = New-Object System.IO.MemoryStream
        $bitmap.Save($png, [System.Drawing.Imaging.ImageFormat]::Png)
        $bitmap.Dispose()
        , $png.ToArray()
    }
} finally {
    $logo.Dispose()
}

# ICO container with PNG frames (Windows Vista and later): header, one directory entry per frame, frame bytes.
$icon = New-Object System.IO.MemoryStream
$writer = New-Object System.IO.BinaryWriter $icon
$writer.Write([uint16]0)
$writer.Write([uint16]1)
$writer.Write([uint16]$sizes.Count)
$offset = 6 + 16 * $sizes.Count
for ($i = 0; $i -lt $sizes.Count; $i++) {
    $edge = if ($sizes[$i] -ge 256) { 0 } else { $sizes[$i] }  # 0 means 256 pixels
    $writer.Write([byte]$edge)
    $writer.Write([byte]$edge)
    $writer.Write([byte]0)
    $writer.Write([byte]0)
    $writer.Write([uint16]1)
    $writer.Write([uint16]32)
    $writer.Write([uint32]$frames[$i].Length)
    $writer.Write([uint32]$offset)
    $offset += $frames[$i].Length
}
foreach ($frame in $frames) { $writer.Write([byte[]]$frame) }
$writer.Flush()
[System.IO.File]::WriteAllBytes($iconPath, $icon.ToArray())
$writer.Dispose()

$shortcutPath = Join-Path ([Environment]::GetFolderPath('Desktop')) 'INVIZ Vision-Certi.lnk'
$shortcut = (New-Object -ComObject WScript.Shell).CreateShortcut($shortcutPath)
$shortcut.TargetPath = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$shortcut.Arguments = "-NoProfile -ExecutionPolicy RemoteSigned -File `"$launcher`""
$shortcut.WorkingDirectory = $workspace
$shortcut.IconLocation = "$iconPath,0"
$shortcut.Description = 'INVIZ 서버와 PC 인증서 프로그램을 켜고 로그인 화면을 엽니다.'
$shortcut.WindowStyle = 1
$shortcut.Save()
Write-Output "바탕화면 아이콘을 만들었습니다: $shortcutPath"
