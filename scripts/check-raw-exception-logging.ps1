param(
    [string] $RootPath = (Join-Path $PSScriptRoot "..")
)

$ErrorActionPreference = "Stop"

$scanRoot = Resolve-Path $RootPath
$sourceRoot = Join-Path $scanRoot "src\main\java"
if (-not (Test-Path -LiteralPath $sourceRoot)) {
    exit 0
}

$findings = New-Object System.Collections.Generic.List[string]

Get-ChildItem -LiteralPath $sourceRoot -Recurse -Filter "*.java" | ForEach-Object {
    $relativePath = [System.IO.Path]::GetRelativePath($scanRoot, $_.FullName)
    $lines = [System.IO.File]::ReadAllLines($_.FullName)
    for ($i = 0; $i -lt $lines.Length; $i++) {
        if ($lines[$i] -notmatch "log\.(trace|debug|info|warn|error)\s*\(") {
            continue
        }
        $buffer = ""
        $startLine = $i + 1
        for ($j = $i; $j -lt $lines.Length -and $j -lt $i + 12; $j++) {
            $line = $lines[$j]
            $buffer += " " + $line.Trim()
            if ($line -match "\)\s*;") {
                break
            }
        }
        if ($buffer -match ",\s*(e|ex|t|throwable)\s*\)\s*;") {
            $findings.Add(("{0}:{1}: raw Throwable passed to logger" -f $relativePath, $startLine))
        }
    }
}

if ($findings.Count -gt 0) {
    Write-Host "Raw exception logging is blocked. Use SecretRedactor/safeError and log only redacted strings." -ForegroundColor Red
    $findings | ForEach-Object { Write-Host $_ -ForegroundColor Red }
    exit 1
}
