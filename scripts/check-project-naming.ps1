param(
    [string] $RootPath = (Join-Path $PSScriptRoot ".."),
    [string[]] $ExtraBlockedTerms = @(),
    [string] $GitCommitRange = "",
    [switch] $CheckGitCommitSubjects,
    [switch] $CheckGitObjectText,
    [switch] $CheckAllGitRefs
)

$ErrorActionPreference = "Stop"

$scanRoot = Resolve-Path $RootPath
Push-Location $scanRoot
try {
    function Get-BlockedPatterns {
        $patterns = @(
            "[Hh][Ee][Rr][Mm][Ee][Ss]_?",
            "[Oo][Pp][Ee][Nn](?:[_\-\s])?[Cc][Ll][Aa][Ww][_\-]?"
        )
        foreach ($term in $ExtraBlockedTerms) {
            if (-not [string]::IsNullOrWhiteSpace($term)) {
                $patterns += [Regex]::Escape($term)
            }
        }
        return $patterns
    }

    function Get-GitGrepBlockedPatterns {
        $patterns = @(
            "[Hh][Ee][Rr][Mm][Ee][Ss]_?",
            "[Oo][Pp][Ee][Nn]([_[:space:]-])?[Cc][Ll][Aa][Ww][_ -]?"
        )
        foreach ($term in $ExtraBlockedTerms) {
            if (-not [string]::IsNullOrWhiteSpace($term)) {
                $patterns += [Regex]::Escape($term)
            }
        }
        return $patterns
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
        "coverage"
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
        Write-Host "Blocked legacy project naming in repository paths or text. Use Jimuqu/JIMUQU naming for code, docs, config keys, routes, storage keys, environment variables, and generated source." -ForegroundColor Red
        $findings | ForEach-Object { Write-Host $_ -ForegroundColor Red }
        exit 1
    }

    if ($CheckGitCommitSubjects) {
        $git = Get-Command git -ErrorAction SilentlyContinue
        if ($null -eq $git) {
            Write-Host "git was not found, cannot check commit subjects." -ForegroundColor Red
            exit 1
        }

        $insideWorkTree = (& git rev-parse --is-inside-work-tree 2>$null)
        if ($LASTEXITCODE -ne 0 -or $insideWorkTree -ne "true") {
            Write-Host "Current path is not a git work tree, cannot check commit subjects." -ForegroundColor Red
            exit 1
        }

        $range = $GitCommitRange
        if ($CheckAllGitRefs) {
            $range = "--all"
        } elseif ([string]::IsNullOrWhiteSpace($range)) {
            $range = "HEAD"
        }

        $subjectMatches = New-Object System.Collections.Generic.List[string]
        if ($CheckAllGitRefs) {
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
        if ($CheckAllGitRefs) {
            $range = "--all"
        } elseif ([string]::IsNullOrWhiteSpace($range)) {
            $range = "HEAD"
        }

        if ($CheckAllGitRefs) {
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
