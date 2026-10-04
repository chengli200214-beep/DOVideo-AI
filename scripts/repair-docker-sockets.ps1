[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'Medium')]
param(
    [switch]$StartDocker
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Only Desktop processes that can own/recreate these transient sockets block repair.
# com.docker.service may remain running after Desktop quits; an unrelated docker CLI is not a blocker.
$desktopProcessNames = @(
    'Docker Desktop', 'com.docker.backend', 'com.docker.build',
    'com.docker.dev-envs', 'com.docker.proxy', 'com.docker.wsl-distro-proxy'
)

function Assert-DockerDesktopStopped {
    $running = @(Get-Process | Where-Object { $desktopProcessNames -contains $_.ProcessName })
    if ($running.Count -gt 0) {
        $labels = ($running | ForEach-Object { '{0} (PID {1})' -f $_.ProcessName, $_.Id }) -join ', '
        throw "Quit Docker Desktop completely before repair. Still running: $labels. This script will not stop processes."
    }
}

if ([string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
    throw 'LOCALAPPDATA is missing; no filesystem changes were made.'
}
$rootItem = Get-Item -LiteralPath $env:LOCALAPPDATA -Force
if (-not $rootItem.PSIsContainer -or $rootItem.PSProvider.Name -ne 'FileSystem') {
    throw 'LOCALAPPDATA must be an existing filesystem directory.'
}
$localAppDataRoot = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $rootItem.FullName).ProviderPath).TrimEnd([char[]]'\/')
$rootPrefix = $localAppDataRoot + [IO.Path]::DirectorySeparatorChar

function Assert-WithinLocalAppData([string]$Path) {
    $absolute = [IO.Path]::GetFullPath($Path)
    if (-not $absolute.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing path outside LOCALAPPDATA: $absolute"
    }
}

function Assert-PlainDirectoryChain([string]$Path) {
    $current = Get-Item -LiteralPath $Path -Force
    while ($null -ne $current) {
        if (-not $current.PSIsContainer -or ($current.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "Refusing a non-directory or reparse-point directory: $($current.FullName)"
        }
        if ([string]::Equals($current.FullName.TrimEnd([char[]]'\/'), $localAppDataRoot, [StringComparison]::OrdinalIgnoreCase)) {
            return
        }
        Assert-WithinLocalAppData $current.FullName
        $current = if ($null -ne $current.Parent) { Get-Item -LiteralPath $current.Parent.FullName -Force } else { $null }
    }
    throw 'Directory ancestry did not terminate at LOCALAPPDATA.'
}

function Get-KnownSocketDirectory([hashtable]$Definition) {
    $target = [IO.Path]::GetFullPath((Join-Path $localAppDataRoot $Definition.RelativePath))
    Assert-WithinLocalAppData $target
    if (-not (Test-Path -LiteralPath $target)) { return $null }
    Assert-PlainDirectoryChain $target
    $resolved = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $target).ProviderPath)
    Assert-WithinLocalAppData $resolved
    if (-not [string]::Equals($target, $resolved, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Resolved socket directory differs from the expected path: $target"
    }
    # Enumerate direct entries only. Never open, rename or delete an individual socket file.
    $entries = @(Get-ChildItem -LiteralPath $resolved -Force)
    foreach ($entry in $entries) {
        if ($entry.PSIsContainer) { throw "Refusing nested directory in socket location: $($entry.FullName)" }
        if ($Definition.Names -notcontains $entry.Name) { throw "Refusing unexpected socket-directory entry: $($entry.FullName)" }
    }
    if ($entries.Count -eq 0) { return $null }
    return [pscustomobject]@{
        Directory = Get-Item -LiteralPath $resolved -Force
        Names = @($entries.Name | Sort-Object)
        Definition = $Definition
    }
}

Assert-PlainDirectoryChain $localAppDataRoot
Assert-DockerDesktopStopped
$definitions = @(
    @{ RelativePath = 'Docker\run'; Names = @('dockerInference', 'userAnalyticsOtlpHttp.sock') },
    @{ RelativePath = 'docker-secrets-engine'; Names = @('engine.sock') }
)

$dockerExecutable = $null
if ($StartDocker) {
    if ([string]::IsNullOrWhiteSpace($env:ProgramFiles)) { throw 'ProgramFiles is missing; start Docker Desktop manually after repair.' }
    $dockerExecutable = Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'
    if (-not (Test-Path -LiteralPath $dockerExecutable -PathType Leaf)) {
        throw "Docker Desktop executable was not found at $dockerExecutable. Omit -StartDocker and start it manually."
    }
}

# Validate every candidate before the first rename, so an unexpected second directory aborts the whole plan.
$plans = @(
    foreach ($definition in $definitions) {
        $inspection = Get-KnownSocketDirectory $definition
        if ($null -eq $inspection) { continue }
        $stamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')
        $backupName = '{0}.socket-backup-{1}-{2}' -f $inspection.Directory.Name, $stamp, [Guid]::NewGuid().ToString('N')
        $backup = [IO.Path]::GetFullPath((Join-Path $inspection.Directory.Parent.FullName $backupName))
        Assert-WithinLocalAppData $backup
        if (Test-Path -LiteralPath $backup) { throw "Backup already exists: $backup" }
        [pscustomobject]@{ Inspection = $inspection; Backup = $backup; BackupName = $backupName }
    }
)

if ($plans.Count -eq 0) { Write-Output 'No non-empty known socket directories require backup.' }
foreach ($plan in $plans) {
    Assert-DockerDesktopStopped
    $current = Get-KnownSocketDirectory $plan.Inspection.Definition
    if ($null -eq $current -or (($current.Names -join '|') -ne ($plan.Inspection.Names -join '|'))) {
        throw 'Socket directory contents changed after inspection; quit Docker Desktop and inspect again.'
    }
    Assert-WithinLocalAppData $plan.Backup
    if (Test-Path -LiteralPath $plan.Backup) { throw "Backup already exists: $($plan.Backup)" }
    $action = "Rename the entire transient socket directory to $($plan.Backup)"
    if ($PSCmdlet.ShouldProcess($current.Directory.FullName, $action)) {
        Assert-DockerDesktopStopped
        Rename-Item -LiteralPath $current.Directory.FullName -NewName $plan.BackupName -ErrorAction Stop
        [pscustomobject]@{ Original = $current.Directory.FullName; Backup = $plan.Backup; Status = 'BackedUp' }
    }
}

if ($StartDocker -and $PSCmdlet.ShouldProcess($dockerExecutable, 'Start Docker Desktop after socket backup')) {
    Assert-DockerDesktopStopped
    Start-Process -FilePath $dockerExecutable -WindowStyle Hidden
    Write-Output 'Docker Desktop was started. Wait for its engine, then check docker info.'
}
