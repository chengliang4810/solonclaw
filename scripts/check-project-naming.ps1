$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Push-Location $repoRoot
try {
    $terms = @(
        (([char]72) + ([char]101) + ([char]114) + ([char]109) + ([char]101) + ([char]115)),
        (([char]79) + ([char]112) + ([char]101) + ([char]110) + ([char]67) + ([char]108) + ([char]97) + ([char]119))
    )
    $pattern = ($terms | ForEach-Object { [Regex]::Escape($_) }) -join "|"
    $matches = git grep -n -I -i -E -- $pattern -- . 2>$null
    if ($LASTEXITCODE -eq 1) {
        exit 0
    }
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to scan tracked project files."
    }
    if ($matches) {
        Write-Error "Legacy project naming was found in tracked files. Use Jimuqu naming for code, docs, config, routes, storage keys, and environment variables."
        $matches | ForEach-Object { Write-Error $_ }
        exit 1
    }
} finally {
    Pop-Location
}
