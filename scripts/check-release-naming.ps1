param(
    [string] $Repository = $env:GITHUB_REPOSITORY,
    [string] $Tag = "",
    [string] $Token = $env:GITHUB_TOKEN,
    [string] $ApiBaseUrl = "https://api.github.com",
    [string] $LocalJsonPath = ""
)

$ErrorActionPreference = "Stop"

function Get-ReleaseData {
    if (-not [string]::IsNullOrWhiteSpace($LocalJsonPath)) {
        if (-not (Test-Path -LiteralPath $LocalJsonPath)) {
            throw ("Local release JSON was not found: {0}" -f $LocalJsonPath)
        }
        return Get-Content -LiteralPath $LocalJsonPath -Raw -Encoding UTF8 | ConvertFrom-Json
    }

    if ([string]::IsNullOrWhiteSpace($Repository)) {
        throw "Repository is required."
    }
    if ([string]::IsNullOrWhiteSpace($Tag)) {
        throw "Tag is required."
    }
    if ([string]::IsNullOrWhiteSpace($Token)) {
        throw "Token is required."
    }

    $encodedTag = [System.Uri]::EscapeDataString($Tag)
    $apiBase = $ApiBaseUrl.TrimEnd("/")
    $uri = "{0}/repos/{1}/releases/tags/{2}" -f $apiBase, $Repository, $encodedTag
    $headers = @{
        Authorization = "Bearer $Token"
        Accept = "application/vnd.github+json"
        "X-GitHub-Api-Version" = "2022-11-28"
    }
    return Invoke-RestMethod -Method Get -Uri $uri -Headers $headers
}

function Write-ReleaseSnapshot {
    param(
        [object] $Release,
        [string] $OutputPath
    )

    $assets = @()
    foreach ($asset in @($Release.assets)) {
        if ($null -eq $asset) {
            continue
        }
        $assets += [PSCustomObject]@{
            name = $asset.name
            label = $asset.label
            content_type = $asset.content_type
        }
    }

    $snapshot = [PSCustomObject]@{
        name = $Release.name
        tag_name = $Release.tag_name
        body = $Release.body
        assets = $assets
    }
    $snapshot | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $OutputPath -Encoding UTF8
}

$release = Get-ReleaseData
$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("jimuqu-release-naming-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $tempDir | Out-Null
try {
    $snapshotPath = Join-Path $tempDir "release.json"
    Write-ReleaseSnapshot -Release $release -OutputPath $snapshotPath

    $guardPath = Join-Path $PSScriptRoot "check-project-naming.ps1"
    $guardOutput = & pwsh -NoProfile -ExecutionPolicy Bypass -File $guardPath -RootPath $tempDir 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw ("Release naming guard failed for published release metadata.`n{0}" -f ($guardOutput | Out-String))
    }
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}
