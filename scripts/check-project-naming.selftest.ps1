$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$scriptPath = Join-Path $repoRoot "scripts\check-project-naming.ps1"
$releaseNotesScriptPath = Join-Path $repoRoot "scripts\write-release-notes.ps1"
$releaseRangeScriptPath = Join-Path $repoRoot "scripts\resolve-release-range.ps1"
$publishedReleaseScriptPath = Join-Path $repoRoot "scripts\check-release-naming.ps1"
$archiveNamingScriptPath = Join-Path $repoRoot "scripts\check-archive-naming.ps1"
$sandbox = Join-Path ([System.IO.Path]::GetTempPath()) ("jimuqu-naming-check-selftest-" + [Guid]::NewGuid().ToString("N"))
$blockedFixture = "BLOCKED_LEGACY_TOKEN_FIXTURE"
$blockedFixtureLower = $blockedFixture.ToLowerInvariant()
$blockedEnvFixture = $blockedFixture + "_ALLOW_PRIVATE_URLS"
$blockedDefaultEnvFixture = "BAD_" + "LEGACY_" + "PREFIX_ALLOW_PRIVATE_URLS"
$blockedLegacyProjectA = "HER" + "MES"
$blockedLegacyProjectB = "Open" + "Claw"
$blockedLegacyProjectAEnv = $blockedLegacyProjectA + "_ALLOW_PRIVATE_URLS"
$blockedLegacyProjectBEnv = $blockedLegacyProjectB.ToUpperInvariant() + "_ALLOW_PRIVATE_URLS"

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
    New-Item -ItemType Directory -Path (Join-Path $sandbox "src") | Out-Null
    Set-Content -Path (Join-Path $sandbox "src\external-env.txt") -Value ($blockedEnvFixture + "=true") -Encoding UTF8
    $blockedExternalEnv = Invoke-NamingCheck -WithExtraFixture
    if ($blockedExternalEnv.ExitCode -eq 0) {
        throw "Naming check did not block a forbidden external-name environment variable."
    }
    Assert-NoRawBlockedOutput $blockedExternalEnv.Output @($blockedEnvFixture) "external-name environment variable scan"

    Reset-Sandbox
    New-Item -ItemType Directory -Path (Join-Path $sandbox "src") | Out-Null
    Set-Content -Path (Join-Path $sandbox "src\legacy-project-a-env.txt") -Value ($blockedLegacyProjectAEnv + "=true") -Encoding UTF8
    $blockedLegacyProjectAEnvResult = Invoke-NamingCheck
    if ($blockedLegacyProjectAEnvResult.ExitCode -eq 0) {
        throw "Naming check did not block a forbidden project-specific private URL environment variable."
    }
    Assert-NoRawBlockedOutput $blockedLegacyProjectAEnvResult.Output @($blockedLegacyProjectAEnv) "project-specific private URL environment variable scan"

    Reset-Sandbox
    New-Item -ItemType Directory -Path (Join-Path $sandbox "src") | Out-Null
    Set-Content -Path (Join-Path $sandbox "src\legacy-project-b-env.txt") -Value ($blockedLegacyProjectBEnv + "=true") -Encoding UTF8
    $blockedLegacyProjectBEnvResult = Invoke-NamingCheck
    if ($blockedLegacyProjectBEnvResult.ExitCode -eq 0) {
        throw "Naming check did not block a forbidden alternate-project private URL environment variable."
    }
    Assert-NoRawBlockedOutput $blockedLegacyProjectBEnvResult.Output @($blockedLegacyProjectBEnv) "alternate-project private URL environment variable scan"

    Reset-Sandbox
    New-Item -ItemType Directory -Path (Join-Path $sandbox "docs") | Out-Null
    Set-Content -Path (Join-Path $sandbox "docs\external-name.md") -Value ("Old upstream name: " + $blockedEnvFixture) -Encoding UTF8
    $blockedExternalName = Invoke-NamingCheck -WithExtraFixture
    if ($blockedExternalName.ExitCode -eq 0) {
        throw "Naming check did not block a forbidden external project name."
    }
    Assert-NoRawBlockedOutput $blockedExternalName.Output @($blockedEnvFixture) "external project name scan"

    Reset-Sandbox
    New-Item -ItemType Directory -Path (Join-Path $sandbox "src") | Out-Null
    Set-Content -Path (Join-Path $sandbox "src\legacy-env.txt") -Value ($blockedEnvFixture + "=true") -Encoding UTF8
    $blockedLegacyEnv = Invoke-NamingCheck -WithExtraFixture
    if ($blockedLegacyEnv.ExitCode -eq 0) {
        throw "Naming check did not block a forbidden legacy environment variable."
    }
    Assert-NoRawBlockedOutput $blockedLegacyEnv.Output @($blockedEnvFixture) "legacy environment variable scan"

    Reset-Sandbox
    New-Item -ItemType Directory -Path (Join-Path $sandbox "src") | Out-Null
    Set-Content -Path (Join-Path $sandbox "src\private-url-env.txt") -Value ($blockedEnvFixture + "=true") -Encoding UTF8
    $blockedPrivateUrlEnv = Invoke-NamingCheck -WithExtraFixture
    if ($blockedPrivateUrlEnv.ExitCode -eq 0) {
        throw "Naming check did not block a forbidden private URL environment variable."
    }
    Assert-NoRawBlockedOutput $blockedPrivateUrlEnv.Output @($blockedEnvFixture) "legacy private URL environment variable scan"

    Reset-Sandbox
    New-Item -ItemType Directory -Path (Join-Path $sandbox "src") | Out-Null
    Set-Content -Path (Join-Path $sandbox "src\default-private-url-env.txt") -Value ($blockedEnvFixture + "=true") -Encoding UTF8
    $blockedDefaultPrivateUrlEnv = Invoke-NamingCheck -WithExtraFixture
    if ($blockedDefaultPrivateUrlEnv.ExitCode -eq 0) {
        throw "Naming check did not block a forbidden default private URL environment variable."
    }
    Assert-NoRawBlockedOutput $blockedDefaultPrivateUrlEnv.Output @($blockedEnvFixture) "default private URL environment variable scan"

    Reset-Sandbox
    New-Item -ItemType Directory -Path (Join-Path $sandbox "docs") | Out-Null
    Set-Content -Path (Join-Path $sandbox "docs\legacy-name.md") -Value ("Old upstream name: " + $blockedEnvFixture) -Encoding UTF8
    $blockedLegacyName = Invoke-NamingCheck -WithExtraFixture
    if ($blockedLegacyName.ExitCode -eq 0) {
        throw "Naming check did not block a forbidden legacy project name."
    }
    Assert-NoRawBlockedOutput $blockedLegacyName.Output @($blockedEnvFixture) "legacy project name scan"

    Reset-Sandbox
    New-Item -ItemType Directory -Path (Join-Path $sandbox "docs") | Out-Null
    Set-Content -Path (Join-Path $sandbox "docs\default-legacy-name.md") -Value ("Old upstream name: " + $blockedEnvFixture) -Encoding UTF8
    $blockedDefaultLegacyName = Invoke-NamingCheck -WithExtraFixture
    if ($blockedDefaultLegacyName.ExitCode -eq 0) {
        throw "Naming check did not block a forbidden default legacy project name."
    }
    Assert-NoRawBlockedOutput $blockedDefaultLegacyName.Output @($blockedEnvFixture) "default legacy project name scan"

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
        Set-Content -Path (Join-Path $sandbox "README.md") -Value "Clean body fixture" -Encoding UTF8
        & git add README.md | Out-Null
        & git commit -m "fix: clean subject with polluted body / Clean subject with polluted body" `
            -m ("body uses " + $blockedDefaultEnvFixture) | Out-Null
    } finally {
        Pop-Location
    }
    $blockedCommitBody = Invoke-GitNamingCheck -Range "HEAD"
    if ($blockedCommitBody.ExitCode -eq 0) {
        throw "Naming check did not block forbidden naming in git commit messages."
    }
    Assert-NoRawBlockedOutput $blockedCommitBody.Output @($blockedDefaultEnvFixture) "git commit body scan"

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
        & git commit -m ("fix: " + $blockedFixture + " historical fixture") | Out-Null
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
        Set-Content -Path (Join-Path $sandbox "README.md") -Value ($blockedFixture + " before clean baseline") -Encoding UTF8
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
            -CleanNamingBase $cleanBase `
            -GithubOutputPath "" 2>&1
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

    Reset-Sandbox
    New-Item -ItemType Directory -Path $releaseDir | Out-Null
    $releaseNotesPath = Join-Path $releaseDir "release-notes.md"
    Push-Location $sandbox
    try {
        & git init --initial-branch=main | Out-Null
        & git config user.name "Jimuqu Naming Check" | Out-Null
        & git config user.email "naming-check@example.invalid" | Out-Null
        Set-Content -Path (Join-Path $sandbox "README.md") -Value "Clean file for release private URL fixture" -Encoding UTF8
        & git add README.md | Out-Null
        & git commit -m ("fix: block " + $blockedDefaultEnvFixture + " release leak") | Out-Null

        $releaseDefaultSubjectOutput = & pwsh -NoProfile -ExecutionPolicy Bypass -File $releaseNotesScriptPath `
            -OutputPath $releaseNotesPath `
            -Tag "v2099.01.02-bcdef02" `
            -Version "0.0.0-test" `
            -CommitRange "HEAD" `
            -DisplayRange "HEAD" 2>&1
        if ($LASTEXITCODE -eq 0) {
            throw "Release notes generation should fail when forbidden default private URL naming exists in a commit subject."
        }
        Assert-NoRawBlockedOutput ($releaseDefaultSubjectOutput | Out-String) @($blockedDefaultEnvFixture) "release notes default private URL subject generation"
    } finally {
        Pop-Location
    }

    Reset-Sandbox
    New-Item -ItemType Directory -Path $releaseDir | Out-Null
    $releaseNotesPath = Join-Path $releaseDir "release-notes.md"
    Push-Location $sandbox
    try {
        & git init --initial-branch=main | Out-Null
        & git config user.name "Jimuqu Naming Check" | Out-Null
        & git config user.email "naming-check@example.invalid" | Out-Null
        Set-Content -Path (Join-Path $sandbox "README.md") -Value "Clean file for release subject fixture" -Encoding UTF8
        & git add README.md | Out-Null
        & git commit -m ("fix: block " + $blockedDefaultEnvFixture + " release leak") | Out-Null

        $releaseSubjectOutput = & pwsh -NoProfile -ExecutionPolicy Bypass -File $releaseNotesScriptPath `
            -OutputPath $releaseNotesPath `
            -Tag "v2099.01.02-bcdef01" `
            -Version "0.0.0-test" `
            -CommitRange "HEAD" `
            -DisplayRange "HEAD" 2>&1
        if ($LASTEXITCODE -eq 0) {
            throw "Release notes generation should fail when blocked default naming exists in a commit subject."
        }
        Assert-NoRawBlockedOutput ($releaseSubjectOutput | Out-String) @($blockedDefaultEnvFixture) "release notes default subject generation"
    } finally {
        Pop-Location
    }

    Reset-Sandbox
    New-Item -ItemType Directory -Path $releaseDir | Out-Null
    $releaseNotesPath = Join-Path $releaseDir "release-notes.md"
    Push-Location $sandbox
    try {
        & git init --initial-branch=main | Out-Null
        & git config user.name "Jimuqu Naming Check" | Out-Null
        & git config user.email "naming-check@example.invalid" | Out-Null
        Set-Content -Path (Join-Path $sandbox "README.md") -Value "Clean release note fixture" -Encoding UTF8
        for ($i = 1; $i -le 9; $i++) {
            Set-Content -Path (Join-Path $sandbox ("changed-{0}.txt" -f $i)) -Value ("changed file {0}" -f $i) -Encoding UTF8
        }
        & git add README.md | Out-Null
        & git add changed-*.txt | Out-Null
        & git commit -m "fix: clean release notes / Clean release notes" | Out-Null
        Add-Content -Path (Join-Path $sandbox "README.md") -Value "Scoped feature fixture"
        & git add README.md | Out-Null
        & git commit -m "feat(cron): scoped feature release note / Scoped feature release note" `
            -m "功能：补充计划任务投递策略说明。" `
            -m "Feature: add scheduled delivery policy details." | Out-Null
        Add-Content -Path (Join-Path $sandbox "README.md") -Value "Scoped fix fixture"
        & git add README.md | Out-Null
        & git commit -m "fix(api): scoped fix release note / Scoped fix release note" `
            -m "缺陷修复：避免空提交范围只生成占位说明。" `
            -m "Fix: avoid placeholder-only notes for empty ranges." | Out-Null

        $cleanReleaseOutput = & pwsh -NoProfile -ExecutionPolicy Bypass -File $releaseNotesScriptPath `
            -OutputPath $releaseNotesPath `
            -Tag "v2099.01.03-cdef012" `
            -Version "0.0.0-test" `
            -CommitRange "HEAD" `
            -DisplayRange "HEAD" 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "Release notes generation should succeed for a clean range without extra blocked terms: $($cleanReleaseOutput | Out-String)"
        }
        $cleanReleaseText = Get-Content -LiteralPath $releaseNotesPath -Raw -Encoding UTF8
        if ($cleanReleaseText -notmatch "fix: clean release notes / Clean release notes") {
            throw "Release notes generation did not include the clean commit subject."
        }
        if ($cleanReleaseText -notmatch "fix: clean release notes / Clean release notes[\s\S]*影响文件 / Changed files:[\s\S]*README\.md") {
            throw "Release notes generation did not include changed files for commits without body details."
        }
        if ($cleanReleaseText -notmatch "另有 2 个文件未展开。 / 2 more files omitted.") {
            throw "Release notes generation did not limit long changed-file lists with a bilingual omission note."
        }
        if ($cleanReleaseText -match "changed-9\.txt") {
            throw "Release notes generation should omit files beyond the display limit."
        }
        if ($cleanReleaseText -notmatch "### 功能 / Features[\s\S]*feat\(cron\): scoped feature release note / Scoped feature release note") {
            throw "Release notes generation did not classify scoped feat commits as features."
        }
        if ($cleanReleaseText -notmatch "### 功能 / Features[\s\S]*详情 / Details:[\s\S]*功能：补充计划任务投递策略说明。[\s\S]*Feature: add scheduled delivery policy details.") {
            throw "Release notes generation did not include feature details from commit body."
        }
        if ($cleanReleaseText -notmatch "### 缺陷修复 / Fixes[\s\S]*fix\(api\): scoped fix release note / Scoped fix release note") {
            throw "Release notes generation did not classify scoped fix commits as fixes."
        }
        if ($cleanReleaseText -notmatch "### 缺陷修复 / Fixes[\s\S]*详情 / Details:[\s\S]*缺陷修复：避免空提交范围只生成占位说明。[\s\S]*Fix: avoid placeholder-only notes for empty ranges.") {
            throw "Release notes generation did not include fix details from commit body."
        }
    } finally {
        Pop-Location
    }

    Reset-Sandbox
    New-Item -ItemType Directory -Path $releaseDir | Out-Null
    $releaseNotesPath = Join-Path $releaseDir "release-notes-empty-range.md"
    Push-Location $sandbox
    try {
        & git init --initial-branch=main | Out-Null
        & git config user.name "Jimuqu Naming Check" | Out-Null
        & git config user.email "naming-check@example.invalid" | Out-Null
        Set-Content -Path (Join-Path $sandbox "README.md") -Value "Fallback release note fixture" -Encoding UTF8
        & git add README.md | Out-Null
        & git commit -m "fix: fallback release notes / Fallback release notes" | Out-Null

        $emptyRangeReleaseOutput = & pwsh -NoProfile -ExecutionPolicy Bypass -File $releaseNotesScriptPath `
            -OutputPath $releaseNotesPath `
            -Tag "v2099.01.06-f012345" `
            -Version "0.0.0-test" `
            -CommitRange "HEAD..HEAD" `
            -DisplayRange "HEAD..HEAD" 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "Release notes generation should fall back to the current commit for an empty range: $($emptyRangeReleaseOutput | Out-String)"
        }
        $emptyRangeReleaseText = Get-Content -LiteralPath $releaseNotesPath -Raw -Encoding UTF8
        if ($emptyRangeReleaseText -notmatch "fix: fallback release notes / Fallback release notes") {
            throw "Release notes empty-range fallback did not include the current commit subject."
        }
        if ($emptyRangeReleaseText -match "No commits were explicitly marked as fix") {
            throw "Release notes empty-range fallback should not emit only the fix placeholder."
        }
        if ($emptyRangeReleaseText -notmatch "Commit range: ``[0-9a-f]{7,}``") {
            throw "Release notes empty-range fallback did not replace display range with the current short commit."
        }
        if ($emptyRangeReleaseText -notmatch "空提交范围，已使用当前提交生成发布说明。") {
            throw "Release notes empty-range fallback did not include the Chinese fallback note."
        }
        if ($emptyRangeReleaseText -notmatch "Empty commit range; the current commit was used to generate these release notes.") {
            throw "Release notes empty-range fallback did not include the English fallback note."
        }
    } finally {
        Pop-Location
    }

    Reset-Sandbox
    New-Item -ItemType Directory -Path $releaseDir | Out-Null
    $releaseNotesPath = Join-Path $releaseDir "release-notes.md"
    Push-Location $sandbox
    try {
        & git init --initial-branch=main | Out-Null
        & git config user.name "Jimuqu Naming Check" | Out-Null
        & git config user.email "naming-check@example.invalid" | Out-Null
        Set-Content -Path (Join-Path $sandbox "README.md") -Value ($blockedFixture + " in object text only") -Encoding UTF8
        & git add README.md | Out-Null
        & git commit -m "fix: clean subject with blocked object / Clean subject with blocked object" | Out-Null

        $releaseObjectOutput = & pwsh -NoProfile -ExecutionPolicy Bypass -File $releaseNotesScriptPath `
            -OutputPath $releaseNotesPath `
            -Tag "v2099.01.02-abcdef0" `
            -Version "0.0.0-test" `
            -CommitRange "HEAD" `
            -DisplayRange "HEAD" `
            -ExtraBlockedTerms $blockedFixture 2>&1
        if ($LASTEXITCODE -eq 0) {
            throw "Release notes generation should fail when extended blocked naming exists only in git object text."
        }
        Assert-NoRawBlockedOutput ($releaseObjectOutput | Out-String) @($blockedFixture) "release notes git object text generation"
    } finally {
        Pop-Location
    }

    Reset-Sandbox
    $publishedReleaseFixturePath = Join-Path $sandbox "published-release.json"
    $publishedReleaseFixture = [PSCustomObject]@{
        name = "jimuqu-agent v2099.01.04-def0123"
        tag_name = "v2099.01.04-def0123"
        body = "Published release body mentions $blockedDefaultEnvFixture"
        assets = @(
            [PSCustomObject]@{
                name = "jimuqu-agent-0.0.0-test.jar"
                label = ""
                content_type = "application/java-archive"
            }
        )
    }
    $publishedReleaseFixture | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $publishedReleaseFixturePath -Encoding UTF8
    $publishedReleaseOutput = & pwsh -NoProfile -ExecutionPolicy Bypass -File $publishedReleaseScriptPath `
        -LocalJsonPath $publishedReleaseFixturePath 2>&1
    if ($LASTEXITCODE -eq 0) {
        throw "Published release naming check should fail when blocked naming exists in release metadata."
    }
    Assert-NoRawBlockedOutput ($publishedReleaseOutput | Out-String) @($blockedDefaultEnvFixture) "published release metadata scan"

    Reset-Sandbox
    $cleanPublishedReleaseFixturePath = Join-Path $sandbox "published-release-clean.json"
    $cleanPublishedReleaseFixture = [PSCustomObject]@{
        name = "jimuqu-agent v2099.01.05-ef01234"
        tag_name = "v2099.01.05-ef01234"
        body = "Clean published release body"
        assets = @(
            [PSCustomObject]@{
                name = "jimuqu-agent-0.0.0-test.jar"
                label = ""
                content_type = "application/java-archive"
            }
        )
    }
    $cleanPublishedReleaseFixture | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $cleanPublishedReleaseFixturePath -Encoding UTF8
    $cleanPublishedReleaseOutput = & pwsh -NoProfile -ExecutionPolicy Bypass -File $publishedReleaseScriptPath `
        -LocalJsonPath $cleanPublishedReleaseFixturePath 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Published release naming check should pass for clean release metadata: $($cleanPublishedReleaseOutput | Out-String)"
    }

    Reset-Sandbox
    $archiveRoot = Join-Path $sandbox "archive-root"
    $archivePath = Join-Path $sandbox "fixture.jar"
    New-Item -ItemType Directory -Path $archiveRoot | Out-Null
    [System.IO.File]::WriteAllBytes(
        (Join-Path $archiveRoot "Binary.class"),
        [System.Text.Encoding]::ASCII.GetBytes("constant-pool " + $blockedDefaultEnvFixture + " value"))
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::CreateFromDirectory($archiveRoot, $archivePath)
    $archiveOutput = & pwsh -NoProfile -ExecutionPolicy Bypass -File $archiveNamingScriptPath `
        -ArchivePath $archivePath 2>&1
    if ($LASTEXITCODE -eq 0) {
        throw "Archive naming check should fail when blocked naming exists inside packaged binary constants."
    }
    Assert-NoRawBlockedOutput ($archiveOutput | Out-String) @($blockedDefaultEnvFixture) "archive binary constant scan"

    Reset-Sandbox
    $cleanArchiveRoot = Join-Path $sandbox "clean-archive-root"
    $cleanArchivePath = Join-Path $sandbox "clean-fixture.jar"
    New-Item -ItemType Directory -Path $cleanArchiveRoot | Out-Null
    Set-Content -Path (Join-Path $cleanArchiveRoot "app.properties") -Value "app.name=jimuqu-agent" -Encoding UTF8
    [System.IO.Compression.ZipFile]::CreateFromDirectory($cleanArchiveRoot, $cleanArchivePath)
    $cleanArchiveOutput = & pwsh -NoProfile -ExecutionPolicy Bypass -File $archiveNamingScriptPath `
        -ArchivePath $cleanArchivePath 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Archive naming check should pass for clean packaged content: $($cleanArchiveOutput | Out-String)"
    }

} finally {
    Pop-Location
    if (Test-Path -LiteralPath $sandbox) {
        Remove-Item -LiteralPath $sandbox -Recurse -Force
    }
}
