param([switch]$NoBrowser,[switch]$LocalOnly)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$bidJavaCandidates = @()
if ($env:JAVA_HOME) { $bidJavaCandidates += Join-Path $env:JAVA_HOME 'bin/java.exe' }
$bidJavaCommand = Get-Command java -ErrorAction SilentlyContinue
if ($bidJavaCommand) { $bidJavaCandidates += $bidJavaCommand.Source }
$bidJava = $bidJavaCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $bidJava) { throw 'Java 8 or newer is required. Set JAVA_HOME and try again.' }
$bidJar = Join-Path $PSScriptRoot 'backend/target/bidcheck-0.1.0.jar'
if (-not (Test-Path -LiteralPath $bidJar)) { throw 'Build the application first: mvn -f backend/pom.xml package' }
$bidPort = if ($env:BIDCHECK_PORT) { $env:BIDCHECK_PORT } else { '8787' }
$bidUrl = 'http://127.0.0.1:' + $bidPort
$bidExisting = $false
try { $bidHealth = Invoke-RestMethod -Uri ($bidUrl + '/api/health') -TimeoutSec 2; $bidExisting = ($bidHealth.status -eq 'UP' -and $bidHealth.version -eq '0.1.0') } catch { }
if ($bidExisting) { if (-not $NoBrowser) { Start-Process $bidUrl }; Write-Host ('BidCheck is already available at ' + $bidUrl); exit 0 }
if (-not (Test-Path -LiteralPath '.local')) { New-Item -ItemType Directory -Path '.local' | Out-Null }
function Read-BidCheckSecret([string]$Path) {
    $bidSecure = (Get-Content -LiteralPath $Path -Raw).Trim() | ConvertTo-SecureString
    return [System.Net.NetworkCredential]::new('', $bidSecure).Password
}
$bidPreviousEnvironment = @{}
try {
    $bidEnvironment = @{}
    if (-not $LocalOnly -and (Test-Path -LiteralPath '.local/runtime-env.dpapi')) {
        $bidConfig = Read-BidCheckSecret '.local/runtime-env.dpapi' | ConvertFrom-Json
        foreach ($bidProperty in $bidConfig.PSObject.Properties) { $bidEnvironment[$bidProperty.Name] = [string]$bidProperty.Value }
    }
    if (-not $LocalOnly -and (Test-Path -LiteralPath '.local/model-key.dpapi')) {
        $bidEnvironment['BIDCHECK_MODEL_KEY'] = Read-BidCheckSecret '.local/model-key.dpapi'
    }
    foreach ($bidName in $bidEnvironment.Keys) {
        $bidPreviousEnvironment[$bidName] = [Environment]::GetEnvironmentVariable($bidName, 'Process')
        [Environment]::SetEnvironmentVariable($bidName, $bidEnvironment[$bidName], 'Process')
    }
    $bidRuntimeDirectory = Join-Path $PSScriptRoot '.local/runtime'
    New-Item -ItemType Directory -Path $bidRuntimeDirectory -Force | Out-Null
    $bidRuntimeJar = Join-Path $bidRuntimeDirectory 'bidcheck.jar'
    Copy-Item -LiteralPath $bidJar -Destination $bidRuntimeJar -Force
    $bidProcess = Start-Process -FilePath $bidJava -ArgumentList @('-Xmx768m','-jar',('"' + $bidRuntimeJar + '"')) -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput '.local/server.log' -RedirectStandardError '.local/server-error.log'
} finally {
    foreach ($bidName in $bidPreviousEnvironment.Keys) { [Environment]::SetEnvironmentVariable($bidName, $bidPreviousEnvironment[$bidName], 'Process') }
}
$bidProcess.Id | Set-Content -LiteralPath '.local/server.pid'
for ($bidAttempt = 0; $bidAttempt -lt 45; $bidAttempt++) {
    Start-Sleep -Seconds 1
    if ($bidProcess.HasExited) { throw 'BidCheck could not start. See .local/server.log and .local/server-error.log.' }
    try { $bidHealth = Invoke-RestMethod -Uri ($bidUrl + '/api/health') -TimeoutSec 2; if ($bidHealth.status -eq 'UP') { if (-not $NoBrowser) { Start-Process $bidUrl }; Write-Host ('BidCheck is ready: ' + $bidUrl); exit 0 } } catch { }
}
throw 'Startup is taking longer than expected. See .local/server.log.'
