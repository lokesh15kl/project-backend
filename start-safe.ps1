param(
    [switch]$NoRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$backendDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$envFile = Join-Path $backendDir ".env.local"

if (-not (Test-Path $envFile)) {
    Write-Error "Missing $envFile. Create it from .env.local.example first."
}

function Set-ProcessEnvFromFile {
    param([string]$Path)

    Get-Content -Path $Path | ForEach-Object {
        $line = $_.Trim()
        if ([string]::IsNullOrWhiteSpace($line) -or $line.StartsWith("#")) {
            return
        }

        $match = [regex]::Match($line, '^(?<key>[A-Za-z_][A-Za-z0-9_]*)\s*=\s*(?<value>.*)$')
        if (-not $match.Success) {
            return
        }

        $key = $match.Groups["key"].Value
        $value = $match.Groups["value"].Value.Trim()

        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }

        [Environment]::SetEnvironmentVariable($key, $value, "Process")
    }
}

Set-ProcessEnvFromFile -Path $envFile

if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME) -or -not (Test-Path $env:JAVA_HOME)) {
    $candidates = @(
        "C:\Program Files\Java\jdk-17",
        "C:\Program Files\Java\jdk-17.0.12",
        "C:\Program Files\Java\jdk-21"
    )

    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) {
            $env:JAVA_HOME = $candidate
            break
        }
    }
}

if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME) -or -not (Test-Path $env:JAVA_HOME)) {
    Write-Error "JAVA_HOME is not set. Add JAVA_HOME in .env.local or install JDK 17/21."
}

Write-Host "Loaded secure env from .env.local"
Write-Host "Using JAVA_HOME=$($env:JAVA_HOME)"

if ($NoRun) {
    Write-Host "Dry run complete. Env loaded successfully."
    exit 0
}

Push-Location $backendDir
try {
    & "$backendDir\mvnw.cmd" spring-boot:run
} finally {
    Pop-Location
}
