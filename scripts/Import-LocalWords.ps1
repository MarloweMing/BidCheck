#Requires -Version 5.1
<#
.SYNOPSIS
Imports the explicitly supplied Words 19.7 JAR into this project's isolated Maven repository.
.DESCRIPTION
Checks SHA-256 and BuildVersionInfo in an isolated Java class loader. It never
applies a license, patches a class, edits the input JAR, builds or restarts BidCheck.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string] $SourceJar,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-fA-F]{64}$')]
    [string] $ExpectedSha256,
    [ValidateSet('19.7')]
    [string] $ExpectedVersion = '19.7',
    [string] $RepositoryPath,
    [string] $JavaHome
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$wordsWorkspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$wordsTemporaryRoot = [IO.Path]::GetFullPath((Join-Path $wordsWorkspace '.local/words-import'))
$wordsUtf8 = New-Object Text.UTF8Encoding($false)

function Assert-Descendant([string] $Path, [string] $Root) {
    $candidate = [IO.Path]::GetFullPath($Path)
    $prefix = [IO.Path]::GetFullPath($Root).TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (-not $candidate.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Output must remain inside the intended project directory.'
    }
    return $candidate
}

function Quote-NativeArgument([string] $Value) {
    # Windows CommandLineToArgvW / CRT quoting. No shell interprets these values.
    if ($Value -notmatch '[\s"]' -and $Value.Length -gt 0) { return $Value }
    $escaped = [regex]::Replace($Value, '(\\*)"', '$1$1\"')
    $escaped = [regex]::Replace($escaped, '(\\+)$', '$1$1')
    return '"' + $escaped + '"'
}

function Invoke-BoundedJava([string] $Executable, [string[]] $Arguments, [int] $TimeoutSeconds) {
    $start = New-Object Diagnostics.ProcessStartInfo
    $start.FileName = $Executable
    $start.Arguments = (($Arguments | ForEach-Object { Quote-NativeArgument $_ }) -join ' ')
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $start
    try {
        if (-not $process.Start()) { throw 'Could not start the Java verification process.' }
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            $process.Kill()
            $process.WaitForExit()
            throw 'Java verification timed out; no artifact was imported.'
        }
        $text = $stdout.GetAwaiter().GetResult()
        $errors = $stderr.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) {
            # Avoid echoing arbitrary vendor output or the environment into logs.
            throw "Java verification failed (exit $($process.ExitCode)); no artifact was imported."
        }
        if ($text.Length -gt 4096 -or $errors.Length -gt 16384) {
            throw 'Unexpected verification output; no artifact was imported.'
        }
        return $text
    }
    finally { $process.Dispose() }
}

if ([string]::IsNullOrWhiteSpace($RepositoryPath)) {
    $RepositoryPath = Join-Path $wordsWorkspace '.local/m2'
}
$wordsRepository = Assert-Descendant $RepositoryPath $wordsWorkspace
$wordsReadOnlyRoot = [IO.Path]::GetFullPath((Join-Path $wordsWorkspace 'sources'))
if ($wordsRepository.Equals($wordsReadOnlyRoot, [StringComparison]::OrdinalIgnoreCase) -or
    $wordsRepository.StartsWith($wordsReadOnlyRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'The synced sources directory is read-only.'
}
$wordsSource = (Get-Item -LiteralPath $SourceJar).FullName
if (-not [IO.File]::Exists($wordsSource)) { throw 'The source JAR does not exist.' }

if ([string]::IsNullOrWhiteSpace($JavaHome)) { $JavaHome = $env:JAVA_HOME }
if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    $compiler = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($null -eq $compiler) { throw 'Provide -JavaHome pointing to a JDK 8 or later.' }
    $JavaHome = Split-Path -Parent (Split-Path -Parent $compiler.Source)
}
$wordsJava = Join-Path ([IO.Path]::GetFullPath($JavaHome)) 'bin/java.exe'
$wordsJavac = Join-Path ([IO.Path]::GetFullPath($JavaHome)) 'bin/javac.exe'
if (-not [IO.File]::Exists($wordsJava) -or -not [IO.File]::Exists($wordsJavac)) {
    throw 'JavaHome must contain java.exe and javac.exe from JDK 8 or later.'
}

[IO.Directory]::CreateDirectory($wordsTemporaryRoot) | Out-Null
$wordsStaging = Assert-Descendant (Join-Path $wordsTemporaryRoot ([Guid]::NewGuid().ToString('N'))) $wordsTemporaryRoot
[IO.Directory]::CreateDirectory($wordsStaging) | Out-Null
$wordsStream = $null
try {
    # Holding this read-only handle prevents concurrent overwrite while checking/copying.
    $wordsStream = [IO.File]::Open($wordsSource, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    if ($wordsStream.Length -lt 1024 -or $wordsStream.Length -gt 256MB) { throw 'Unexpected SDK file size.' }
    $hasher = [Security.Cryptography.SHA256]::Create()
    try { $wordsHash = ([BitConverter]::ToString($hasher.ComputeHash($wordsStream))).Replace('-', '').ToLowerInvariant() }
    finally { $hasher.Dispose(); $wordsStream.Position = 0 }
    if ($wordsHash -ne $ExpectedSha256.ToLowerInvariant()) { throw 'Source SHA-256 does not match the explicitly expected file.' }

    $probeCode = @'
import java.net.URLClassLoader;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
public final class WordsVersionProbe {
    public static void main(String[] args) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{Paths.get(args[0]).toUri().toURL()}, null)) {
            Class<?> type = Class.forName("com.aspose.words.BuildVersionInfo", false, loader);
            String version = (String)type.getMethod("getVersion").invoke(null);
            String product = (String)type.getMethod("getProduct").invoke(null);
            System.out.println("VERSION=" + Base64.getEncoder().encodeToString(version.getBytes(StandardCharsets.UTF_8)));
            System.out.println("PRODUCT=" + Base64.getEncoder().encodeToString(product.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
'@
    $probeSource = Join-Path $wordsStaging 'WordsVersionProbe.java'
    [IO.File]::WriteAllText($probeSource, $probeCode, $wordsUtf8)
    Invoke-BoundedJava $wordsJavac @('-encoding', 'UTF-8', '-source', '8', '-target', '8', '-d', $wordsStaging, $probeSource) 30 | Out-Null
    $probeOutput = Invoke-BoundedJava $wordsJava @('-Dfile.encoding=UTF-8', '-cp', $wordsStaging, 'WordsVersionProbe', $wordsSource) 20
    $probeLines = @($probeOutput.Trim() -split '\r?\n')
    if ($probeLines.Count -ne 2 -or $probeLines[0] -notmatch '^VERSION=([A-Za-z0-9+/=]+)$') { throw 'BuildVersionInfo returned an unexpected response.' }
    $wordsActualVersion = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($Matches[1]))
    if ($probeLines[1] -notmatch '^PRODUCT=([A-Za-z0-9+/=]+)$') { throw 'BuildVersionInfo product is unavailable.' }
    $wordsProduct = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($Matches[1]))
    if ($wordsActualVersion -notmatch ('^' + [regex]::Escape($ExpectedVersion) + '(\.0)*$') -or $wordsProduct -notmatch '^Aspose\.Words') {
        throw "The actual SDK version is not the supported $ExpectedVersion Words SDK."
    }

    $wordsVersion = $ExpectedVersion + '-' + $wordsHash.Substring(0, 12)
    $wordsArtifactId = 'aspose-words-local'
    $wordsArtifactName = $wordsArtifactId + '-' + $wordsVersion
    $wordsTarget = Assert-Descendant (Join-Path $wordsRepository ('com/bidcheck/vendor/' + $wordsArtifactId + '/' + $wordsVersion)) $wordsRepository
    $wordsTargetJar = Join-Path $wordsTarget ($wordsArtifactName + '-jdk17.jar')
    $wordsPomName = $wordsArtifactName + '.pom'
    $wordsPom = @"
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.bidcheck.vendor</groupId>
  <artifactId>aspose-words-local</artifactId>
  <version>$wordsVersion</version>
  <packaging>jar</packaging>
  <name>Locally supplied Words SDK; no license is included or applied</name>
</project>
"@
    $record = [ordered]@{
        groupId = 'com.bidcheck.vendor'; artifactId = $wordsArtifactId; version = $wordsVersion; classifier = 'jdk17'
        actualVersion = $wordsActualVersion; product = $wordsProduct; sha256 = $wordsHash
        sourceFileName = [IO.Path]::GetFileName($wordsSource); bytes = $wordsStream.Length
        licenseApplied = $false; licenseIncludedByScript = $false
    }
    if ([IO.Directory]::Exists($wordsTarget)) {
        if (-not [IO.File]::Exists($wordsTargetJar) -or -not [IO.File]::Exists((Join-Path $wordsTarget $wordsPomName))) {
            throw 'The target coordinate already exists but is incomplete; it was not overwritten.'
        }
        if ((Get-FileHash -LiteralPath $wordsTargetJar -Algorithm SHA256).Hash.ToLowerInvariant() -ne $wordsHash -or
            [IO.File]::ReadAllText((Join-Path $wordsTarget $wordsPomName)) -ne $wordsPom) {
            throw 'The target coordinate already contains different bytes or metadata; it was not overwritten.'
        }
    }
    else {
        $artifactStage = Join-Path $wordsStaging 'artifact'
        [IO.Directory]::CreateDirectory($artifactStage) | Out-Null
        $stagedJar = Join-Path $artifactStage ($wordsArtifactName + '-jdk17.jar')
        $destination = [IO.File]::Open($stagedJar, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
        try { $wordsStream.CopyTo($destination); $destination.Flush() } finally { $destination.Dispose() }
        if ((Get-FileHash -LiteralPath $stagedJar -Algorithm SHA256).Hash.ToLowerInvariant() -ne $wordsHash) { throw 'Copied artifact integrity check failed.' }
        [IO.File]::WriteAllText((Join-Path $artifactStage $wordsPomName), $wordsPom, $wordsUtf8)
        [IO.File]::WriteAllText((Join-Path $artifactStage 'local-words-import.json'), ($record | ConvertTo-Json -Depth 4), $wordsUtf8)
        [IO.File]::WriteAllText((Join-Path $artifactStage '_remote.repositories'), "$wordsArtifactName-jdk17.jar>=`n$wordsPomName>=`n", $wordsUtf8)
        [IO.Directory]::CreateDirectory((Split-Path -Parent $wordsTarget)) | Out-Null
        [IO.Directory]::Move($artifactStage, $wordsTarget)
    }
    if ((Get-FileHash -LiteralPath $wordsTargetJar -Algorithm SHA256).Hash.ToLowerInvariant() -ne $wordsHash) { throw 'Final artifact integrity check failed.' }
    [pscustomobject]@{
        ActualVersion = $wordsActualVersion; Sha256 = $wordsHash
        Coordinate = "com.bidcheck.vendor:aspose-words-local:${wordsVersion}:jdk17"
        Version = $wordsVersion; RepositoryPath = $wordsRepository; ArtifactPath = $wordsTargetJar
        LicenseState = 'UNCHANGED_REQUIRES_SEPARATE_LICENSE'
    }
}
finally {
    if ($null -ne $wordsStream) { $wordsStream.Dispose() }
    # Delete only this script's verified GUID staging directory, never the source/repository.
    $cleanup = Assert-Descendant $wordsStaging $wordsTemporaryRoot
    if ([IO.Directory]::Exists($cleanup)) { Remove-Item -LiteralPath $cleanup -Recurse -Force }
}
