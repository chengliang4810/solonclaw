$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$scriptPath = Join-Path $repoRoot "scripts\check-project-naming.ps1"
$sandbox = Join-Path ([System.IO.Path]::GetTempPath()) ("jimuqu-naming-check-selftest-" + [Guid]::NewGuid().ToString("N"))
$blockedFixture = "BLOCKED_PROJECT_NAME_ALLOW_PRIVATE_URLS"
$legacyPrefix = -join ([char]72, [char]69, [char]82, [char]77, [char]69, [char]83)
$legacyEnv = $legacyPrefix + "_ALLOW_PRIVATE_URLS"
$legacyProduct = -join ([char]79, [char]112, [char]101, [char]110, [char]67, [char]108, [char]97, [char]119)
$legacyLower = ($legacyProduct.Substring(0, 4).ToLowerInvariant() + "-" + $legacyProduct.Substring(4).ToLowerInvariant())

function Invoke-NamingCheck {
    param([switch] $WithExtraFixture)

    $args = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $scriptPath, "-RootPath", $sandbox)
    if ($WithExtraFixture) {
        $args += @("-ExtraBlockedTerms", $blockedFixture)
    }
    $output = & pwsh @args 2>&1
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
    $blocked = Invoke-NamingCheck -WithExtraFixture
    if ($blocked.ExitCode -eq 0) {
        throw "Naming check did not block a forbidden legacy environment variable."
    }

    Reset-Sandbox
    New-Item -ItemType Directory -Path (Join-Path $sandbox "web\node_modules\fixture") -Force | Out-Null
    Set-Content -Path (Join-Path $sandbox "web\node_modules\fixture\README.md") -Value ("Third-party text may mention " + $blockedFixture + ".") -Encoding UTF8
    $ignored = Invoke-NamingCheck -WithExtraFixture
    if ($ignored.ExitCode -ne 0) {
        throw "Naming check should ignore third-party dependency directories, but failed: $($ignored.Output)"
    }

    Reset-Sandbox
    New-Item -ItemType Directory -Path (Join-Path $sandbox "src") | Out-Null
    Set-Content -Path (Join-Path $sandbox "src\config.txt") -Value ($legacyEnv + "=true") -Encoding UTF8
    $legacyEnvBlocked = Invoke-NamingCheck
    if ($legacyEnvBlocked.ExitCode -eq 0) {
        throw "Naming check did not block the legacy private URL environment variable."
    }

    Reset-Sandbox
    New-Item -ItemType Directory -Path (Join-Path $sandbox "docs") | Out-Null
    Set-Content -Path (Join-Path $sandbox "docs\notes.md") -Value ("This mentions " + $legacyLower + ".") -Encoding UTF8
    $legacyProductBlocked = Invoke-NamingCheck
    if ($legacyProductBlocked.ExitCode -eq 0) {
        throw "Naming check did not block a legacy product keyword."
    }
} finally {
    Pop-Location
    if (Test-Path -LiteralPath $sandbox) {
        Remove-Item -LiteralPath $sandbox -Recurse -Force
    }
}
