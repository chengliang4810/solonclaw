param(
    [Parameter(Mandatory = $true)]
    [string[]] $ArchivePath,
    [string[]] $ExtraBlockedTerms = @()
)

$ErrorActionPreference = "Stop"

$guardPath = Join-Path $PSScriptRoot "check-project-naming.ps1"
if (-not (Test-Path -LiteralPath $guardPath)) {
    throw "Project naming guard script was not found."
}

Add-Type -AssemblyName System.IO.Compression.FileSystem

foreach ($archive in $ArchivePath) {
    if ([string]::IsNullOrWhiteSpace($archive)) {
        continue
    }
    $resolvedArchive = Resolve-Path -LiteralPath $archive
    $tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("jimuqu-archive-naming-" + [Guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path $tempDir | Out-Null
    try {
        [System.IO.Compression.ZipFile]::ExtractToDirectory($resolvedArchive.Path, $tempDir)
        $guardArgs = @(
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            $guardPath,
            "-RootPath",
            $tempDir,
            "-CheckBinaryText"
        )
        $cleanExtraBlockedTerms = @($ExtraBlockedTerms | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
        if ($cleanExtraBlockedTerms.Length -gt 0) {
            $guardArgs += @("-ExtraBlockedTerms")
            $guardArgs += $cleanExtraBlockedTerms
        }
        $guardOutput = & pwsh @guardArgs 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw ("Archive naming guard failed for: {0}`n{1}" -f $resolvedArchive.Path, ($guardOutput | Out-String))
        }
    } finally {
        if (Test-Path -LiteralPath $tempDir) {
            Remove-Item -LiteralPath $tempDir -Recurse -Force
        }
    }
}
