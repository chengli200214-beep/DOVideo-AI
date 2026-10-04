[CmdletBinding()]
param()

# No Docker daemon, Docker CLI, Desktop launch or socket repair is performed.
# A tiny local .NET fixture process exercises pipe draining and timeout handling.
& {
    param($ScriptsDirectory)
    $ErrorActionPreference = 'Stop'
    $ensurePath = Join-Path $ScriptsDirectory 'ensure-docker.ps1'
    $tokens = $null
    $parseErrors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($ensurePath, [ref]$tokens, [ref]$parseErrors)
    if ($parseErrors.Count) { throw ($parseErrors | Out-String) }
    # Load definitions, never the entry point that touches the real runtime.
    $definitions = ($ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] }, $true) |
        ForEach-Object { $_.Extent.Text }) -join "`r`n"
    . ([scriptblock]::Create($definitions))
    $actualProbe = ${function:Invoke-DockerInfoProbe}
    $fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('dovideo-docker-readiness-' + [Guid]::NewGuid().ToString('N'))
    [void](New-Item -ItemType Directory -Path $fixtureRoot)
    $compiler = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
    if (-not (Test-Path -LiteralPath $compiler -PathType Leaf)) {
        $compiler = Join-Path $env:WINDIR 'Microsoft.NET\Framework\v4.0.30319\csc.exe'
    }
    if (-not (Test-Path -LiteralPath $compiler -PathType Leaf)) { throw 'This Windows-only test requires the .NET Framework 4 compiler installed with Windows PowerShell.' }
    $sourcePath = Join-Path $fixtureRoot 'ProbeFixture.cs'
    $native = Join-Path $fixtureRoot 'ProbeFixture.exe'
    @'
using System;
using System.Diagnostics;
using System.IO;
using System.Threading;
class ProbeFixture {
    public static int Main(string[] args) {
        string output = Environment.GetEnvironmentVariable("ENSURE_DOCKER_FIXTURE_OUT");
        string mode = Environment.GetEnvironmentVariable("ENSURE_DOCKER_FIXTURE_MODE");
        bool safe = args.Length >= 3 && args[0] == "--host" && args[1] == "npipe:////./pipe/dockerDesktopLinuxEngine" && args[2] == "info";
        foreach (string name in new string[] { "DOCKER_CONTEXT", "DOCKER_HOST", "DOCKER_TLS", "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH" }) {
            if (!String.IsNullOrEmpty(Environment.GetEnvironmentVariable(name))) safe = false;
        }
        File.WriteAllText(output, Process.GetCurrentProcess().Id + "\n" + safe);
        if (mode == "hang") Thread.Sleep(10000);
        if (mode == "loud") {
            string chunk = new String('x', 16384);
            for (int i = 0; i < 64; i++) { Console.Out.Write(chunk); Console.Error.Write(chunk); }
        }
        return safe && mode != "fail" ? 0 : 7;
    }
}
'@ | Set-Content -LiteralPath $sourcePath -Encoding UTF8
    & $compiler /nologo /target:exe ("/out:" + $native) $sourcePath
    if ($LASTEXITCODE -ne 0) { throw 'Native fixture compilation failed.' }

    $originalLocal = $env:LOCALAPPDATA
    $savedEnvironment = @{}
    foreach ($name in @('DOCKER_CONTEXT', 'DOCKER_HOST', 'DOCKER_TLS', 'DOCKER_TLS_VERIFY', 'DOCKER_CERT_PATH', 'ENSURE_DOCKER_FIXTURE_MODE', 'ENSURE_DOCKER_FIXTURE_OUT')) {
        $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    }
    function Assert-DockerTest([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
    function Assert-DockerTestState([string]$Expected) {
        $failure = $null
        try { Invoke-DockerReadiness $fixtureRoot 1 1 1 | Out-Null } catch { $failure = $_.Exception }
        Assert-DockerTest ($null -ne $failure -and $failure.Data['State'] -eq $Expected) ("Wrong error state: " + $failure)
    }
    try {
        $env:LOCALAPPDATA = Join-Path $fixtureRoot 'appdata'
        [void](New-Item -ItemType Directory -Path (Join-Path $env:LOCALAPPDATA 'Docker\log\host') -Force)
        function Get-DockerCliPath { 'fixture-only.exe' }
        function Start-Sleep { param($Milliseconds) Microsoft.PowerShell.Utility\Start-Sleep -Milliseconds 40 }
        $testState = @{ Probes = 0; Repairs = 0; Scenario = 'healthy' }
        function Invoke-DockerInfoProbe {
            $testState.Probes++
            [pscustomobject]@{
                Ready = ($testState.Scenario -eq 'healthy' -or ($testState.Scenario -in @('stopped', 'starting') -and $testState.Probes -gt 1))
                TimedOut = ($testState.Scenario -eq 'timeout'); Failure = 'Fixture'
            }
        }
        function Get-DockerDesktopProcesses {
            if ($testState.Scenario -eq 'healthy') { throw 'Healthy path should not inspect Desktop processes.' }
            if ($testState.Scenario -ne 'stopped') { [pscustomobject]@{ ProcessName = 'com.docker.backend'; StartTime = [DateTime]::UtcNow.AddMinutes(-1) } }
        }
        function Invoke-DockerSocketRepair { $testState.Repairs++ }
        $result = Invoke-DockerReadiness $fixtureRoot 1 1 1
        Assert-DockerTest ($result.Ready -and $result.InitialState -eq 'Ready' -and -not $result.StartedDesktop -and $testState.Probes -eq 1 -and $testState.Repairs -eq 0) 'Healthy idempotence failed.'
        $testState.Scenario = 'stopped'; $testState.Probes = 0
        $result = Invoke-DockerReadiness $fixtureRoot 1 1 1
        Assert-DockerTest ($result.Ready -and $result.InitialState -eq 'Stopped' -and $result.StartedDesktop -and $testState.Repairs -eq 1) 'Stopped repair/start failed.'
        $testState.Scenario = 'starting'; $testState.Probes = 0
        $result = Invoke-DockerReadiness $fixtureRoot 1 1 1
        Assert-DockerTest ($result.Ready -and $result.InitialState -eq 'Starting' -and -not $result.StartedDesktop -and $testState.Repairs -eq 1) 'Starting process must not be repaired.'
        $testState.Scenario = 'timeout'; $testState.Probes = 0
        Assert-DockerTestState 'ReadinessTimedOut'
        Assert-DockerTest ($testState.Repairs -eq 1) 'Timeout repaired a running Desktop.'
        $log = Join-Path $env:LOCALAPPDATA 'Docker\log\host\com.docker.backend.exe.log'
        Set-Content -LiteralPath $log -Value ('[' + [DateTime]::UtcNow.AddHours(-1).ToString('o') + '] error remove C:\Users\fixture\AppData\Local\Docker\run\dockerInference: file cannot be accessed')
        Assert-DockerTestState 'ReadinessTimedOut'
        Set-Content -LiteralPath $log -Value ('[' + [DateTime]::UtcNow.ToString('o') + '] error remove C:\Users\fixture\AppData\Local\docker-secrets-engine\engine.sock: file cannot be accessed')
        Assert-DockerTestState 'SocketStartupFailed'
        $testState.Scenario = 'stopped'; $testState.Probes = 0
        function Invoke-DockerSocketRepair { throw 'Fixture repair refused unexpected file.' }
        Assert-DockerTestState 'RepairFailed'
        function Get-DockerCliPath { $null }
        Assert-DockerTestState 'CliNotFound'

        $env:ENSURE_DOCKER_FIXTURE_OUT = Join-Path $fixtureRoot 'probe-result.txt'
        foreach ($name in @('DOCKER_CONTEXT', 'DOCKER_HOST', 'DOCKER_TLS', 'DOCKER_TLS_VERIFY', 'DOCKER_CERT_PATH')) {
            [Environment]::SetEnvironmentVariable($name, 'fixture-parent-value', 'Process')
        }
        $env:ENSURE_DOCKER_FIXTURE_MODE = 'loud'
        $result = & $actualProbe $native 3000
        Assert-DockerTest $result.Ready 'Native success or asynchronous pipe draining failed.'
        $probeFile = @(Get-Content -LiteralPath $env:ENSURE_DOCKER_FIXTURE_OUT)
        Assert-DockerTest ($probeFile[1] -eq 'True') 'Native probe did not isolate the local endpoint/environment.'
        foreach ($name in @('DOCKER_CONTEXT', 'DOCKER_HOST', 'DOCKER_TLS', 'DOCKER_TLS_VERIFY', 'DOCKER_CERT_PATH')) {
            Assert-DockerTest ([Environment]::GetEnvironmentVariable($name, 'Process') -eq 'fixture-parent-value') 'Caller Docker environment was modified.'
        }
        $env:ENSURE_DOCKER_FIXTURE_MODE = 'fail'
        $result = & $actualProbe $native 3000
        Assert-DockerTest (-not $result.Ready -and -not $result.TimedOut) 'Native nonzero exit accepted.'
        $env:ENSURE_DOCKER_FIXTURE_OUT = Join-Path $fixtureRoot 'hung-probe-result.txt'
        $env:ENSURE_DOCKER_FIXTURE_MODE = 'hang'
        $clock = [Diagnostics.Stopwatch]::StartNew()
        $result = & $actualProbe $native 300
        Assert-DockerTest (-not $result.Ready -and $result.TimedOut -and $clock.Elapsed.TotalSeconds -lt 3) 'Native probe timeout was not bounded.'
        $probePid = [int](@(Get-Content -LiteralPath $env:ENSURE_DOCKER_FIXTURE_OUT)[0])
        Assert-DockerTest ($null -eq (Get-Process -Id $probePid -ErrorAction SilentlyContinue)) 'Timed-out probe process survived.'
        Write-Output 'PASS: 8 orchestration scenarios and 3 native fixture probes; no real Docker calls or Desktop changes.'
        Write-Output "Retained test fixtures: $fixtureRoot"
    } finally {
        $env:LOCALAPPDATA = $originalLocal
        foreach ($name in $savedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process') }
    }
} $PSScriptRoot
