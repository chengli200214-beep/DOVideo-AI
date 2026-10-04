[CmdletBinding()]
param([string]$JdkHome = $env:JAVA_HOME, [string]$FfmpegDir = $env:FFMPEG_DIR, [int]$Port = 9095, [switch]$Seedance,
    [ValidateRange(1, 300)][int]$DependencyReadyTimeoutSeconds = 60)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskLocal = Join-Path $taskRoot '.local'
$taskPidFile = Join-Path $taskLocal 'aigc-process.json'
$taskSecretsFile = Join-Path $taskLocal 'aigc.env'
$taskRunningRecord = $null
$taskExpectedJar = [IO.Path]::GetFullPath((Join-Path $taskRoot 'server/target/server-0.0.1-SNAPSHOT.jar'))
$taskDockerHost = 'npipe:////./pipe/dockerDesktopLinuxEngine'
function Test-AigcJavaProcess($Candidate) {
    if (-not $Candidate -or [string]::IsNullOrWhiteSpace($Candidate.CommandLine)) { return $false }
    if ([IO.Path]::GetFileName([string]$Candidate.ExecutablePath) -ine 'java.exe') { return $false }
    if ($Candidate.CommandLine -notmatch '(?:^|\s)-jar\s+(?:"([^"]+)"|(\S+))(?=\s|$)') { return $false }
    $taskCommandJar = if ($Matches[1]) { $Matches[1] } else { $Matches[2] }
    return [IO.Path]::GetFullPath($taskCommandJar) -eq $taskExpectedJar
}
if (-not $JdkHome -or -not (Test-Path -LiteralPath (Join-Path $JdkHome 'bin/java.exe'))) { throw 'Pass -JdkHome with an installed JDK 21 or newer.' }
if (Test-Path -LiteralPath $taskPidFile) {
    $taskPrevious = Get-Content -LiteralPath $taskPidFile -Raw | ConvertFrom-Json
    if ($taskPrevious.jar -ne $taskExpectedJar) { throw 'Saved process does not belong to this workspace.' }
    $taskRecordedPid = 0
    if (-not [int]::TryParse([string]$taskPrevious.pid, [ref]$taskRecordedPid) -or $taskRecordedPid -le 0) { throw 'Saved process ID is invalid.' }
    $taskProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $taskRecordedPid"
    if ($taskProcess -and -not (Test-AigcJavaProcess $taskProcess)) { throw 'Saved PID belongs to another or unverifiable process. No process was stopped.' }
    if ($taskProcess) {
        foreach ($taskFlag in @('paid', 'seedance')) { if ($taskPrevious.$taskFlag -isnot [bool]) { throw 'Saved process mode cannot be verified; no dependencies were restarted.' } }
        # Older launchers recorded this retired mode. Only an explicit false is safe to reuse.
        $taskLegacyRecovery = $taskPrevious.PSObject.Properties['videoRecovery']
        if ($taskLegacyRecovery -and ($taskLegacyRecovery.Value -isnot [bool] -or $taskLegacyRecovery.Value)) { throw 'Saved process has an enabled or unverifiable legacy recovery mode. Stop it with scripts/stop-aigc.ps1, then start again.' }
        if ([bool]$taskPrevious.paid -or [bool]$taskPrevious.seedance -ne [bool]$Seedance -or [int]$taskPrevious.port -ne $Port) { throw 'Requested port/provider/paid mode differs from the running app. Stop it with scripts/stop-aigc.ps1, then start again.' }
        $taskRunningRecord = $taskPrevious
        $taskRunningCreationDate = $taskProcess.CreationDate
    }
}
if (-not $taskRunningRecord -and (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)) { throw "Port $Port is occupied; choose -Port or stop the existing service." }
New-Item -ItemType Directory -Path $taskLocal -Force | Out-Null
if (-not $FfmpegDir) {
    $taskBinary = Get-ChildItem -LiteralPath (Join-Path $taskRoot '.tools') -Recurse -File -Filter ffmpeg.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($taskBinary) { $FfmpegDir = $taskBinary.Directory.FullName }
}
if (-not $FfmpegDir) {
    $taskCommand = Get-Command ffmpeg -ErrorAction SilentlyContinue
    if ($taskCommand) { $FfmpegDir = Split-Path -Parent $taskCommand.Source }
}
if (-not $FfmpegDir -or -not (Test-Path -LiteralPath (Join-Path $FfmpegDir 'ffprobe.exe'))) { throw 'Install FFmpeg/ffprobe with scripts/install-ffmpeg.ps1 or pass -FfmpegDir.' }
$FfmpegDir = (Resolve-Path -LiteralPath $FfmpegDir).Path
if ($taskRunningRecord -and -not (Test-Path -LiteralPath $taskSecretsFile)) { throw 'The running app has no saved infrastructure credentials. Restore .local/aigc.env before recovering dependencies.' }
$taskDockerSaved = @{}
try {
foreach ($taskName in @('DOCKER_CONTEXT', 'DOCKER_HOST', 'DOCKER_TLS', 'DOCKER_TLS_VERIFY', 'DOCKER_CERT_PATH')) {
    $taskDockerSaved[$taskName] = [Environment]::GetEnvironmentVariable($taskName, 'Process')
    [Environment]::SetEnvironmentVariable($taskName, $null, 'Process')
}
# Paid/mode/ownership checks must precede engine startup: reconnecting a live JVM can wake its workers.
& (Join-Path $PSScriptRoot 'ensure-docker.ps1') | Out-Host
if (-not (Test-Path -LiteralPath $taskSecretsFile)) {
    $taskVolumes = @(& docker --host $taskDockerHost volume ls --format '{{.Name}}')
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect local persistent volumes; no credentials were generated.' }
    if (@($taskVolumes | Where-Object { $_.StartsWith('dovideo-aigc-local_', [StringComparison]::OrdinalIgnoreCase) }).Count -gt 0) {
        throw 'Existing project volumes have no saved credentials. Restore .local/aigc.env; generating new passwords would not update existing MySQL data.'
    }
    @('AIGC_DB_PASSWORD','AIGC_MYSQL_ROOT_PASSWORD','AIGC_REDIS_PASSWORD','AIGC_MINIO_PASSWORD') | ForEach-Object { "$_=$([Guid]::NewGuid().ToString('N'))" } | Set-Content -LiteralPath $taskSecretsFile -Encoding utf8
}
$taskEnvironment = @{}
foreach ($taskLine in Get-Content -LiteralPath $taskSecretsFile) {
    if ($taskLine -match '^(AIGC_[A-Z_]+)=(.+)$') { $taskEnvironment[$Matches[1]] = $Matches[2] }
}
foreach ($taskRequired in @('AIGC_DB_PASSWORD','AIGC_MYSQL_ROOT_PASSWORD','AIGC_REDIS_PASSWORD','AIGC_MINIO_PASSWORD')) { if (-not $taskEnvironment[$taskRequired]) { throw 'Local environment is incomplete.' } }
$taskEnvironment['JAVA_HOME']=$JdkHome
$taskEnvironment['VITE_AIGC_ONLY']='true'
$taskEnvironment['VITE_API_BASE_URL']='/'
$taskEnvironment['DB_URL']='jdbc:mysql://127.0.0.1:3308/aigc?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8'
$taskEnvironment['DB_USERNAME']='aigc'; $taskEnvironment['DB_PASSWORD']=$taskEnvironment['AIGC_DB_PASSWORD']
$taskEnvironment['REDIS_HOST']='127.0.0.1'; $taskEnvironment['REDIS_PORT']='6380'; $taskEnvironment['REDIS_PASSWORD']=$taskEnvironment['AIGC_REDIS_PASSWORD']
$taskEnvironment['MINIO_ENDPOINT']='http://127.0.0.1:9002'; $taskEnvironment['MINIO_ACCESS_KEY']='aigc-local'; $taskEnvironment['MINIO_SECRET_KEY']=$taskEnvironment['AIGC_MINIO_PASSWORD']; $taskEnvironment['MINIO_BUCKET']='aigc'
$taskEnvironment['FFMPEG_DIR']=$FfmpegDir; $taskEnvironment['SERVER_PORT']="$Port"; $taskEnvironment['SERVER_ADDRESS']='127.0.0.1'
# This local launcher is deliberately a no-paid-call demonstration, regardless of inherited shell settings.
$taskEnvironment['GENERATION_PROVIDER']='mock'; $taskEnvironment['GENERATION_PAID_ENABLED']='false'
$taskEnvironment['GENERATION_TEXT_MODEL']='mock-video'; $taskEnvironment['GENERATION_IMAGE_MODEL']='mock-video'
$taskEnvironment['SEEDANCE_API_KEY']=''; $taskEnvironment['SEEDANCE_BASE_URL']='https://ark.cn-beijing.volces.com/api/v3'
$taskEnvironment['SEEDANCE_ARTIFACT_HOSTS']=''; $taskEnvironment['SEEDANCE_RECOVERY_ENABLED']='false'
foreach ($taskKind in @('TEXT','IMAGE')) {
    $taskEnvironment["GENERATION_${taskKind}_ENABLED"]='true'
    $taskEnvironment["GENERATION_${taskKind}_SIZES"]='1280x720,720x1280,960x960'
    $taskEnvironment["GENERATION_${taskKind}_NEGATIVE_PROMPT"]='true'
    $taskEnvironment["GENERATION_${taskKind}_SEED"]='true'
    $taskEnvironment["GENERATION_${taskKind}_MAX_PROMPT_LENGTH"]='2000'
}
$taskEnvironment['GENERATION_AUTHORIZATION_ID']=''; $taskEnvironment['GENERATION_APPROVED_MODELS']=''; $taskEnvironment['GENERATION_MAX_PAID_TASKS']='0'
$taskEnvironment['GENERATION_BUDGET_LIMIT']='0'; $taskEnvironment['GENERATION_RESERVATION_PER_TASK']='0'
if ($Seedance) {
    $taskSeedanceFile = Join-Path $taskLocal 'seedance-video.env'
    if (-not (Test-Path -LiteralPath $taskSeedanceFile)) { throw 'Seedance requires the ignored local seedance-video.env configuration.' }
    foreach ($taskLine in Get-Content -LiteralPath $taskSeedanceFile) {
        # Credentials only: local startup never imports paid submission permissions or budgets.
        if ($taskLine -match '^(SEEDANCE_API_KEY|SEEDANCE_BASE_URL|SEEDANCE_ARTIFACT_HOSTS)=(.*)$') { $taskEnvironment[$Matches[1]]=$Matches[2].Trim() }
    }
    if (-not $taskEnvironment['SEEDANCE_API_KEY'] -or $taskEnvironment['SEEDANCE_BASE_URL'].TrimEnd('/') -ne 'https://ark.cn-beijing.volces.com/api/v3') { throw 'Seedance requires a local Ark key and the official Beijing API endpoint.' }
    $taskEnvironment['GENERATION_PROVIDER']='seedance'
    $taskEnvironment['GENERATION_TEXT_MODEL']='doubao-seedance-2-0-mini-260615'
    $taskEnvironment['GENERATION_IMAGE_MODEL']='doubao-seedance-2-0-mini-260615'
    foreach ($taskKind in @('TEXT','IMAGE')) {
        $taskEnvironment["GENERATION_${taskKind}_SIZES"]='1280x720,720x1280,720x720'
        $taskEnvironment["GENERATION_${taskKind}_NEGATIVE_PROMPT"]='false'
    }
}
$taskEnvironment['STORYBOARD_PAID_ENABLED']='false'; $taskEnvironment['STORYBOARD_API_KEY']=''
$taskEnvironment['STORYBOARD_AUTHORIZATION_ID']=''; $taskEnvironment['STORYBOARD_APPROVED_MODEL']=''; $taskEnvironment['STORYBOARD_MAX_CALLS']='0'
$taskEnvironment['STORYBOARD_BUDGET_LIMIT']='0'; $taskEnvironment['STORYBOARD_RESERVATION_PER_CALL']='0'
$taskSaved = @{}
try {
    foreach ($taskEntry in $taskEnvironment.GetEnumerator()) { $taskSaved[$taskEntry.Key]=[Environment]::GetEnvironmentVariable($taskEntry.Key,'Process'); [Environment]::SetEnvironmentVariable($taskEntry.Key,$taskEntry.Value,'Process') }
    Push-Location $taskRoot
    try {
        # A surviving Java process alone is not proof that its Docker dependencies are ready.
        & docker --host $taskDockerHost compose -p dovideo-aigc-local -f (Join-Path $taskRoot 'docker-compose.aigc.yml') up -d --wait --wait-timeout 180 mysql redis minio
        if ($LASTEXITCODE -ne 0) { throw 'Local infrastructure did not become ready.' }
        # MinIO has no image healthcheck; Compose "running" is not its readiness signal.
        $taskMinioDeadline = [DateTime]::UtcNow.AddSeconds($DependencyReadyTimeoutSeconds)
        $taskMinioReady = $false
        while ([DateTime]::UtcNow -lt $taskMinioDeadline) {
            $taskProbeTimeout = [Math]::Max(1, [Math]::Min(2, [Math]::Ceiling(($taskMinioDeadline - [DateTime]::UtcNow).TotalSeconds)))
            try { $taskProbe = Invoke-WebRequest 'http://127.0.0.1:9002/minio/health/ready' -TimeoutSec $taskProbeTimeout -UseBasicParsing; if ($taskProbe.StatusCode -eq 200) { $taskMinioReady = $true; break } } catch { }
            Start-Sleep -Milliseconds 250
        }
        if (-not $taskMinioReady) { throw 'MinIO did not become ready; the existing app was not reported healthy. Inspect this project container logs.' }
        if ($taskRunningRecord) {
            $taskCurrentProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $($taskRunningRecord.pid)"
            if (-not (Test-AigcJavaProcess $taskCurrentProcess) -or $taskCurrentProcess.CreationDate -ne $taskRunningCreationDate) { throw 'The original app exited or its PID changed during dependency recovery. No unrelated process was stopped; start the app again after checking its logs.' }
            $taskListeners = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
            if ($taskListeners.Count -eq 0 -or @($taskListeners | Where-Object { $_.OwningProcess -ne $taskRunningRecord.pid }).Count -gt 0) { throw 'The requested app port is not owned by the saved Java process; no unrelated process was stopped.' }
            try { $taskExistingResponse = Invoke-WebRequest "http://127.0.0.1:$Port/" -TimeoutSec 5 -UseBasicParsing }
            catch { throw 'Docker dependencies recovered, but the existing app did not respond. Use scripts/stop-aigc.ps1 before starting it again.' }
            if ($taskExistingResponse.StatusCode -ne 200) { throw 'Existing app is not ready; inspect .local/server.log.' }
            Write-Output "AIGC and its Docker dependencies are ready: http://127.0.0.1:$Port/?storyboard"
            return
        }
        Push-Location client
        try {
            if (-not (Test-Path -LiteralPath 'node_modules/vue/package.json')) { & npm.cmd ci; if ($LASTEXITCODE -ne 0) { throw 'Client dependency installation failed.' } }
            & npm.cmd run build; if ($LASTEXITCODE -ne 0) { throw 'Client build failed.' }
        } finally { Pop-Location }
        Push-Location server
        try { & .\mvnw.cmd -B -Paigc-app -DskipTests package; if ($LASTEXITCODE -ne 0) { throw 'AIGC app build failed.' } } finally { Pop-Location }
        if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { throw "Port $Port became occupied before app launch; no unrelated process was stopped." }
        $taskJar = $taskExpectedJar
        $taskStarted = Start-Process -FilePath (Join-Path $JdkHome 'bin/java.exe') -ArgumentList @('-jar',('"'+$taskJar+'"')) -WorkingDirectory $taskRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $taskLocal 'server.log') -RedirectStandardError (Join-Path $taskLocal 'server-error.log') -PassThru
        @{pid=$taskStarted.Id;jar=$taskJar;port=$Port;seedance=[bool]$Seedance;paid=$false} | ConvertTo-Json | Set-Content -LiteralPath $taskPidFile -Encoding utf8
        $taskReady=$false
        for ($taskAttempt=0;$taskAttempt -lt 45;$taskAttempt++) {
            if ($taskStarted.HasExited) { throw 'AIGC app exited; inspect .local/server.log and server-error.log.' }
            try { $taskResponse=Invoke-WebRequest "http://127.0.0.1:$Port/" -TimeoutSec 2 -UseBasicParsing; if($taskResponse.StatusCode -eq 200) { $taskReady=$true; break } } catch { }
            Start-Sleep -Seconds 1
        }
        if (-not $taskReady) { throw 'AIGC app readiness timed out; inspect .local/server.log.' }
        Write-Output "AIGC is running: http://127.0.0.1:$Port/?storyboard"
        Write-Output "Provider=$($taskEnvironment['GENERATION_PROVIDER']); paid calls disabled. Local data persists in the dovideo-aigc-local project."
    } finally { Pop-Location }
} finally { foreach($taskName in $taskSaved.Keys) { [Environment]::SetEnvironmentVariable($taskName,$taskSaved[$taskName],'Process') } }
} finally { foreach ($taskName in $taskDockerSaved.Keys) { [Environment]::SetEnvironmentVariable($taskName, $taskDockerSaved[$taskName], 'Process') } }
