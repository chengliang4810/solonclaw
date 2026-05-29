param(
    [string] $RootPath = (Join-Path $PSScriptRoot ".."),
    [string[]] $ExtraBlockedTerms = @(),
    [string] $GitCommitRange = "",
    [switch] $CheckGitCommitSubjects,
    [switch] $CheckGitObjectText,
    [switch] $CheckAllGitRefs,
    [switch] $CheckCurrentBranchRange,
    [switch] $CheckBinaryText
)

# Normal validation should use the working tree plus an explicit commit range, or
# -CheckCurrentBranchRange for the commits ahead of the default branch. The
# all-ref mode is intentionally reserved for manual audits of old refs/tags.

$ErrorActionPreference = "Stop"

$scanRoot = Resolve-Path $RootPath
Push-Location $scanRoot
try {
    function Get-BlockedPatterns {
        $firstExternalName = "[Hh][Ee][Rr][Mm][Ee][Ss]"
        $secondExternalName = "[Oo][Pp][Ee][Nn](?:[_\-\.\s])?[Cc][Ll][Aa][Ww]"
        $patterns = @()
        $patterns += ($firstExternalName + "_?")
        $patterns += ($secondExternalName + "[_\-]?")
        $patterns += "[Bb][Aa][Dd][_\-.]?[Ll][Ee][Gg][Aa][Cc][Yy][_\-.]?[Pp][Rr][Ee][Ff][Ii][Xx](?:[_\-.])"
        $patterns += ($firstExternalName + "(?:[_\-.])")
        $patterns += ($secondExternalName + "(?:[_\-.])")
        foreach ($term in $ExtraBlockedTerms) {
            if (-not [string]::IsNullOrWhiteSpace($term)) {
                $patterns += [Regex]::Escape($term)
            }
        }
        return $patterns
    }

    function Get-GitGrepBlockedPatterns {
        $firstExternalName = "[Hh][Ee][Rr][Mm][Ee][Ss]"
        $secondExternalName = "[Oo][Pp][Ee][Nn]([_[:space:].-])?[Cc][Ll][Aa][Ww]"
        $patterns = @()
        $patterns += ($firstExternalName + "_?")
        $patterns += ($secondExternalName + "[_ -]?")
        $patterns += "[Bb][Aa][Dd][_.-]?[Ll][Ee][Gg][Aa][Cc][Yy][_.-]?[Pp][Rr][Ee][Ff][Ii][Xx]([_.-])"
        $patterns += ($firstExternalName + "([_.-])")
        $patterns += ($secondExternalName + "([_.-])")
        foreach ($term in $ExtraBlockedTerms) {
            if (-not [string]::IsNullOrWhiteSpace($term)) {
                $patterns += [Regex]::Escape($term)
            }
        }
        return $patterns
    }

    function Get-DefaultBranchRange {
        $git = Get-Command git -ErrorAction SilentlyContinue
        if ($null -eq $git) {
            Write-Host "git was not found, cannot resolve the current branch range." -ForegroundColor Red
            exit 1
        }

        $insideWorkTree = (& git rev-parse --is-inside-work-tree 2>$null)
        if ($LASTEXITCODE -ne 0 -or $insideWorkTree -ne "true") {
            Write-Host "Current path is not a git work tree, cannot resolve the current branch range." -ForegroundColor Red
            exit 1
        }

        $head = ((& git rev-parse HEAD 2>$null) | Select-Object -First 1)
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($head)) {
            Write-Host "Cannot resolve HEAD for current branch range." -ForegroundColor Red
            exit 1
        }
        $head = ($head -as [string]).Trim()

        $baseCandidates = @("origin/main", "main", "origin/master", "master")
        foreach ($candidate in $baseCandidates) {
            & git rev-parse -q --verify $candidate 2>$null | Out-Null
            if ($LASTEXITCODE -ne 0) {
                continue
            }
            $mergeBase = ((& git merge-base $candidate HEAD 2>$null) | Select-Object -First 1)
            if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace($mergeBase)) {
                $mergeBase = ($mergeBase -as [string]).Trim()
                if ($mergeBase -eq $head) {
                    return ""
                }
                return ("{0}..{1}" -f $mergeBase, $head)
            }
        }

        Write-Host "Cannot resolve a default branch base for current branch range." -ForegroundColor Red
        exit 1
    }

    function Resolve-GitRange {
        param([string] $Range)

        $value = [string] $Range
        if ($value -match '^([0-9a-fA-F]{7,40})\^\.\.\1$') {
            $head = $matches[1]
            & git rev-parse -q --verify "$head^" 2>$null | Out-Null
            if ($LASTEXITCODE -ne 0) {
                return $head
            }
        }
        return $value
    }

    $blockedPatterns = Get-BlockedPatterns
    $regex = [Regex]::new(
        ($blockedPatterns -join "|"),
        [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)

    $ignoredDirs = @(
        ".git",
        ".idea",
        "node_modules",
        ".gradle",
        ".mvn",
        ".turbo",
        ".vite",
        "coverage",
        "target"
    )
    $ignoredFiles = @(
        "package-lock.json",
        "pnpm-lock.yaml",
        "yarn.lock"
    )

    function Test-IgnoredPath {
        param([string] $RelativePath)

        $parts = $RelativePath -split '[\\/]+' | Where-Object { $_ -ne "" }
        foreach ($part in $parts) {
            if ($ignoredDirs -contains $part) {
                return $true
            }
        }
        if ($parts.Length -gt 0 -and ($ignoredFiles -contains $parts[$parts.Length - 1])) {
            return $true
        }
        return $false
    }

    function Test-ProbablyTextFile {
        param([System.IO.FileInfo] $File)

        $binaryExtensions = @(
            ".7z",
            ".avif",
            ".bmp",
            ".class",
            ".db",
            ".dll",
            ".exe",
            ".gif",
            ".ico",
            ".jar",
            ".jpeg",
            ".jpg",
            ".mp3",
            ".mp4",
            ".pdf",
            ".png",
            ".so",
            ".sqlite",
            ".ttf",
            ".wasm",
            ".webm",
            ".webp",
            ".woff",
            ".woff2",
            ".zip"
        )
        if ($binaryExtensions -contains $File.Extension.ToLowerInvariant()) {
            return $false
        }
        if ($File.Length -gt 10MB) {
            return $false
        }
        $stream = [System.IO.File]::OpenRead($File.FullName)
        try {
            $buffer = New-Object byte[] ([Math]::Min(4096, [int] $File.Length))
            $read = $stream.Read($buffer, 0, $buffer.Length)
            for ($i = 0; $i -lt $read; $i++) {
                if ($buffer[$i] -eq 0) {
                    return $false
                }
            }
            return $true
        } finally {
            $stream.Dispose()
        }
    }

    function Search-BinaryFile {
        param(
            [System.IO.FileInfo] $File,
            [string] $RelativePath
        )

        if ($File.Length -gt 100MB) {
            return
        }

        $bytes = [System.IO.File]::ReadAllBytes($File.FullName)
        if ($bytes.Length -eq 0) {
            return
        }

        $latin1 = [System.Text.Encoding]::GetEncoding(28591).GetString($bytes)
        if ($regex.IsMatch($latin1)) {
            $findings.Add(("{0}:0:<binary>" -f (Hide-BlockedText $RelativePath)))
        }
    }

    function Hide-BlockedText {
        param([string] $Text)

        if ([string]::IsNullOrEmpty($Text)) {
            return $Text
        }
        return $regex.Replace($Text, "<blocked>")
    }

    function Search-Directory {
        param([System.IO.DirectoryInfo] $Directory)

        Get-ChildItem -LiteralPath $Directory.FullName -Force | ForEach-Object {
            $relativePath = [System.IO.Path]::GetRelativePath($scanRoot, $_.FullName)
            if (Test-IgnoredPath $relativePath) {
                return
            }
            if ($regex.IsMatch($relativePath)) {
                $findings.Add(("{0}:0:<path>" -f (Hide-BlockedText $relativePath)))
            }
            if ($_.PSIsContainer) {
                Search-Directory $_
                return
            }
            if (-not (Test-ProbablyTextFile $_)) {
                if ($CheckBinaryText) {
                    Search-BinaryFile $_ $relativePath
                }
                return
            }

            $lineNumber = 0
            foreach ($line in [System.IO.File]::ReadLines($_.FullName)) {
                $lineNumber++
                if ($regex.IsMatch($line)) {
                    $findings.Add(("{0}:{1}:<blocked>" -f (Hide-BlockedText $relativePath), $lineNumber))
                }
            }
        }
    }

    $findings = New-Object System.Collections.Generic.List[string]
    Search-Directory (Get-Item -LiteralPath $scanRoot)

    if ($findings) {
        Write-Host "Blocked legacy project naming in repository paths or text. Use Jimuqu/JIMUQU naming for code, docs, config keys, routes, storage keys, environment variables, generated source, and release artifacts." -ForegroundColor Red
        $findings | ForEach-Object { Write-Host $_ -ForegroundColor Red }
        exit 1
    }

    if ($CheckGitCommitSubjects) {
        $git = Get-Command git -ErrorAction SilentlyContinue
        if ($null -eq $git) {
            Write-Host "git was not found, cannot check commit text." -ForegroundColor Red
            exit 1
        }

        $insideWorkTree = (& git rev-parse --is-inside-work-tree 2>$null)
        if ($LASTEXITCODE -ne 0 -or $insideWorkTree -ne "true") {
            Write-Host "Current path is not a git work tree, cannot check commit text." -ForegroundColor Red
            exit 1
        }

        $range = $GitCommitRange
        $skipRangeCheck = $false
        if ($CheckAllGitRefs) {
            $range = "--all"
        } elseif ($CheckCurrentBranchRange) {
            $range = Get-DefaultBranchRange
            $skipRangeCheck = [string]::IsNullOrWhiteSpace($range)
        } elseif ([string]::IsNullOrWhiteSpace($range)) {
            $range = "HEAD"
        }
        $range = Resolve-GitRange -Range $range

        $subjectMatches = New-Object System.Collections.Generic.List[string]
        if ($skipRangeCheck) {
            $subjects = @()
        } elseif ($CheckAllGitRefs) {
            $subjects = & git log --all --format="%h %s" 2>$null
        } else {
            $subjects = & git log --format="%h %s" $range 2>$null
        }
        if ($LASTEXITCODE -ne 0) {
            Write-Host ("Failed to read git commit subjects for range: {0}" -f $range) -ForegroundColor Red
            exit 1
        }
        foreach ($subject in $subjects) {
            if ($regex.IsMatch($subject)) {
                $subjectMatches.Add($subject)
            }
        }

        if ($subjectMatches) {
            Write-Host "Blocked legacy project naming in git commit subjects. Rewrite or replace the commit subject before publishing release notes." -ForegroundColor Red
            $subjectMatches | ForEach-Object { Write-Host (Hide-BlockedText $_) -ForegroundColor Red }
            exit 1
        }

        $messageMatches = New-Object System.Collections.Generic.List[string]
        if ($skipRangeCheck) {
            $messageCommits = @()
        } elseif ($CheckAllGitRefs) {
            $messageCommits = & git log --all --format="%H" 2>$null
        } else {
            $messageCommits = & git log --format="%H" $range 2>$null
        }
        if ($LASTEXITCODE -ne 0) {
            Write-Host ("Failed to read git commits for message scan range: {0}" -f $range) -ForegroundColor Red
            exit 1
        }
        foreach ($commit in $messageCommits) {
            if ([string]::IsNullOrWhiteSpace($commit)) {
                continue
            }
            $commit = ($commit -as [string]).Trim()
            if ($commit -notmatch '^[0-9a-fA-F]{7,40}$') {
                Write-Host "Unexpected git commit id while scanning commit messages." -ForegroundColor Red
                exit 1
            }
            $message = (& git log -1 --format="%B" $commit 2>$null) | Out-String
            if ($LASTEXITCODE -ne 0) {
                Write-Host ("Failed to read git commit message for commit: {0}" -f $commit) -ForegroundColor Red
                exit 1
            }
            if ($regex.IsMatch($message)) {
                $messageMatches.Add(("{0}:<blocked>" -f $commit.Substring(0, 12)))
            }
        }

        if ($messageMatches) {
            Write-Host "Blocked legacy project naming in git commit messages. Rewrite or replace the commit before publishing release notes." -ForegroundColor Red
            $messageMatches | ForEach-Object { Write-Host $_ -ForegroundColor Red }
            exit 1
        }
    }

    if ($CheckGitObjectText) {
        $git = Get-Command git -ErrorAction SilentlyContinue
        if ($null -eq $git) {
            Write-Host "git was not found, cannot check git object text." -ForegroundColor Red
            exit 1
        }

        $insideWorkTree = (& git rev-parse --is-inside-work-tree 2>$null)
        if ($LASTEXITCODE -ne 0 -or $insideWorkTree -ne "true") {
            Write-Host "Current path is not a git work tree, cannot check git object text." -ForegroundColor Red
            exit 1
        }

        $range = $GitCommitRange
        $skipRangeCheck = $false
        if ($CheckAllGitRefs) {
            $range = "--all"
        } elseif ($CheckCurrentBranchRange) {
            $range = Get-DefaultBranchRange
            $skipRangeCheck = [string]::IsNullOrWhiteSpace($range)
        } elseif ([string]::IsNullOrWhiteSpace($range)) {
            $range = "HEAD"
        }
        $range = Resolve-GitRange -Range $range

        if ($skipRangeCheck) {
            $commits = @()
        } elseif ($CheckAllGitRefs) {
            $commits = & git rev-list --all 2>$null
        } else {
            $commits = & git rev-list $range 2>$null
        }
        if ($LASTEXITCODE -ne 0) {
            Write-Host ("Failed to read git commits for range: {0}" -f $range) -ForegroundColor Red
            exit 1
        }

        $objectMatches = New-Object System.Collections.Generic.List[string]
        $objectMatchCount = 0
        $objectMatchLimit = 200
        $grepArgs = @("grep", "-I", "-n", "-i", "-E")
        foreach ($pattern in (Get-GitGrepBlockedPatterns)) {
            $grepArgs += @("-e", $pattern)
        }
        foreach ($commit in $commits) {
            if ([string]::IsNullOrWhiteSpace($commit)) {
                continue
            }
            $grepOutput = & git @grepArgs $commit -- . ":(exclude).git/**" ":(exclude)web/node_modules/**" ":(exclude)node_modules/**" ":(exclude)target/**" 2>$null
            $exitCode = $LASTEXITCODE
            if ($exitCode -ne 0 -and $exitCode -ne 1) {
                Write-Host ("Failed to scan git object text for commit: {0}" -f $commit) -ForegroundColor Red
                exit 1
            }
            foreach ($line in $grepOutput) {
                if ([string]::IsNullOrWhiteSpace($line)) {
                    continue
                }
                $objectMatchCount++
                $parts = $line -split ":", 4
                if ($objectMatches.Count -lt $objectMatchLimit) {
                    if ($parts.Length -ge 3) {
                        $objectMatches.Add(("{0}:{1}:{2}:<blocked>" -f $parts[0], $parts[1], $parts[2]))
                    } else {
                        $objectMatches.Add(("{0}:<blocked>" -f $commit))
                    }
                }
            }
        }

        if ($objectMatchCount -gt 0) {
            Write-Host "Blocked legacy project naming in git object text. Rewrite the range, remove the polluted release range, or publish from a clean range before generating release notes." -ForegroundColor Red
            Write-Host ("Total blocked git object text matches: {0}" -f $objectMatchCount) -ForegroundColor Red
            $objectMatches | ForEach-Object { Write-Host $_ -ForegroundColor Red }
            if ($objectMatchCount -gt $objectMatches.Count) {
                Write-Host ("Additional matches omitted: {0}" -f ($objectMatchCount - $objectMatches.Count)) -ForegroundColor Red
            }
            exit 1
        }
    }
} finally {
    Pop-Location
}

$global:LASTEXITCODE = 0
