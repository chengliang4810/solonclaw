param(
    [Parameter(Mandatory = $true)]
    [string] $OutputPath,
    [Parameter(Mandatory = $true)]
    [string] $Tag,
    [Parameter(Mandatory = $true)]
    [string] $Version,
    [Parameter(Mandatory = $true)]
    [string] $CommitRange,
    [Parameter(Mandatory = $true)]
    [string] $DisplayRange,
    [string[]] $ExtraBlockedTerms = @()
)

$ErrorActionPreference = "Stop"

function Invoke-ProjectNamingGuard {
    param([string] $Range)

    if ([string]::IsNullOrWhiteSpace($Range)) {
        return
    }

    $scriptPath = Join-Path $PSScriptRoot "check-project-naming.ps1"
    if (-not (Test-Path -LiteralPath $scriptPath)) {
        throw "Project naming guard script was not found."
    }

    $rootPath = (Get-Location).Path
    $guardArgs = @(
        "-NoProfile",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        $scriptPath,
        "-RootPath",
        $rootPath,
        "-CheckGitCommitSubjects",
        "-CheckGitObjectText",
        "-GitCommitRange",
        $Range
    )
    $cleanExtraBlockedTerms = @($ExtraBlockedTerms | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    if ($cleanExtraBlockedTerms.Length -gt 0) {
        $guardArgs += @("-ExtraBlockedTerms")
        $guardArgs += $cleanExtraBlockedTerms
    }
    $guardOutput = & pwsh @guardArgs 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw ("Release naming guard failed for commit range: {0}`n{1}" -f $Range, ($guardOutput | Out-String))
    }
}

function New-TextFromCodes {
    param([int[]] $Codes)

    return (($Codes | ForEach-Object { [string] [char] $_ }) -join "")
}

function Get-BlockedReleaseRegex {
    $firstExternalName = [Regex]::Escape((New-TextFromCodes @(72, 69, 82, 77, 69, 83)))
    $secondExternalNamePartA = [Regex]::Escape((New-TextFromCodes @(79, 80, 69, 78)))
    $secondExternalNamePartB = [Regex]::Escape((New-TextFromCodes @(67, 76, 65, 87)))
    $secondExternalName = $secondExternalNamePartA + "(?:[_\-\.\s])?" + $secondExternalNamePartB
    $patterns = @()
    $patterns += ($firstExternalName + "_?")
    $patterns += ($firstExternalName + "(?:[_\-.])")
    $patterns += ($secondExternalName + "[_\-]?")
    $patterns += ($secondExternalName + "(?:[_\-.])")
    foreach ($blockedTerm in $ExtraBlockedTerms) {
        if (-not [string]::IsNullOrWhiteSpace($blockedTerm)) {
            $patterns += [Regex]::Escape($blockedTerm)
        }
    }

    return [Regex]::new(
        ($patterns -join "|"),
        [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
}

function Assert-CleanReleaseText {
    param([string] $Text)

    if ([string]::IsNullOrEmpty($Text)) {
        return
    }

    if ($script:BlockedReleaseRegex.IsMatch($Text)) {
        throw "Release notes input contains blocked legacy project naming. Rewrite the commit subject before publishing."
    }
}

function Normalize-ReleaseItem {
    param([string] $Item)

    Assert-CleanReleaseText $Item
    if ($Item -match "\s/\s") {
        return $Item
    }
    return ("提交：{0} / Commit: {0}" -f $Item)
}

function Get-CommitSubjects {
    param([string] $Range)

    $subjects = & git log --pretty=format:'%s' $Range 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw ("Failed to read git commit subjects for range: {0}" -f $Range)
    }
    return @($subjects | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
}

function Get-HeadCommitSubject {
    $subjects = & git log -1 --pretty=format:'%s' HEAD 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to read current git commit subject."
    }
    return @($subjects | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
}

function Select-Items {
    param(
        [string[]] $Items,
        [string] $Pattern
    )

    return @($Items | Where-Object { $_ -match $Pattern })
}

function Write-Items {
    param(
        [string[]] $Items,
        [string] $Fallback
    )

    if ($Items.Length -eq 0) {
        return "- $Fallback"
    }
    return ($Items | ForEach-Object { "- " + (Normalize-ReleaseItem $_) }) -join [Environment]::NewLine
}

$script:BlockedReleaseRegex = Get-BlockedReleaseRegex
Assert-CleanReleaseText $Tag
Assert-CleanReleaseText $Version
Assert-CleanReleaseText $CommitRange
Assert-CleanReleaseText $DisplayRange
Invoke-ProjectNamingGuard $CommitRange
$commits = Get-CommitSubjects $CommitRange
$rangeFallbackNote = ""
if ($commits.Length -eq 0) {
    $commits = Get-HeadCommitSubject
    $DisplayRange = (& git rev-parse --short HEAD).Trim()
    Assert-CleanReleaseText $DisplayRange
    Invoke-ProjectNamingGuard "HEAD"
    $rangeFallbackNote = @"
空提交范围，已使用当前提交生成发布说明。
Empty commit range; the current commit was used to generate these release notes.

"@
}
foreach ($commit in $commits) {
    Assert-CleanReleaseText $commit
}

$featurePattern = '(^|\b)(feat|feature)(\(.+\))?:|功能|新增|支持|对齐|补齐|完善|实现|add|implement|support|align|complete|improve'
$fixPattern = '(^|\b)(fix|bugfix)(\(.+\))?:|修复|缺陷|问题|异常|错误|失败|回归|bug|fix|bugfix|resolve|repair'
$features = Select-Items $commits $featurePattern
$fixes = Select-Items $commits $fixPattern
$classified = New-Object 'System.Collections.Generic.HashSet[string]'
foreach ($item in $features) {
    [void] $classified.Add($item)
}
foreach ($item in $fixes) {
    [void] $classified.Add($item)
}
$others = @($commits | Where-Object { -not $classified.Contains($_) })

$body = @"
## jimuqu-agent $Tag

本次发布说明按提交类型整理，并保留中英双语摘要；功能、缺陷修复和其他变更来自本次发布范围内的提交摘要。
These release notes are grouped by commit type and keep bilingual summaries; features, fixes, and other changes are derived from commit summaries in this release range.

提交范围：``$DisplayRange``
Commit range: ``$DisplayRange``

$rangeFallbackNote
### 功能 / Features

$(Write-Items $features "本次发布没有单独标记为 feat 的提交。 / No commits were explicitly marked as feat in this release.")

### 缺陷修复 / Fixes

$(Write-Items $fixes "本次发布没有单独标记为 fix 的提交。 / No commits were explicitly marked as fix in this release.")

### 其他变更 / Other Changes

$(Write-Items $others "无其他提交。 / No other commits.")

### 下载内容 / Downloads

- ``jimuqu-agent-$Version.jar``：完整运行包，包含后端依赖与 Dashboard 静态资源。
- ``jimuqu-agent-$Version.jar``: Full runtime package with backend dependencies and Dashboard static assets.
- ``SHA256SUMS``：发布包校验文件。
- ``SHA256SUMS``: Checksums for release artifacts.

### 快速运行 / Quick Start

``````bash
java -jar jimuqu-agent-$Version.jar
``````

服务默认监听 ``http://127.0.0.1:8080``，运行数据会写入当前目录的 ``runtime/``。
The service listens on ``http://127.0.0.1:8080`` by default and writes runtime data to ``runtime/`` in the current directory.
"@

Assert-CleanReleaseText $body
$outputFile = New-Item -ItemType File -Path $OutputPath -Force
[System.IO.File]::WriteAllText($outputFile.FullName, $body, [System.Text.Encoding]::UTF8)
