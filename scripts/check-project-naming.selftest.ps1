$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$scriptPath = Join-Path $repoRoot "scripts\check-project-naming.ps1"
$sandbox = Join-Path ([System.IO.Path]::GetTempPath()) ("jimuqu-naming-check-selftest-" + [Guid]::NewGuid().ToString("N"))
$blockedFixture = "BLOCKED_PROJECT_NAME_ALLOW_PRIVATE_URLS"

function Invoke-NamingCheck {
    $output = & pwsh -NoProfile -ExecutionPolicy Bypass -File $scriptPath -RootPath $sandbox -ExtraBlockedTerms $blockedFixture 2>&1
    return @{
        ExitCode = $LASTEXITCODE
        Output = ($output | Out-String)
    }
}

function Reset-Sandbox {
    if (Test-Path -LiteralPath $sandbox) {
        Remove-Item -LiteralPath $sandbox -Recurse -Force
    }
    New-Item -ItemType Directory -Path $sandbox | Out-Null
}

Push-Location $repoRoot
try {
    Reset-Sandbox
    New-Item -ItemType Directory -Path (Join-Path $sandbox "src") | Out-Null
    Set-Content -Path (Join-Path $sandbox "src\config.txt") -Value ($blockedFixture + "=true") -Encoding UTF8
    $blocked = Invoke-NamingCheck
    if ($blocked.ExitCode -eq 0) {
        throw "Naming check did not block a forbidden legacy environment variable."
    }

    Reset-Sandbox
    New-Item -ItemType Directory -Path (Join-Path $sandbox "web\node_modules\fixture") -Force | Out-Null
    Set-Content -Path (Join-Path $sandbox "web\node_modules\fixture\README.md") -Value ("Third-party text may mention " + $blockedFixture + ".") -Encoding UTF8
    $ignored = Invoke-NamingCheck
    if ($ignored.ExitCode -ne 0) {
        throw "Naming check should ignore third-party dependency directories, but failed: $($ignored.Output)"
    }
} finally {
    Pop-Location
    if (Test-Path -LiteralPath $sandbox) {
        Remove-Item -LiteralPath $sandbox -Recurse -Force
    }
}
