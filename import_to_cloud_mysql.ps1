param(
    [Parameter(Mandatory = $true)]
    [string]$Host,

    [Parameter(Mandatory = $true)]
    [int]$Port,

    [Parameter(Mandatory = $true)]
    [string]$Database,

    [Parameter(Mandatory = $true)]
    [string]$User,

    [string]$DumpFile = "C:\Users\bhaga\OneDrive\Desktop\frontend\backend\full_project_dump.sql"
)

$mysqlExe = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"

if (-not (Test-Path $mysqlExe)) {
    throw "mysql.exe not found at $mysqlExe"
}

if (-not (Test-Path $DumpFile)) {
    throw "Dump file not found: $DumpFile"
}

if (-not $env:DB_PASS) {
    throw "Set DB_PASS environment variable before running this script."
}

Write-Host "Importing $DumpFile to $Host:$Port/$Database ..."
Get-Content -Raw $DumpFile | & $mysqlExe -h $Host -P $Port -u $User --password="$env:DB_PASS" $Database

if ($LASTEXITCODE -ne 0) {
    throw "Import failed with exit code $LASTEXITCODE"
}

Write-Host "Import completed successfully."
