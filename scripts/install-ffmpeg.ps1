param([string]$Destination = (Join-Path (Split-Path -Parent $PSScriptRoot) '.tools'))
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Path $Destination -Force | Out-Null
# This Windows binary distributor is linked by https://ffmpeg.org/download.html.
$taskZip = Join-Path $Destination 'ffmpeg-release-essentials.zip'
Invoke-WebRequest 'https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip' -OutFile $taskZip -TimeoutSec 180
$taskExpected = (Invoke-WebRequest 'https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip.sha256' -TimeoutSec 20).Content.Trim().Split(' ')[0]
if ((Get-FileHash -LiteralPath $taskZip -Algorithm SHA256).Hash -ne $taskExpected) { throw 'FFmpeg archive checksum mismatch; do not execute this archive.' }
Expand-Archive -LiteralPath $taskZip -DestinationPath $Destination -Force
Get-ChildItem -LiteralPath $Destination -Recurse -File -Filter ffmpeg.exe | ForEach-Object { Write-Output $_.Directory.FullName }
