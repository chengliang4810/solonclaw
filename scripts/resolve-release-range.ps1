param(
    [string] $HeadSha = $env:GITHUB_SHA,
    [string] $CleanNamingBase = "a6e245c53d8eacdc041a2314390448e92e31ab10",
    [string] $GithubOutputPath = $env:GITHUB_OUTPUT
)

$ErrorActionPreference = "Stop"

function Invoke-GitLine {
    param([string[]] $Arguments)

    $output = & git @Arguments 2>$null
    if ($LASTEXITCODE -ne 0) {
        return ""
    }
    return (($output | Select-Object -First 1) -as [string]).Trim()
}

function Test-GitAncestor {
    param(
        [string] $Ancestor,
        [string] $Descendant
    )

    if ([string]::IsNullOrWhiteSpace($Ancestor) -or [string]::IsNullOrWhiteSpace($Descendant)) {
        return $false
    }
    & git merge-base --is-ancestor $Ancestor $Descendant 2>$null
    return $LASTEXITCODE -eq 0
}

function Get-ShortSha {
    param([string] $Sha)

    if ([string]::IsNullOrWhiteSpace($Sha)) {
        return ""
    }
    return Invoke-GitLine -Arguments @("rev-parse", "--short", $Sha)
}

if ([string]::IsNullOrWhiteSpace($HeadSha)) {
    throw "HeadSha is required."
}

$shortHead = Get-ShortSha -Sha $HeadSha
if ([string]::IsNullOrWhiteSpace($shortHead)) {
    throw ("Cannot resolve release head: {0}" -f $HeadSha)
}

$cleanBaseReachable = Test-GitAncestor $CleanNamingBase $HeadSha
$previousTag = Invoke-GitLine -Arguments @("describe", "--tags", "--match", "v*", "--abbrev=0", "$HeadSha^")
$previousTagCommit = ""
if (-not [string]::IsNullOrWhiteSpace($previousTag)) {
    $previousTagCommit = Invoke-GitLine -Arguments @("rev-list", "-n", "1", $previousTag)
}

if ($cleanBaseReachable -and $HeadSha -eq $CleanNamingBase) {
    $gitRange = $HeadSha
    $displayRange = $shortHead
} elseif ($cleanBaseReachable -and (
        [string]::IsNullOrWhiteSpace($previousTag) -or
        (Test-GitAncestor $previousTagCommit $CleanNamingBase))) {
    $gitRange = "$CleanNamingBase..$HeadSha"
    $displayRange = "clean naming baseline..$shortHead"
} elseif (-not [string]::IsNullOrWhiteSpace($previousTag)) {
    $gitRange = "$previousTag..$HeadSha"
    $displayRange = "$previousTag..$shortHead"
} else {
    $commits = @(& git rev-list --max-count=30 $HeadSha)
    if ($LASTEXITCODE -ne 0 -or $commits.Length -eq 0) {
        throw ("Cannot resolve fallback release range for: {0}" -f $HeadSha)
    }
    $oldestCommit = ($commits[$commits.Length - 1] -as [string]).Trim()
    & git rev-parse -q --verify "$oldestCommit^" 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) {
        $gitRange = "$oldestCommit^..$HeadSha"
    } else {
        $gitRange = $HeadSha
    }
    $displayRange = "latest 30 commits ending at $shortHead"
}

if (-not [string]::IsNullOrWhiteSpace($GithubOutputPath)) {
    Add-Content -LiteralPath $GithubOutputPath -Value ("git_range={0}" -f $gitRange)
    Add-Content -LiteralPath $GithubOutputPath -Value ("display_range={0}" -f $displayRange)
} else {
    [PSCustomObject]@{
        git_range = $gitRange
        display_range = $displayRange
    }
}
