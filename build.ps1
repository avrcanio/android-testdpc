# Stop execution on any error
$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$BazelCacheDir = Join-Path $ScriptDir ".bazelisk"
$BazelExe = Join-Path $BazelCacheDir "bazel.exe"

function Ensure-Bazel {
    $existing = Get-Command bazel -ErrorAction SilentlyContinue
    if ($existing) {
        return $existing.Source
    }

    if (Test-Path $BazelExe) {
        return $BazelExe
    }

    New-Item -Path $BazelCacheDir -ItemType Directory -Force | Out-Null
    $uri = "https://github.com/bazelbuild/bazelisk/releases/download/v1.20.0/bazelisk-windows-amd64.exe"
    Write-Host "Bazel not found. Downloading Bazelisk from $uri ..."
    Invoke-WebRequest -Uri $uri -OutFile $BazelExe
    return $BazelExe
}

$BazelCmd = Ensure-Bazel

if (-not $env:BAZEL_SH) {
    $gitBash = "C:\Program Files\Git\bin\bash.exe"
    if (Test-Path $gitBash) {
        $env:BAZEL_SH = $gitBash
        Write-Host "Using bash from: $env:BAZEL_SH"
    }
    else {
        Write-Warning "BAZEL_SH not set and Git Bash not found. Bazel actions that require bash may fail."
    }
}
else {
    Write-Host "Using existing BAZEL_SH: $env:BAZEL_SH"
}

& $BazelCmd --version
& $BazelCmd build testdpc
