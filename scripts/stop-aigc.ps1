param([switch]$StopInfrastructure)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskFile=Join-Path $taskRoot '.local/aigc-process.json'
if(Test-Path -LiteralPath $taskFile) {
    $taskRecord=Get-Content -LiteralPath $taskFile -Raw | ConvertFrom-Json
    $taskExpectedJar=[IO.Path]::GetFullPath((Join-Path $taskRoot 'server/target/server-0.0.1-SNAPSHOT.jar'))
    if($taskRecord.jar -ne $taskExpectedJar) { throw 'Saved process does not belong to this workspace.' }
    $taskProcess=Get-CimInstance Win32_Process -Filter "ProcessId = $($taskRecord.pid)"
    if($taskProcess -and $taskProcess.CommandLine.Contains($taskExpectedJar)) { Stop-Process -Id $taskRecord.pid }
    Remove-Item -LiteralPath $taskFile
}
if($StopInfrastructure) {
    # Stop this named local project only; no volume or data is deleted.
    $taskSaved=@{}
    try {
        foreach($taskLine in Get-Content (Join-Path $taskRoot '.local/aigc.env')) { if($taskLine -match '^(AIGC_[A-Z_]+)=(.+)$') { $taskSaved[$Matches[1]]=[Environment]::GetEnvironmentVariable($Matches[1],'Process'); [Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process') } }
        & docker compose -p dovideo-aigc-local -f (Join-Path $taskRoot 'docker-compose.aigc.yml') stop
        if($LASTEXITCODE -ne 0) { throw 'Local container stop failed.' }
    } finally { foreach($taskName in $taskSaved.Keys) { [Environment]::SetEnvironmentVariable($taskName,$taskSaved[$taskName],'Process') } }
}
