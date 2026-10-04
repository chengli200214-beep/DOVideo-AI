# Offline startup regression: every external process/network command is stubbed.
# The launcher is copied into a temporary workspace; the real .local directory is never touched.
$ErrorActionPreference = 'Stop'
$taskSource = Join-Path $PSScriptRoot 'start-aigc.ps1'
$taskFixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('dovideo startup tests ' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskFixtureRoot | Out-Null
$taskEnvNames = @('DOCKER_CONTEXT', 'DOCKER_HOST', 'DOCKER_TLS', 'DOCKER_TLS_VERIFY', 'DOCKER_CERT_PATH',
    'GENERATION_PROVIDER', 'GENERATION_TEXT_MODEL', 'GENERATION_IMAGE_MODEL', 'GENERATION_PAID_ENABLED',
    'GENERATION_AUTHORIZATION_ID', 'GENERATION_APPROVED_MODELS', 'GENERATION_MAX_PAID_TASKS', 'GENERATION_BUDGET_LIMIT', 'GENERATION_RESERVATION_PER_TASK',
    'STORYBOARD_PAID_ENABLED', 'STORYBOARD_API_KEY', 'STORYBOARD_AUTHORIZATION_ID', 'STORYBOARD_APPROVED_MODEL', 'STORYBOARD_MAX_CALLS', 'STORYBOARD_BUDGET_LIMIT', 'STORYBOARD_RESERVATION_PER_CALL',
    'SEEDANCE_API_KEY', 'SEEDANCE_BASE_URL', 'SEEDANCE_ARTIFACT_HOSTS', 'SEEDANCE_RECOVERY_ENABLED')
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
    $record = @{ pid = 4242; jar = $expectedJar; port = 9095; paid = $false; seedance = [bool]$Options.Seedance }
    if ($Options.ContainsKey('LegacyRecovery')) { $record.videoRecovery = $Options.LegacyRecovery }
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
    if ($Options.Seedance) {
        @('SEEDANCE_API_KEY=fixture-only', 'SEEDANCE_BASE_URL=https://ark.cn-beijing.volces.com/api/v3', 'SEEDANCE_ARTIFACT_HOSTS=fixture.invalid',
            'SEEDANCE_RECOVERY_ENABLED=true', 'GENERATION_PAID_ENABLED=true', 'GENERATION_MAX_PAID_TASKS=9999', 'GENERATION_BUDGET_LIMIT=9999',
            'GENERATION_AUTHORIZATION_ID=fixture-must-not-import', 'GENERATION_APPROVED_MODELS=fixture-must-not-import',
            'STORYBOARD_PAID_ENABLED=true', 'STORYBOARD_API_KEY=fixture-must-not-import') |
            Set-Content -LiteralPath (Join-Path $fixture '.local/seedance-video.env') -Encoding utf8
    }
    $inheritedEnvironment = @{}
    foreach ($name in $taskEnvNames) {
        $inheritedEnvironment[$name] = if ($name -match '(PAID|RECOVERY)_ENABLED$') { 'true' } else { 'inherited-test-value' }
        [Environment]::SetEnvironmentVariable($name, $inheritedEnvironment[$name], 'Process')
    }
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
        Assert-StartupTest ($env:SEEDANCE_RECOVERY_ENABLED -eq 'false') 'Seedance recovery was unexpectedly enabled'
        foreach ($name in @('GENERATION_AUTHORIZATION_ID', 'GENERATION_APPROVED_MODELS', 'STORYBOARD_API_KEY', 'STORYBOARD_AUTHORIZATION_ID', 'STORYBOARD_APPROVED_MODEL')) {
            Assert-StartupTest (-not [Environment]::GetEnvironmentVariable($name, 'Process')) "Paid permission or planning credential was imported: $name"
        }
        foreach ($name in @('GENERATION_MAX_PAID_TASKS', 'GENERATION_BUDGET_LIMIT', 'GENERATION_RESERVATION_PER_TASK', 'STORYBOARD_MAX_CALLS', 'STORYBOARD_BUDGET_LIMIT', 'STORYBOARD_RESERVATION_PER_CALL')) {
            Assert-StartupTest ([Environment]::GetEnvironmentVariable($name, 'Process') -eq '0') "Paid quota was not reset: $name"
        }
        if ($caseState.Options.Seedance) {
            Assert-StartupTest ($env:GENERATION_PROVIDER -eq 'seedance' -and $env:SEEDANCE_API_KEY -eq 'fixture-only') 'Seedance did not use the configured test credentials'
            Assert-StartupTest ($env:SEEDANCE_BASE_URL -eq 'https://ark.cn-beijing.volces.com/api/v3' -and $env:SEEDANCE_ARTIFACT_HOSTS -eq 'fixture.invalid') 'Seedance endpoint/archive configuration was not preserved'
            Assert-StartupTest ($env:GENERATION_TEXT_MODEL -eq 'doubao-seedance-2-0-mini-260615' -and $env:GENERATION_IMAGE_MODEL -eq 'doubao-seedance-2-0-mini-260615') 'Unexpected Seedance model'
        } else {
            Assert-StartupTest ($env:GENERATION_PROVIDER -eq 'mock' -and $env:GENERATION_TEXT_MODEL -eq 'mock-video' -and $env:GENERATION_IMAGE_MODEL -eq 'mock-video') 'Default startup did not use the mock provider and model'
            Assert-StartupTest (-not $env:SEEDANCE_API_KEY -and -not $env:SEEDANCE_ARTIFACT_HOSTS) 'Default startup retained provider credentials'
        }
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
        $launchArguments = @{ JdkHome = (Join-Path $fixture 'jdk'); FfmpegDir = (Join-Path $fixture 'ffmpeg'); DependencyReadyTimeoutSeconds = 1 }
        if ($Options.Seedance) { $launchArguments.Seedance = $true }
        if ($Options.LegacyParameter) { $launchArguments.RecoverVideoTasks = $true }
        $null = & (Join-Path $fixture 'scripts/start-aigc.ps1') @launchArguments
    } catch { $caught = $_.Exception.Message }
    if ($ExpectedError) { Assert-StartupTest ($caught -match $ExpectedError) "$ScenarioName expected '$ExpectedError', got '$caught'" }
    else { Assert-StartupTest (-not $caught) "$ScenarioName failed: $caught" }
    foreach ($name in $taskEnvNames) { Assert-StartupTest ([Environment]::GetEnvironmentVariable($name, 'Process') -eq $inheritedEnvironment[$name]) "$ScenarioName did not restore $name" }
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
        @{ Name='legacy-mock-record-reused'; Options=@{LegacyRecovery=$false}; Error='' },
        @{ Name='legacy-recovery-mode-rejected-first'; Options=@{LegacyRecovery=$true;BeforeDocker=$true}; Error='legacy recovery mode.*stop-aigc' },
        @{ Name='legacy-recovery-invalid-flag-rejected-first'; Options=@{LegacyRecovery='false';BeforeDocker=$true}; Error='legacy recovery mode.*stop-aigc' },
        @{ Name='retired-recovery-parameter-rejected'; Options=@{LegacyParameter=$true;BeforeDocker=$true}; Error='RecoverVideoTasks' },
        @{ Name='seedance-reuse-keeps-paid-disabled'; Options=@{Seedance=$true}; Error='' },
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
        @{ Name='new-seedance-keeps-paid-disabled-before-build'; Options=@{NoRecord=$true;MissingSecrets=$true;Seedance=$true}; Error='MOCK_NATIVE_BUILD_DISABLED' },
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
