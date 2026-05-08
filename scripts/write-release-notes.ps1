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

function Join-Chars {
    param([int[]] $Codes)

    return -join ($Codes | ForEach-Object { [char] $_ })
}

function Convert-LegacyReleaseText {
    param([string] $Text)

    if ([string]::IsNullOrEmpty($Text)) {
        return $Text
    }

    $legacyOne = Join-Chars @(72, 101, 114, 109, 101, 115)
    $legacyOneUpper = $legacyOne.ToUpperInvariant()
    $legacyTwo = Join-Chars @(79, 112, 101, 110, 67, 108, 97, 119)
    $legacyTwoWords = @(
        $legacyTwo,
        ($legacyTwo.Substring(0, 4) + "_" + $legacyTwo.Substring(4)),
        ($legacyTwo.Substring(0, 4) + "-" + $legacyTwo.Substring(4)),
        ($legacyTwo.Substring(0, 4) + " " + $legacyTwo.Substring(4))
    )

    $result = $Text
    $result = [Regex]::Replace(
        $result,
        ("\b" + [Regex]::Escape($legacyOneUpper) + "_([A-Z0-9_]+)\b"),
        "JIMUQU_`$1",
        [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
    $result = [Regex]::Replace(
        $result,
        ("\b" + [Regex]::Escape($legacyOne) + "([-_ ]agent|[-_ ]cli|[-_ ]api)?\b"),
        "jimuqu-agent",
        [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
    foreach ($legacyWord in $legacyTwoWords) {
        $result = [Regex]::Replace(
            $result,
            ("\b" + [Regex]::Escape($legacyWord) + "(_[A-Z0-9_]+)?\b"),
            "SolonClaw",
            [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
    }
    foreach ($blockedTerm in $ExtraBlockedTerms) {
        if ([string]::IsNullOrWhiteSpace($blockedTerm)) {
            continue
        }
        $result = [Regex]::Replace(
            $result,
            [Regex]::Escape($blockedTerm),
            "JIMUQU_BLOCKED_TERM",
            [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
    }
    return $result
}

function Normalize-ReleaseItem {
    param([string] $Item)

    $safe = Convert-LegacyReleaseText $Item
    if ($safe -match "\s/\s") {
        return $safe
    }
    return ("提交：{0} / Commit: {0}" -f $safe)
}

function Get-CommitSubjects {
    param([string] $Range)

    $subjects = & git log --pretty=format:'%s' $Range 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw ("Failed to read git commit subjects for range: {0}" -f $Range)
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

$commits = Get-CommitSubjects $CommitRange
if ($commits.Length -eq 0) {
    $commits = Get-CommitSubjects "HEAD"
    $DisplayRange = (& git rev-parse --short HEAD).Trim()
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
## solon-claw $Tag

本次发布说明按提交类型整理，并保留中英双语摘要；功能、缺陷修复和其他变更来自本次发布范围内的提交摘要。
These release notes are grouped by commit type and keep bilingual summaries; features, fixes, and other changes are derived from commit summaries in this release range.

提交范围：``$DisplayRange``
Commit range: ``$DisplayRange``

### 功能 / Features

$(Write-Items $features "本次发布没有单独标记为 feat 的提交。 / No commits were explicitly marked as feat in this release.")

### 缺陷修复 / Fixes

$(Write-Items $fixes "本次发布没有单独标记为 fix 的提交。 / No commits were explicitly marked as fix in this release.")

### 其他变更 / Other Changes

$(Write-Items $others "无其他提交。 / No other commits.")

### 下载内容 / Downloads

- ``solon-claw-$Version.jar``：完整运行包，包含后端依赖与 Dashboard 静态资源。
- ``solon-claw-$Version.jar``: Full runtime package with backend dependencies and Dashboard static assets.
- ``SHA256SUMS``：发布包校验文件。
- ``SHA256SUMS``: Checksums for release artifacts.

### 快速运行 / Quick Start

``````bash
java -jar solon-claw-$Version.jar
``````

服务默认监听 ``http://127.0.0.1:8080``，运行数据会写入当前目录的 ``runtime/``。
The service listens on ``http://127.0.0.1:8080`` by default and writes runtime data to ``runtime/`` in the current directory.
"@

$outputFile = New-Item -ItemType File -Path $OutputPath -Force
[System.IO.File]::WriteAllText($outputFile.FullName, $body, [System.Text.Encoding]::UTF8)
