param(
    [string]$Name = 'milestone-one',
    [string]$BrowserEvidencePath = ''
)

$ErrorActionPreference = 'Stop'
if ($Name -notmatch '^[a-z0-9-]{1,64}$') { throw 'Use a lowercase archive name.' }
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskTarget = Join-Path $taskRoot 'server/target'
$taskReportPath = Join-Path $taskTarget 'generation-infrastructure-acceptance.json'
$taskReport = Get-Content -LiteralPath $taskReportPath -Raw | ConvertFrom-Json
if ($taskReport.result -ne 'PASS' -or $taskReport.modelProvider -ne 'mock' -or $taskReport.paidCalls -ne 0) {
    throw 'A successful, zero-paid-call mock infrastructure report is required.'
}

function Read-TaskSuite([string]$Directory) {
    $taskTotal = 0; $taskFailures = 0; $taskErrors = 0; $taskSkipped = 0
    $taskFiles = @(Get-ChildItem -LiteralPath $Directory -Filter 'TEST-*.xml' -File)
    if ($taskFiles.Count -eq 0) { throw "Missing test results: $Directory" }
    foreach ($taskFile in $taskFiles) {
        [xml]$taskXml = Get-Content -LiteralPath $taskFile.FullName -Raw
        $taskTotal += [int]$taskXml.testsuite.tests
        $taskFailures += [int]$taskXml.testsuite.failures
        $taskErrors += [int]$taskXml.testsuite.errors
        $taskSkipped += [int]$taskXml.testsuite.skipped
    }
    if ($taskFailures -or $taskErrors -or $taskSkipped) { throw 'Only fully passing, non-skipped runs may be archived.' }
    return [ordered]@{ tests = $taskTotal; failures = $taskFailures; errors = $taskErrors; skipped = $taskSkipped }
}
$taskUnit = Read-TaskSuite (Join-Path $taskTarget 'surefire-reports')
$taskIntegration = Read-TaskSuite (Join-Path $taskTarget 'failsafe-reports')
$taskClientLog = Get-Content -LiteralPath (Join-Path $taskTarget ($Name + '-client-tests.log')) -Raw
$taskClientBuild = Get-Content -LiteralPath (Join-Path $taskTarget ($Name + '-client-build.log')) -Raw
if ($taskClientLog -notmatch 'tests (\d+)' ) { throw 'Missing client test count.' }
$taskClientCount = [int]$Matches[1]
if ($taskClientLog -notmatch 'fail 0' -or $taskClientLog -notmatch 'skipped 0' -or $taskClientBuild -notmatch 'built in') {
    throw 'Client tests and build must pass before archiving.'
}

# A source change after verification must be checked again. Do not relabel an earlier run as current.
$taskServerSources = @(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'server/src') -Recurse -File)
$taskServerSources += Get-Item -LiteralPath (Join-Path $taskRoot 'server/pom.xml')
$taskServerVerifiedAt = (Get-Item -LiteralPath $taskReportPath).LastWriteTimeUtc
if ($taskServerSources | Where-Object { $_.LastWriteTimeUtc -gt $taskServerVerifiedAt }) { throw 'Server source is newer than its acceptance result.' }
$taskClientSources = @(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'client/src') -Recurse -File)
$taskClientSources += Get-Item -LiteralPath (Join-Path $taskRoot 'client/vite.config.js'), (Join-Path $taskRoot 'client/package.json'), (Join-Path $taskRoot 'client/package-lock.json')
$taskClientSources += Get-Item -LiteralPath (Join-Path $taskRoot 'client/index.html')
if (Test-Path -LiteralPath (Join-Path $taskRoot 'client/public')) { $taskClientSources += Get-ChildItem -LiteralPath (Join-Path $taskRoot 'client/public') -Recurse -File }
$taskClientVerifiedAt = (Get-Item -LiteralPath (Join-Path $taskTarget ($Name + '-client-build.log'))).LastWriteTimeUtc
if ($taskClientSources | Where-Object { $_.LastWriteTimeUtc -gt $taskClientVerifiedAt }) { throw 'Client source is newer than its build result.' }

$taskHead = (& git -C $taskRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot read source baseline.' }
$taskChanged = @(& git -C $taskRoot -c core.quotepath=false diff --name-only HEAD)
if ($LASTEXITCODE -ne 0) { throw 'Cannot read source changes.' }
$taskChanged += @(& git -C $taskRoot -c core.quotepath=false ls-files --others --exclude-standard)
if ($LASTEXITCODE -ne 0) { throw 'Cannot read new source files.' }
$taskManifest = @($taskChanged | Sort-Object -Unique | Where-Object { $_ -and -not $_.StartsWith('docs/acceptance/') } | ForEach-Object {
    $taskFilePath = Join-Path $taskRoot $_
    $taskDigest = if (Test-Path -LiteralPath $taskFilePath -PathType Leaf) { (Get-FileHash -LiteralPath $taskFilePath -Algorithm SHA256).Hash.ToLowerInvariant() } else { 'DELETED' }
    [ordered]@{ path = $_; sha256 = $taskDigest }
})
$taskHasher = [Security.Cryptography.SHA256]::Create()
try {
    $taskFingerprintBytes = $taskHasher.ComputeHash([Text.Encoding]::UTF8.GetBytes(($taskManifest | ConvertTo-Json -Depth 5 -Compress)))
    $taskFingerprint = [BitConverter]::ToString($taskFingerprintBytes).Replace('-', '').ToLowerInvariant()
} finally { $taskHasher.Dispose() }
$taskBrowser = $null
if ($BrowserEvidencePath) {
    $taskBrowser = Get-Content -LiteralPath $BrowserEvidencePath -Raw | ConvertFrom-Json
    if ($taskBrowser.result -ne 'PASS' -or $taskBrowser.provider -ne 'mock' -or $taskBrowser.paidCalls -ne 0) { throw 'Only passing local Mock browser evidence may be archived.' }
}
$taskMedia = $null
if ($Name -eq 'complete') {
    $taskVideo = Join-Path $taskTarget 'final-demo.mp4'
    $taskMetadata = Join-Path $taskTarget 'final-demo-metadata.json'
    if (-not (Test-Path -LiteralPath $taskVideo) -or -not (Test-Path -LiteralPath $taskMetadata)) { throw 'Missing final FFmpeg demonstration artifact.' }
    $taskMedia = [ordered]@{ path = 'mock-film.mp4'; sha256 = (Get-FileHash -LiteralPath $taskVideo -Algorithm SHA256).Hash.ToLowerInvariant(); bytes = (Get-Item -LiteralPath $taskVideo).Length; metadata = (Get-Content -LiteralPath $taskMetadata -Raw | ConvertFrom-Json); inputProvider = 'mock'; renderer = 'real-ffmpeg' }
}
$taskLimits = @('No real model invocation, real model quality evaluation or provider billing reconciliation', 'Original RocketMQ/Qdrant/ASR analysis services not exercised')
if (-not $taskBrowser) { $taskLimits += 'Authenticated browser workflow not exercised; API and Vue state behavior verified through HTTP and frontend tests' }
$taskArchive = [ordered]@{
    recordedAtUtc = [DateTime]::UtcNow.ToString('o')
    source = [ordered]@{ baselineCommit = $taskHead; state = 'working-tree'; changedFileFingerprint = $taskFingerprint; manifest = $taskManifest }
    verification = [ordered]@{ serverUnit = $taskUnit; realInfrastructure = $taskIntegration; clientTests = $taskClientCount; clientBuild = 'PASS' }
    infrastructure = $taskReport
    browser = $taskBrowser
    demonstration = $taskMedia
    limitations = $taskLimits
}
$taskArchiveDirectory = Join-Path $taskRoot 'docs/acceptance'
New-Item -ItemType Directory -Path $taskArchiveDirectory -Force | Out-Null
if ($taskMedia) { Copy-Item -LiteralPath $taskVideo -Destination (Join-Path $taskArchiveDirectory 'mock-film.mp4'); Copy-Item -LiteralPath $taskMetadata -Destination (Join-Path $taskArchiveDirectory 'mock-film-metadata.json') }
$taskArchiveFile = Join-Path $taskArchiveDirectory ($Name + '.json')
$taskArchive | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $taskArchiveFile -Encoding utf8
Write-Output "Archived acceptance: $taskArchiveFile"
