$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$scriptPath = Join-Path $repoRoot "scripts\check-project-naming.ps1"
$releaseNotesScriptPath = Join-Path $repoRoot "scripts\write-release-notes.ps1"
$releaseRangeScriptPath = Join-Path $repoRoot "scripts\resolve-release-range.ps1"
$sandbox = Join-Path ([System.IO.Path]::GetTempPath()) ("jimuqu-naming-check-selftest-" + [Guid]::NewGuid().ToString("N"))
$blockedFixture = "BLOCKED_PROJECT_NAME_ALLOW_PRIVATE_URLS"
$blockedFixtureLower = $blockedFixture.ToLowerInvariant()
$legacyEnvFixture = (-join ([char[]] @(72, 69, 82, 77, 69, 83, 95, 65, 76, 76, 79, 87, 95, 80, 82, 73, 86, 65, 84, 69, 95, 85, 82, 76, 83)))
$legacyEnvFixtureLower = $legacyEnvFixture.ToLowerInvariant()
$legacyBrandFixture = (-join ([char[]] @(79, 112, 101, 110, 67, 108, 97, 119)))
$legacyBrandFixtureLower = $legacyBrandFixture.ToLowerInvariant()

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
        [switch] $WithExtraFixture,
        [switch] $CheckObjectText,
        [switch] $CheckAllGitRefs,
        [switch] $CheckCurrentBranchRange
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
    if ($CheckObjectText) {
        $args += @("-CheckGitObjectText")
    }
    if ($CheckAllGitRefs) {
        $args += @("-CheckAllGitRefs")
    }
    if ($CheckCurrentBranchRange) {
        $args += @("-CheckCurrentBranchRange")
    }
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

function Assert-NoRawBlockedOutput {
    param(
        [string] $Output,
        [string[]] $BlockedValues,
        [string] $Scenario
    )

    foreach ($blockedValue in $BlockedValues) {
        if (-not [string]::IsNullOrWhiteSpace($blockedValue) -and $Output.Contains($blockedValue)) {
            throw ("Naming guard leaked a raw blocked term in output for scenario: {0}" -f $Scenario)
        }
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
    Assert-NoRawBlockedOutput $blocked.Output @($blockedFixture) "directory text scan"

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
    Assert-NoRawBlockedOutput $caseInsensitiveBlocked.Output @($blockedFixtureLower) "case-insensitive directory text scan"

    Reset-Sandbox
    New-Item -ItemType Directory -Path (Join-Path $sandbox "src") | Out-Null
    Set-Content -Path (Join-Path $sandbox "src\legacy-env.txt") -Value ($legacyEnvFixture + "=true") -Encoding UTF8
    Set-Content -Path (Join-Path $sandbox "src\legacy-brand.txt") -Value ("Legacy brand: " + $legacyBrandFixture) -Encoding UTF8
    $legacyNamingBlocked = Invoke-NamingCheck
    if ($legacyNamingBlocked.ExitCode -eq 0) {
        throw "Naming check did not block concrete legacy project naming."
    }
    Assert-NoRawBlockedOutput $legacyNamingBlocked.Output @($legacyEnvFixture, $legacyBrandFixture) "concrete legacy directory text scan"

    Reset-Sandbox
    New-Item -ItemType Directory -Path (Join-Path $sandbox "src") | Out-Null
    Set-Content -Path (Join-Path $sandbox "src\legacy-env.txt") -Value ($legacyEnvFixtureLower + "=true") -Encoding UTF8
    Set-Content -Path (Join-Path $sandbox "src\legacy-brand.txt") -Value ("Legacy brand: " + $legacyBrandFixtureLower) -Encoding UTF8
    $legacyLowerBlocked = Invoke-NamingCheck
    if ($legacyLowerBlocked.ExitCode -eq 0) {
        throw "Naming check did not block lower-case concrete legacy project naming."
    }
    Assert-NoRawBlockedOutput $legacyLowerBlocked.Output @($legacyEnvFixtureLower, $legacyBrandFixtureLower) "lower-case concrete legacy directory text scan"

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
    Assert-NoRawBlockedOutput $blockedCommit.Output @($blockedFixture) "git commit subject scan"

    Push-Location $sandbox
    try {
        Add-Content -Path (Join-Path $sandbox "README.md") -Value "Legacy environment fixture"
        & git add README.md | Out-Null
        & git commit -m ("fix: " + $legacyEnvFixture + " fixture") | Out-Null
    } finally {
        Pop-Location
    }
    $legacyCommitSubjectBlocked = Invoke-GitNamingCheck -Range "HEAD"
    if ($legacyCommitSubjectBlocked.ExitCode -eq 0) {
        throw "Naming check did not block concrete legacy environment naming in git commit subjects."
    }
    Assert-NoRawBlockedOutput $legacyCommitSubjectBlocked.Output @($legacyEnvFixture) "concrete legacy git commit subject scan"

    Push-Location $sandbox
    try {
        Set-Content -Path (Join-Path $sandbox "README.md") -Value ($blockedFixture + " removed later") -Encoding UTF8
        & git add README.md | Out-Null
        & git commit -m "fix: temporary polluted file / Temporary polluted file" | Out-Null
        Set-Content -Path (Join-Path $sandbox "README.md") -Value "Clean again" -Encoding UTF8
        & git add README.md | Out-Null
        & git commit -m "fix: clean polluted file / Clean polluted file" | Out-Null
    } finally {
        Pop-Location
    }
    $blockedObjectText = Invoke-GitNamingCheck -Range "HEAD~2..HEAD" -WithExtraFixture -CheckObjectText
    if ($blockedObjectText.ExitCode -eq 0) {
        throw "Naming check did not block forbidden naming in git object text inside a release range."
    }
    Assert-NoRawBlockedOutput $blockedObjectText.Output @($blockedFixture) "git object text scan"

    Push-Location $sandbox
    try {
        Set-Content -Path (Join-Path $sandbox "README.md") -Value ($legacyEnvFixture + " removed later") -Encoding UTF8
        & git add README.md | Out-Null
        & git commit -m "fix: temporary legacy env file / Temporary legacy env file" | Out-Null
        Set-Content -Path (Join-Path $sandbox "README.md") -Value "Clean after legacy environment fixture" -Encoding UTF8
        & git add README.md | Out-Null
        & git commit -m "fix: clean legacy env file / Clean legacy env file" | Out-Null
    } finally {
        Pop-Location
    }
    $legacyObjectTextBlocked = Invoke-GitNamingCheck -Range "HEAD~2..HEAD" -CheckObjectText
    if ($legacyObjectTextBlocked.ExitCode -eq 0) {
        throw "Naming check did not block concrete legacy environment naming in git object text inside a release range."
    }
    Assert-NoRawBlockedOutput $legacyObjectTextBlocked.Output @($legacyEnvFixture) "concrete legacy git object text scan"

    Push-Location $sandbox
    try {
        Set-Content -Path (Join-Path $sandbox "README.md") -Value "Clean default branch again" -Encoding UTF8
        & git add README.md | Out-Null
        & git commit -m "fix: clean default branch again / Clean default branch again" | Out-Null
        & git checkout -b polluted-history-fixture HEAD~1 | Out-Null
        Set-Content -Path (Join-Path $sandbox "README.md") -Value ($blockedFixture + " only on another ref") -Encoding UTF8
        & git add README.md | Out-Null
        & git commit -m "fix: polluted all-ref fixture / Polluted all-ref fixture" | Out-Null
        & git checkout main | Out-Null
    } finally {
        Pop-Location
    }
    $defaultRangeClean = Invoke-GitNamingCheck -Range "HEAD^..HEAD" -WithExtraFixture -CheckObjectText
    if ($defaultRangeClean.ExitCode -ne 0) {
        throw "Naming check should keep explicit clean ranges independent from other refs, but failed: $($defaultRangeClean.Output)"
    }
    $allRefsBlocked = Invoke-GitNamingCheck -WithExtraFixture -CheckObjectText -CheckAllGitRefs
    if ($allRefsBlocked.ExitCode -eq 0) {
        throw "Naming check did not block forbidden naming in all reachable git refs."
    }
    Assert-NoRawBlockedOutput $allRefsBlocked.Output @($blockedFixture) "all refs git object text scan"

    Reset-Sandbox
    Push-Location $sandbox
    try {
        & git init --initial-branch=main | Out-Null
        & git config user.name "Jimuqu Naming Check" | Out-Null
        & git config user.email "naming-check@example.invalid" | Out-Null
        Set-Content -Path (Join-Path $sandbox "README.md") -Value "Clean baseline" -Encoding UTF8
        & git add README.md | Out-Null
        & git commit -m ("fix: " + $legacyEnvFixture + " historical fixture") | Out-Null
        Set-Content -Path (Join-Path $sandbox "README.md") -Value "Clean current branch" -Encoding UTF8
        & git add README.md | Out-Null
        & git commit -m "fix: clean current branch / Clean current branch" | Out-Null
        & git remote add origin $sandbox | Out-Null
        & git update-ref refs/remotes/origin/main HEAD | Out-Null
    } finally {
        Pop-Location
    }
    $emptyCurrentBranchRange = Invoke-GitNamingCheck -CheckObjectText -CheckCurrentBranchRange
    if ($emptyCurrentBranchRange.ExitCode -ne 0) {
        throw "Naming check should skip git history scanning when the current branch has no commits ahead of the default branch, but failed: $($emptyCurrentBranchRange.Output)"
    }

    Reset-Sandbox
    Push-Location $sandbox
    try {
        & git init --initial-branch=main | Out-Null
        & git config user.name "Jimuqu Naming Check" | Out-Null
        & git config user.email "naming-check@example.invalid" | Out-Null
        Set-Content -Path (Join-Path $sandbox "README.md") -Value ($legacyEnvFixture + " before clean baseline") -Encoding UTF8
        & git add README.md | Out-Null
        & git commit -m "fix: historical polluted release base / Historical polluted release base" | Out-Null
        & git tag -a "v2000.01.01-deadbee" -m "Release v2000.01.01-deadbee" | Out-Null
        Set-Content -Path (Join-Path $sandbox "README.md") -Value "Clean naming baseline" -Encoding UTF8
        & git add README.md | Out-Null
        & git commit -m "chore: clean naming baseline / Clean naming baseline" | Out-Null
        $cleanBase = (& git rev-parse HEAD).Trim()
        Set-Content -Path (Join-Path $sandbox "README.md") -Value "Clean release change" -Encoding UTF8
        & git add README.md | Out-Null
        & git commit -m "feat: clean release range / Clean release range" | Out-Null
        $head = (& git rev-parse HEAD).Trim()

        $rangeOutput = & pwsh -NoProfile -ExecutionPolicy Bypass -File $releaseRangeScriptPath `
            -HeadSha $head `
            -CleanNamingBase $cleanBase 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "Release range resolver failed with an older polluted tag: $($rangeOutput | Out-String)"
        }
        $rangeText = ($rangeOutput | Out-String)
        if ($rangeText -notmatch [Regex]::Escape("$cleanBase..$head")) {
            throw "Release range resolver should prefer the clean naming baseline over older tags."
        }
        if ($rangeText -match "v2000\.01\.01-deadbee") {
            throw "Release range resolver should not use an older polluted tag as the release base."
        }
    } finally {
        Pop-Location
    }

    $releaseDir = Join-Path $sandbox "dist"
    New-Item -ItemType Directory -Path $releaseDir | Out-Null
    $releaseNotesPath = Join-Path $releaseDir "release-notes.md"
    Push-Location $sandbox
    try {
        $releaseOutput = & pwsh -NoProfile -ExecutionPolicy Bypass -File $releaseNotesScriptPath `
            -OutputPath $releaseNotesPath `
            -Tag "v2099.01.01-abcdef0" `
            -Version "0.0.0-test" `
            -CommitRange "HEAD" `
            -DisplayRange "HEAD" `
            -ExtraBlockedTerms $blockedFixture 2>&1
        if ($LASTEXITCODE -eq 0) {
            throw "Release notes generation should fail on blocked legacy naming."
        }
        Assert-NoRawBlockedOutput ($releaseOutput | Out-String) @($blockedFixture) "release notes generation"
    } finally {
        Pop-Location
    }

} finally {
    Pop-Location
    if (Test-Path -LiteralPath $sandbox) {
        Remove-Item -LiteralPath $sandbox -Recurse -Force
    }
}
