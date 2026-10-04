# Offline startup regression: every external process/network command is stubbed.
# The launcher is copied into a temporary workspace; the real .local directory is never touched.
$ErrorActionPreference = 'Stop'
$taskSource = Join-Path $PSScriptRoot 'start-aigc.ps1'
$taskFixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('dovideo startup tests ' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskFixtureRoot | Out-Null
$taskEnvNames = @('DOCKER_CONTEXT', 'DOCKER_HOST', 'DOCKER_TLS', 'DOCKER_TLS_VERIFY', 'DOCKER_CERT_PATH', 'GENERATION_PAID_ENABLED', 'STORYBOARD_PAID_ENABLED')
$taskOriginalEnv = @{}
foreach ($taskName in $taskEnvNames) { $taskOriginalEnv[$taskName] = [Environment]::GetEnvironmentVariable($taskName, 'Process') }
$taskPassed = 0

function Assert-StartupTest($Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Invoke-StartupCase([string]$ScenarioName, [hashtable]$Options, [string]$ExpectedError = '') {
    $fixture = Join-Path $taskFixtureRoot $ScenarioName
    foreach ($relative in @('scripts', '.local', 'server/target', 'client/node_modules/vue', 'jdk/bin', 'ffmpeg')) {
        New-Item -ItemType Directory -Path (Join-Path $fixture $relative) -Force | Out-Null
    }
    Copy-Item -LiteralPath $taskSource -Destination (Join-Path $fixture 'scripts/start-aigc.ps1')
    @'
foreach ($name in @('DOCKER_CONTEXT','DOCKER_HOST','DOCKER_TLS','DOCKER_TLS_VERIFY','DOCKER_CERT_PATH')) {
    if ([Environment]::GetEnvironmentVariable($name, 'Process')) { throw 'Docker environment was not isolated before ensure' }
}
Register-StartupTestEvent 'ensure'
'@ | Set-Content -LiteralPath (Join-Path $fixture 'scripts/ensure-docker.ps1') -Encoding utf8
    foreach ($relative in @('jdk/bin/java.exe', 'ffmpeg/ffprobe.exe', 'client/node_modules/vue/package.json')) {
        [IO.File]::WriteAllText((Join-Path $fixture $relative), '')
    }
    $expectedJar = [IO.Path]::GetFullPath((Join-Path $fixture 'server/target/server-0.0.1-SNAPSHOT.jar'))
    $caseState = @{ Events = [Collections.Generic.List[string]]::new(); CimReads = 0; Options = $Options; Fixture = $fixture; ExpectedJar = $expectedJar }
    $record = @{ pid = 4242; jar = $expectedJar; port = 9095; paid = $false; videoRecovery = $false; seedance = $false }
    if ($Options.Paid) { $record.paid = $true }
    if ($Options.ModeMismatch) { $record.seedance = $true }
    if ($Options.PortMismatch) { $record.port = 9199 }
    if ($Options.BadPid) { $record.pid = 'not-a-process-id' }
    if ($Options.BadJar) { $record.jar = Join-Path $fixture 'another-app.jar' }
    if ($Options.MissingMode) { $record.Remove('paid') }
    if (-not $Options.NoRecord) { $record | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $fixture '.local/aigc-process.json') -Encoding utf8 }
    $secrets = Join-Path $fixture '.local/aigc.env'
    if (-not $Options.MissingSecrets) {
        @('AIGC_DB_PASSWORD=fixture-only', 'AIGC_MYSQL_ROOT_PASSWORD=fixture-only', 'AIGC_REDIS_PASSWORD=fixture-only', 'AIGC_MINIO_PASSWORD=fixture-only') |
            Set-Content -LiteralPath $secrets -Encoding utf8
    }
    foreach ($name in $taskEnvNames) { [Environment]::SetEnvironmentVariable($name, 'inherited-test-value', 'Process') }
    function Register-StartupTestEvent([string]$Event) { $caseState.Events.Add($Event) }
    function Get-CimInstance {
        param($ClassName, $Filter)
        $caseState.CimReads++
        if ($caseState.Options.NoRecord -or ($caseState.Options.JavaExits -and $caseState.CimReads -gt 1)) { return $null }
        $command = 'java.exe -jar "' + $caseState.ExpectedJar + '"'
        if ($caseState.Options.ForeignPid) { $command = 'java.exe -Dnote="' + $caseState.ExpectedJar + '" -jar "unrelated.jar"' }
        [pscustomobject]@{ ExecutablePath = (Join-Path $caseState.Fixture 'jdk/bin/java.exe'); CommandLine = $command; CreationDate = [DateTime]'2026-01-01T00:00:00Z' }
    }
    function Get-NetTCPConnection {
        param($LocalPort, $State, $ErrorAction)
        if ($caseState.Options.NoRecord) { return @() }
        [pscustomobject]@{ OwningProcess = $(if ($caseState.Options.ForeignPort) { 9999 } else { 4242 }) }
    }
    function docker {
        Assert-StartupTest ($args[0] -eq '--host' -and $args[1] -eq 'npipe:////./pipe/dockerDesktopLinuxEngine') 'Docker command was not pinned to the local Linux engine'
        foreach ($name in @('DOCKER_CONTEXT','DOCKER_HOST','DOCKER_TLS','DOCKER_TLS_VERIFY','DOCKER_CERT_PATH')) {
            Assert-StartupTest (-not [Environment]::GetEnvironmentVariable($name, 'Process')) 'Docker environment was not isolated'
        }
        $global:LASTEXITCODE = 0
        if ($args[2] -eq 'volume') {
            Register-StartupTestEvent 'volumes'
            if ($caseState.Options.ExistingVolumes) { 'dovideo-aigc-local_aigc-mysql' }
            return
        }
        Assert-StartupTest ($args[2] -eq 'compose') 'Unexpected Docker operation'
        Assert-StartupTest (($args -join ' ') -match 'compose -p dovideo-aigc-local -f ') 'Unexpected Compose project'
        Assert-StartupTest ([IO.Path]::GetFullPath([string]$args[6]) -eq (Join-Path $caseState.Fixture 'docker-compose.aigc.yml')) 'Compose file escaped the temporary fixture'
        Assert-StartupTest (($args[-3..-1] -join ' ') -eq 'mysql redis minio') 'Compose services were not explicitly scoped'
        Assert-StartupTest ($env:GENERATION_PAID_ENABLED -eq 'false' -and $env:STORYBOARD_PAID_ENABLED -eq 'false') 'Inherited paid settings reached the launcher'
        Register-StartupTestEvent 'compose'
        if ($caseState.Options.ComposeFails) { $global:LASTEXITCODE = 17 }
    }
    function Invoke-WebRequest {
        param([string]$Uri, [int]$TimeoutSec, [switch]$UseBasicParsing)
        if ($Uri -eq 'http://127.0.0.1:9002/minio/health/ready') {
            Register-StartupTestEvent 'minio'
            if ($caseState.Options.MinioFails) { throw 'mock not ready' }
        } elseif ($Uri -eq 'http://127.0.0.1:9095/') { Register-StartupTestEvent 'app' }
        else { throw 'Unexpected network request in offline test' }
        [pscustomobject]@{ StatusCode = 200 }
    }
    function npm.cmd { Register-StartupTestEvent 'build'; throw 'MOCK_NATIVE_BUILD_DISABLED' }
    function Start-Process { throw 'Native process creation is forbidden in this test' }
    $caught = ''
    try {
        $null = & (Join-Path $fixture 'scripts/start-aigc.ps1') -JdkHome (Join-Path $fixture 'jdk') -FfmpegDir (Join-Path $fixture 'ffmpeg') -DependencyReadyTimeoutSeconds 1
    } catch { $caught = $_.Exception.Message }
    if ($ExpectedError) { Assert-StartupTest ($caught -match $ExpectedError) "$ScenarioName expected '$ExpectedError', got '$caught'" }
    else { Assert-StartupTest (-not $caught) "$ScenarioName failed: $caught" }
    foreach ($name in $taskEnvNames) { Assert-StartupTest ([Environment]::GetEnvironmentVariable($name, 'Process') -eq 'inherited-test-value') "$ScenarioName did not restore $name" }
    if ($Options.BeforeDocker) { Assert-StartupTest ($caseState.Events.Count -eq 0) "$ScenarioName reached Docker before its preflight guard" }
    if ($Options.ComposeFails) { Assert-StartupTest (-not $caseState.Events.Contains('minio') -and -not $caseState.Events.Contains('build')) 'Compose failure continued to readiness or build' }
    if ($Options.ExistingVolumes) { Assert-StartupTest (-not (Test-Path -LiteralPath $secrets)) 'Existing volume credentials were replaced'; Assert-StartupTest (-not $caseState.Events.Contains('compose')) 'Missing credentials changed running containers' }
    if ($Options.MinioFails -or $Options.JavaExits -or $Options.ForeignPort) { Assert-StartupTest (-not $caseState.Events.Contains('app') -and -not $caseState.Events.Contains('build')) "$ScenarioName was falsely reported as a healthy app" }
    if (-not $ExpectedError) { Assert-StartupTest (($caseState.Events -join ',') -eq 'ensure,compose,minio,app') 'Existing app bypassed dependency or ownership/readiness checks' }
    if ($Options.NoRecord -and -not $Options.ExistingVolumes) { Assert-StartupTest (($caseState.Events -join ',') -eq 'ensure,volumes,compose,minio,build') 'Fresh startup generated credentials or built before dependency inspection' }
    Write-Output "PASS $ScenarioName"
}

try {
    $cases = @(
        @{ Name='running-app-dependencies-recovered'; Options=@{}; Error='' },
        @{ Name='paid-mode-rejected-first'; Options=@{Paid=$true;BeforeDocker=$true}; Error='mode differs' },
        @{ Name='provider-mode-rejected-first'; Options=@{ModeMismatch=$true;BeforeDocker=$true}; Error='mode differs' },
        @{ Name='port-mismatch-rejected-first'; Options=@{PortMismatch=$true;BeforeDocker=$true}; Error='mode differs' },
        @{ Name='wrong-jar-rejected-first'; Options=@{BadJar=$true;BeforeDocker=$true}; Error='does not belong' },
        @{ Name='recycled-pid-not-substring-matched'; Options=@{ForeignPid=$true;BeforeDocker=$true}; Error='another or unverifiable' },
        @{ Name='invalid-pid-rejected-first'; Options=@{BadPid=$true;BeforeDocker=$true}; Error='ID is invalid' },
        @{ Name='missing-mode-rejected-first'; Options=@{MissingMode=$true;BeforeDocker=$true}; Error='mode cannot be verified' },
        @{ Name='live-app-missing-credentials'; Options=@{MissingSecrets=$true;BeforeDocker=$true}; Error='no saved infrastructure credentials' },
        @{ Name='persistent-volumes-missing-credentials'; Options=@{NoRecord=$true;MissingSecrets=$true;ExistingVolumes=$true}; Error='volumes have no saved credentials' },
        @{ Name='brand-new-env-before-build'; Options=@{NoRecord=$true;MissingSecrets=$true}; Error='MOCK_NATIVE_BUILD_DISABLED' },
        @{ Name='compose-failure-stops-startup'; Options=@{ComposeFails=$true}; Error='infrastructure did not become ready' },
        @{ Name='minio-readiness-is-required'; Options=@{MinioFails=$true}; Error='MinIO did not become ready' },
        @{ Name='java-exit-during-recovery'; Options=@{JavaExits=$true}; Error='original app exited' },
        @{ Name='foreign-port-owner-rejected'; Options=@{ForeignPort=$true}; Error='not owned by the saved Java' }
    )
    foreach ($case in $cases) { Invoke-StartupCase $case.Name $case.Options $case.Error; $taskPassed++ }
    Write-Output "$taskPassed startup tests passed. All process/network calls were stubs in temporary workspaces."
} finally {
    foreach ($name in $taskOriginalEnv.Keys) { [Environment]::SetEnvironmentVariable($name, $taskOriginalEnv[$name], 'Process') }
    $resolvedFixture = [IO.Path]::GetFullPath($taskFixtureRoot)
    $resolvedTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([char[]]'\/') + [IO.Path]::DirectorySeparatorChar
    if (-not $resolvedFixture.StartsWith($resolvedTemp, [StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolvedFixture) -notlike 'dovideo startup tests *') { throw 'Refusing to remove a fixture outside the expected temporary directory.' }
    if (Test-Path -LiteralPath $resolvedFixture) { Remove-Item -LiteralPath $resolvedFixture -Recurse -Force }
}
