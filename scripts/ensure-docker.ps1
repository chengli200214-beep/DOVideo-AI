[CmdletBinding()]
param(
    [ValidateRange(1, 600)][int]$TimeoutSeconds = 120,
    [ValidateRange(1, 30)][int]$ProbeTimeoutSeconds = 8,
    [ValidateRange(1, 30)][int]$PollIntervalSeconds = 3
)

# Keep functions and preference changes local, including when this script is dot-sourced.
& {
    param($ScriptDirectory, $TimeoutSeconds, $ProbeTimeoutSeconds, $PollIntervalSeconds)
    $ErrorActionPreference = 'Stop'

    function Get-DockerDesktopProcesses {
        # Keep this exact list aligned with repair-docker-sockets.ps1. The Windows
        # helper service and unrelated docker CLI commands do not own these sockets.
        $names = @('Docker Desktop', 'com.docker.backend', 'com.docker.build',
            'com.docker.dev-envs', 'com.docker.proxy', 'com.docker.wsl-distro-proxy')
        Get-Process | Where-Object { $names -contains $_.ProcessName }
    }

    function Get-DockerCliPath {
        $command = Get-Command docker.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($command) { return $command.Source }
        if ($env:ProgramFiles) {
            $installed = Join-Path $env:ProgramFiles 'Docker\Docker\resources\bin\docker.exe'
            if (Test-Path -LiteralPath $installed -PathType Leaf) { return $installed }
        }
        return $null
    }

    function Invoke-DockerInfoProbe([string]$DockerPath, [int]$TimeoutMilliseconds) {
        $process = New-Object System.Diagnostics.Process
        $start = New-Object System.Diagnostics.ProcessStartInfo
        $start.FileName = $DockerPath
        $start.Arguments = '--host npipe:////./pipe/dockerDesktopLinuxEngine info --format "{{.ServerVersion}}"'
        $start.UseShellExecute = $false
        $start.CreateNoWindow = $true
        $start.RedirectStandardOutput = $true
        $start.RedirectStandardError = $true
        # A remote context or TLS setting must never make a remote daemon look
        # like this machine's Desktop Linux engine. Do not change the caller's env.
        foreach ($name in @('DOCKER_CONTEXT', 'DOCKER_HOST', 'DOCKER_TLS', 'DOCKER_TLS_VERIFY', 'DOCKER_CERT_PATH')) {
            [void]$start.EnvironmentVariables.Remove($name)
        }
        $process.StartInfo = $start
        $started = $false
        try {
            $started = $process.Start()
            if (-not $started) { return [pscustomobject]@{ Ready = $false; TimedOut = $false; Failure = 'ProbeLaunchFailed' } }
            # Drain both pipes concurrently. Never print daemon details, endpoints,
            # environment values or raw error output, and never wait on a pipe task.
            $stdout = $process.StandardOutput.ReadToEndAsync()
            $stderr = $process.StandardError.ReadToEndAsync()
            if (-not $process.WaitForExit([Math]::Max(1, $TimeoutMilliseconds))) {
                # This is only the short-lived CLI process created above; no
                # Desktop/backend/container process is stopped by this script.
                if (-not $process.HasExited) { $process.Kill() }
                [void]$process.WaitForExit(1000)
                return [pscustomobject]@{ Ready = $false; TimedOut = $true; Failure = 'ProbeTimedOut' }
            }
            return [pscustomobject]@{ Ready = ($process.ExitCode -eq 0); TimedOut = $false; Failure = 'EngineNotReady' }
        } catch {
            return [pscustomobject]@{ Ready = $false; TimedOut = $false; Failure = 'ProbeLaunchFailed' }
        } finally {
            if ($started) {
                try { if (-not $process.HasExited) { $process.Kill(); [void]$process.WaitForExit(1000) } } catch { }
            }
            $process.Dispose()
        }
    }

    function Get-DockerSocketStartupFailure([DateTime]$SinceUtc) {
        if (-not $env:LOCALAPPDATA) { return $null }
        $logRoot = Join-Path $env:LOCALAPPDATA 'Docker\log\host'
        $socketPaths = @(
            @{ Pattern = 'Docker[\\/]+run[\\/]+dockerInference\b'; Path = 'Docker\run\dockerInference' },
            @{ Pattern = 'Docker[\\/]+run[\\/]+userAnalyticsOtlpHttp[.]sock\b'; Path = 'Docker\run\userAnalyticsOtlpHttp.sock' },
            @{ Pattern = 'docker-secrets-engine[\\/]+engine[.]sock\b'; Path = 'docker-secrets-engine\engine.sock' }
        )
        # Inspect only bounded tails of the current backend log and its latest
        # rotation. Match a known path, a listener/remove operation and an error.
        foreach ($name in @('com.docker.backend.exe.log', 'com.docker.backend.exe.log.0')) {
            $path = Join-Path $logRoot $name
            try {
                $lines = @(Get-Content -LiteralPath $path -Tail 200 -ErrorAction Stop)
                for ($index = $lines.Count - 1; $index -ge 0; $index--) {
                    $line = $lines[$index]
                    $timestamp = [regex]::Match($line, '\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:[.]\d+)?(?:Z|[+-]\d{2}:\d{2})').Value
                    $parsed = [DateTimeOffset]::MinValue
                    if (-not [DateTimeOffset]::TryParse($timestamp, [ref]$parsed) -or $parsed.UtcDateTime -lt $SinceUtc) { continue }
                    if ($line -notmatch '(?i)(listen|bind|remove|unlink|AF_UNIX)' -or
                        $line -notmatch '(?i)(failed|error|cannot|denied|incorrect|being used|only one usage|not permitted|unable)') { continue }
                    foreach ($socket in $socketPaths) {
                        if ($line -match ('(?i)' + $socket.Pattern)) {
                            return [pscustomobject]@{ SocketPath = $socket.Path; LogPath = $path }
                        }
                    }
                }
            } catch { }
        }
        return $null
    }

    function Invoke-DockerSocketRepair([string]$ScriptDirectory) {
        $repair = Join-Path $ScriptDirectory 'repair-docker-sockets.ps1'
        if (-not (Test-Path -LiteralPath $repair -PathType Leaf)) { throw "Repair helper is missing: $repair" }
        # The helper rechecks process state, validates all paths and entries, keeps
        # backups, and starts the official Desktop executable with a hidden window.
        & $repair -StartDocker | Out-Null
    }

    function Stop-DockerReadiness([string]$State, [string]$Message, [bool]$StartedDesktop, [double]$ElapsedSeconds) {
        $failure = New-Object System.InvalidOperationException($Message)
        $failure.Data['State'] = $State
        $failure.Data['Ready'] = $false
        $failure.Data['StartedDesktop'] = $StartedDesktop
        $failure.Data['ElapsedSeconds'] = [Math]::Round($ElapsedSeconds, 1)
        throw $failure
    }

    function Invoke-DockerReadiness([string]$ScriptDirectory, [int]$TimeoutSeconds, [int]$ProbeTimeoutSeconds, [int]$PollIntervalSeconds) {
        $clock = [System.Diagnostics.Stopwatch]::StartNew()
        $invokedAt = [DateTime]::UtcNow
        $diagnoseSince = $invokedAt.AddMinutes(-5)
        $startedDesktop = $false
        $initialState = 'Ready'
        $docker = Get-DockerCliPath
        if (-not $docker) {
            Stop-DockerReadiness 'CliNotFound' 'Docker CLI was not found. Install Docker Desktop before starting this project.' $false $clock.Elapsed.TotalSeconds
        }
        $firstBudget = [Math]::Max(1, [Math]::Min($ProbeTimeoutSeconds * 1000, $TimeoutSeconds * 1000 - $clock.ElapsedMilliseconds))
        $lastProbe = Invoke-DockerInfoProbe $docker ([int]$firstBudget)
        if (-not $lastProbe.Ready) {
            $processes = @(Get-DockerDesktopProcesses)
            if ($processes.Count -eq 0) {
                $initialState = 'Stopped'
                $diagnoseSince = [DateTime]::UtcNow
                try { Invoke-DockerSocketRepair $ScriptDirectory; $startedDesktop = $true } catch {
                    Stop-DockerReadiness 'RepairFailed' ("Docker Desktop could not be prepared or started: " + $_.Exception.Message) $false $clock.Elapsed.TotalSeconds
                }
            } else {
                $initialState = 'Starting'
                # Ignore an earlier startup's socket error when a new backend is
                # already running. StartTime may be unavailable without elevation.
                foreach ($process in $processes) {
                    if ($process.ProcessName -eq 'com.docker.backend') {
                        try { $diagnoseSince = $process.StartTime.ToUniversalTime() } catch { }
                    }
                }
            }
            while ($clock.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
                $remaining = $TimeoutSeconds * 1000 - $clock.ElapsedMilliseconds
                $delay = [int][Math]::Min($PollIntervalSeconds * 1000, $remaining)
                if ($delay -gt 0) { Start-Sleep -Milliseconds $delay }
                $remaining = $TimeoutSeconds * 1000 - $clock.ElapsedMilliseconds
                if ($remaining -le 0) { break }
                $lastProbe = Invoke-DockerInfoProbe $docker ([int][Math]::Min($ProbeTimeoutSeconds * 1000, $remaining))
                if ($lastProbe.Ready) { break }
            }
        }
        if ($lastProbe.Ready) {
            return [pscustomobject]@{
                State = 'Ready'; Ready = $true; InitialState = $initialState
                StartedDesktop = $startedDesktop; ElapsedSeconds = [Math]::Round($clock.Elapsed.TotalSeconds, 1)
            }
        }
        $socketFailure = Get-DockerSocketStartupFailure $diagnoseSince
        if ($socketFailure) {
            $message = "Docker Desktop's local Linux engine is not ready. Recent startup logs identify the socket $($socketFailure.SocketPath). Quit Docker Desktop from its tray menu, wait for Desktop/backend processes to exit, then rerun scripts\ensure-docker.ps1; it will preserve and replace the known transient socket directories. Log: $($socketFailure.LogPath). No Desktop process was stopped."
            Stop-DockerReadiness 'SocketStartupFailed' $message $startedDesktop $clock.Elapsed.TotalSeconds
        }
        $probeNote = if ($lastProbe.TimedOut) { ' The last docker info probe timed out and only that probe process was stopped.' } else { '' }
        $message = "Docker Desktop's local Linux engine did not become ready within $TimeoutSeconds seconds.$probeNote Inspect Docker Desktop and its logs. If Desktop is stuck, quit it normally and rerun scripts\ensure-docker.ps1. No reset or automatic shutdown was performed."
        Stop-DockerReadiness 'ReadinessTimedOut' $message $startedDesktop $clock.Elapsed.TotalSeconds
    }

    Invoke-DockerReadiness $ScriptDirectory $TimeoutSeconds $ProbeTimeoutSeconds $PollIntervalSeconds
} $PSScriptRoot $TimeoutSeconds $ProbeTimeoutSeconds $PollIntervalSeconds
