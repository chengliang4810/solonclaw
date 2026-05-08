$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$scriptPath = Join-Path $repoRoot "scripts\check-project-naming.ps1"
$releaseNotesScriptPath = Join-Path $repoRoot "scripts\write-release-notes.ps1"
$sandbox = Join-Path ([System.IO.Path]::GetTempPath()) ("jimuqu-naming-check-selftest-" + [Guid]::NewGuid().ToString("N"))
$blockedFixture = "BLOCKED_PROJECT_NAME_ALLOW_PRIVATE_URLS"
$blockedFixtureLower = $blockedFixture.ToLowerInvariant()

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

function Invoke-GitNamingCheck {
    param(
        [string] $Range,
        [switch] $WithExtraFixture
    )

    $args = @(
        "-NoProfile",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        $scriptPath,
        "-RootPath",
        $sandbox,
        "-CheckGitCommitSubjects"
    )
    if (-not [string]::IsNullOrWhiteSpace($Range)) {
        $args += @("-GitCommitRange", $Range)
    }
    if ($WithExtraFixture) {
        $args += @("-ExtraBlockedTerms", $blockedFixture)
    }
    $output = & pwsh @args 2>&1
    return @{
        ExitCode = $LASTEXITCODE
        Output = ($output | Out-String)
    }
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
    Set-Content -Path (Join-Path $sandbox "src\config.txt") -Value ($blockedFixtureLower + "=true") -Encoding UTF8
    $caseInsensitiveBlocked = Invoke-NamingCheck -WithExtraFixture
    if ($caseInsensitiveBlocked.ExitCode -eq 0) {
        throw "Naming check did not block a forbidden term with different casing."
    }

    Reset-Sandbox
    Push-Location $sandbox
    try {
        & git init --initial-branch=main | Out-Null
        & git config user.name "Jimuqu Naming Check" | Out-Null
        & git config user.email "naming-check@example.invalid" | Out-Null
        Set-Content -Path (Join-Path $sandbox "README.md") -Value "Clean fixture" -Encoding UTF8
        & git add README.md | Out-Null
        & git commit -m "feat: clean fixture / Clean fixture" | Out-Null
        Add-Content -Path (Join-Path $sandbox "README.md") -Value "Second fixture"
        & git add README.md | Out-Null
        & git commit -m ("feat: " + $blockedFixture + " fixture") | Out-Null
    } finally {
        Pop-Location
    }
    $blockedCommit = Invoke-GitNamingCheck -Range "HEAD" -WithExtraFixture
    if ($blockedCommit.ExitCode -eq 0) {
        throw "Naming check did not block forbidden naming in git commit subjects."
    }

    $releaseDir = Join-Path $sandbox "dist"
    New-Item -ItemType Directory -Path $releaseDir | Out-Null
    $releaseNotesPath = Join-Path $releaseDir "release-notes.md"
    Push-Location $sandbox
    try {
        & pwsh -NoProfile -ExecutionPolicy Bypass -File $releaseNotesScriptPath `
            -OutputPath $releaseNotesPath `
            -Tag "v2099.01.01-abcdef0" `
            -Version "0.0.0-test" `
            -CommitRange "HEAD" `
            -DisplayRange "HEAD" `
            -ExtraBlockedTerms $blockedFixture
        if ($LASTEXITCODE -ne 0) {
            throw "Release notes generation failed."
        }
    } finally {
        Pop-Location
    }
    $releaseNaming = Invoke-NamingCheck -WithExtraFixture
    if ($releaseNaming.ExitCode -ne 0) {
        throw "Generated release notes leaked legacy naming: $($releaseNaming.Output)"
    }
} finally {
    Pop-Location
    if (Test-Path -LiteralPath $sandbox) {
        Remove-Item -LiteralPath $sandbox -Recurse -Force
    }
}
