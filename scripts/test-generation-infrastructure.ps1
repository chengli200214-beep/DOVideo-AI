param(
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$FfmpegDir = $env:FFMPEG_DIR,
    [switch]$IntegrationOnly
)

$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskComposeFile = Join-Path $taskRoot 'docker-compose.generation-it.yml'
$taskProject = 'dovideo-generation-it-' + [Guid]::NewGuid().ToString('N').Substring(0, 12)
$taskNames = @('JAVA_HOME', 'GENERATION_IT_FFMPEG_DIR', 'GENERATION_IT_DB_PASSWORD', 'GENERATION_IT_REDIS_PASSWORD',
    'GENERATION_IT_MINIO_PASSWORD', 'GENERATION_IT_DB_URL', 'GENERATION_IT_REDIS_PORT', 'GENERATION_IT_MINIO_URL',
    'GENERATION_PROVIDER', 'GENERATION_PAID_ENABLED', 'SEEDANCE_RECOVERY_ENABLED',
    'STORYBOARD_PAID_ENABLED', 'SEEDANCE_API_KEY', 'STORYBOARD_API_KEY', 'DEEPSEEK_API_KEY',
    'ASR_ENABLED', 'ASR_API_KEY', 'EMBEDDING_ENABLED', 'EMBEDDING_API_KEY')
$taskSavedEnvironment = @{}
$taskComposeStarted = $false
foreach ($taskName in $taskNames) {
    $taskSavedEnvironment[$taskName] = [Environment]::GetEnvironmentVariable($taskName, 'Process')
}

function Invoke-TaskCompose {
    & docker compose -p $taskProject -f $taskComposeFile @args
    if ($LASTEXITCODE -ne 0) { throw 'Acceptance Docker Compose command failed.' }
}

function Get-TaskPort([string]$Service, [int]$Port) {
    $taskPortOutput = Invoke-TaskCompose port $Service $Port
    $taskPortMatch = [regex]::Match(($taskPortOutput -join ''), '^127\.0\.0\.1:(\d+)$')
    if (-not $taskPortMatch.Success) { throw "Cannot resolve local port for $Service." }
    return $taskPortMatch.Groups[1].Value
}

try {
    if (-not $JdkHome -or -not (Test-Path -LiteralPath (Join-Path $JdkHome 'bin/java.exe'))) {
        throw 'Pass -JdkHome with an installed JDK 21 or newer.'
    }
    $env:JAVA_HOME = $JdkHome
    if (-not $FfmpegDir) {
        $taskFfmpeg = Get-ChildItem -LiteralPath (Join-Path $taskRoot '.tools') -Recurse -File -Filter ffmpeg.exe -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($taskFfmpeg) { $FfmpegDir = $taskFfmpeg.Directory.FullName }
    }
    if (-not $FfmpegDir) { throw 'Pass -FfmpegDir containing ffmpeg and ffprobe for real composition acceptance.' }
    $FfmpegDir = (Resolve-Path -LiteralPath $FfmpegDir).Path
    foreach ($taskTool in @('ffmpeg.exe', 'ffprobe.exe')) {
        if (-not (Test-Path -LiteralPath (Join-Path $FfmpegDir $taskTool))) { throw 'FfmpegDir must contain both ffmpeg.exe and ffprobe.exe.' }
    }
    $env:GENERATION_IT_FFMPEG_DIR = $FfmpegDir
    # Match the Linux harness: never inherit paid runtime settings or credentials into acceptance.
    $env:GENERATION_PROVIDER = 'mock'
    $env:GENERATION_PAID_ENABLED = 'false'
    $env:SEEDANCE_RECOVERY_ENABLED = 'false'
    $env:STORYBOARD_PAID_ENABLED = 'false'
    $env:ASR_ENABLED = 'false'
    $env:EMBEDDING_ENABLED = 'false'
    foreach ($taskCredential in @('SEEDANCE_API_KEY', 'STORYBOARD_API_KEY', 'DEEPSEEK_API_KEY', 'ASR_API_KEY', 'EMBEDDING_API_KEY')) {
        [Environment]::SetEnvironmentVariable($taskCredential, $null, 'Process')
    }
    & docker info --format '{{.ServerVersion}}'
    if ($LASTEXITCODE -ne 0) { throw 'Docker engine is unavailable. Start Docker Desktop and retry.' }
    # These values exist only in this process and the disposable containers.
    $env:GENERATION_IT_DB_PASSWORD = [Guid]::NewGuid().ToString('N')
    $env:GENERATION_IT_REDIS_PASSWORD = [Guid]::NewGuid().ToString('N')
    $env:GENERATION_IT_MINIO_PASSWORD = [Guid]::NewGuid().ToString('N')
    Write-Host "Starting isolated acceptance project: $taskProject"
    $taskComposeStarted = $true
    Invoke-TaskCompose up -d --wait --wait-timeout 180
    $taskMysqlPort = Get-TaskPort mysql 3306
    $env:GENERATION_IT_DB_URL = "jdbc:mysql://127.0.0.1:$taskMysqlPort/generation_it?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
    $env:GENERATION_IT_REDIS_PORT = Get-TaskPort redis 6379
    $taskMinioPort = Get-TaskPort minio 9000
    $env:GENERATION_IT_MINIO_URL = "http://127.0.0.1:$taskMinioPort"
    # MinIO's container has no curl dependency; probe readiness from this host.
    $taskReady = $false
    for ($taskAttempt = 0; $taskAttempt -lt 30; $taskAttempt++) {
        try {
            $taskResponse = Invoke-WebRequest -Uri "$env:GENERATION_IT_MINIO_URL/minio/health/ready" -TimeoutSec 2 -UseBasicParsing
            if ($taskResponse.StatusCode -eq 200) { $taskReady = $true; break }
        } catch { }
        Start-Sleep -Seconds 1
    }
    if (-not $taskReady) { throw 'Acceptance MinIO did not become ready.' }
    Push-Location (Join-Path $taskRoot 'server')
    try {
        if ($IntegrationOnly) {
            # Refresh main/test classes and resources, then execute only the real infrastructure IT.
            & .\mvnw.cmd -B -Pgeneration-integration '-Dit.test=GenerationInfrastructureIT' test-compile failsafe:integration-test failsafe:verify
        } else {
            & .\mvnw.cmd -B -Pgeneration-integration verify
        }
        if ($LASTEXITCODE -ne 0) { throw 'Generation infrastructure acceptance failed; inspect server/target/failsafe-reports.' }
    } finally { Pop-Location }
} finally {
    # Only this run's random project is removed. Production compose volumes are never targeted.
    try {
        if ($taskComposeStarted) {
            & docker compose -p $taskProject -f $taskComposeFile down --volumes --remove-orphans 2>&1 | Out-Host
            if ($LASTEXITCODE -ne 0) { Write-Warning "Cleanup did not complete for acceptance project $taskProject." }
        }
    } finally {
        foreach ($taskName in $taskNames) {
            [Environment]::SetEnvironmentVariable($taskName, $taskSavedEnvironment[$taskName], 'Process')
        }
    }
}
