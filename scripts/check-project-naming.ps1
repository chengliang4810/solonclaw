param(
    [string] $RootPath = (Join-Path $PSScriptRoot ".."),
    [string[]] $ExtraBlockedTerms = @(),
    [string] $GitCommitRange = "",
    [switch] $CheckGitCommitSubjects
)

$ErrorActionPreference = "Stop"

$scanRoot = Resolve-Path $RootPath
Push-Location $scanRoot
try {
    $h = (([char]72) + ([char]101) + ([char]114) + ([char]109) + ([char]101) + ([char]115))
    $oc = (([char]79) + ([char]112) + ([char]101) + ([char]110) + ([char]67) + ([char]108) + ([char]97) + ([char]119))
    $terms = @(
        $h,
        ($h + "_"),
        $oc,
        ($oc + "_"),
        (([char]79) + ([char]112) + ([char]101) + ([char]110) + "_" + ([char]67) + ([char]108) + ([char]97) + ([char]119)),
        (([char]79) + ([char]112) + ([char]101) + ([char]110) + "_" + ([char]67) + ([char]108) + ([char]97) + ([char]119) + "_"),
        (([char]79) + ([char]112) + ([char]101) + ([char]110) + "-" + ([char]67) + ([char]108) + ([char]97) + ([char]119)),
        (([char]79) + ([char]112) + ([char]101) + ([char]110) + "-" + ([char]67) + ([char]108) + ([char]97) + ([char]119) + "-"),
        (([char]79) + ([char]112) + ([char]101) + ([char]110) + " " + ([char]67) + ([char]108) + ([char]97) + ([char]119))
    )
    foreach ($term in $ExtraBlockedTerms) {
        if (-not [string]::IsNullOrWhiteSpace($term)) {
            $terms += $term
        }
    }
    $regex = [Regex]::new(
        (($terms | ForEach-Object { [Regex]::Escape($_) }) -join "|"),
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

    function Search-Directory {
        param([System.IO.DirectoryInfo] $Directory)

        Get-ChildItem -LiteralPath $Directory.FullName -Force | ForEach-Object {
            $relativePath = [System.IO.Path]::GetRelativePath($scanRoot, $_.FullName)
            if (Test-IgnoredPath $relativePath) {
                return
            }
            if ($regex.IsMatch($relativePath)) {
                $matches.Add(("{0}:0:<path>" -f $relativePath))
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
                    $matches.Add(("{0}:{1}:{2}" -f $relativePath, $lineNumber, $line))
                }
            }
        }
    }

    $matches = New-Object System.Collections.Generic.List[string]
    Search-Directory (Get-Item -LiteralPath $scanRoot)

    if ($matches) {
        Write-Host "Blocked legacy project naming in repository paths or text. Use Jimuqu/JIMUQU naming for code, docs, config keys, routes, storage keys, environment variables, and generated source." -ForegroundColor Red
        $matches | ForEach-Object { Write-Host $_ -ForegroundColor Red }
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
        if ([string]::IsNullOrWhiteSpace($range)) {
            $range = "HEAD"
        }

        $subjectMatches = New-Object System.Collections.Generic.List[string]
        $subjects = & git log --format="%h %s" $range 2>$null
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
            $subjectMatches | ForEach-Object { Write-Host $_ -ForegroundColor Red }
            exit 1
        }
    }
} finally {
    Pop-Location
}
