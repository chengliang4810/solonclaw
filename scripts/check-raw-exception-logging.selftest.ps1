$ErrorActionPreference = "Stop"
$originalNativeCommandUseErrorActionPreference = $PSNativeCommandUseErrorActionPreference
$PSNativeCommandUseErrorActionPreference = $false

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$scriptPath = Join-Path $repoRoot "scripts\check-raw-exception-logging.ps1"
$sandbox = Join-Path ([System.IO.Path]::GetTempPath()) ("jimuqu-raw-exception-logging-selftest-" + [Guid]::NewGuid().ToString("N"))

function Invoke-RawExceptionLoggingCheck {
    $output = & pwsh -NoProfile -ExecutionPolicy Bypass -File $scriptPath -RootPath $sandbox 2>&1
    return @{
        ExitCode = $LASTEXITCODE
        Output = ($output | Out-String)
    }
}

function Reset-Sandbox {
    if (Test-Path -LiteralPath $sandbox) {
        Remove-Item -LiteralPath $sandbox -Recurse -Force
    }
    New-Item -ItemType Directory -Path (Join-Path $sandbox "src\main\java\example") -Force | Out-Null
}

Push-Location $repoRoot
try {
    Reset-Sandbox
    Set-Content -Path (Join-Path $sandbox "src\main\java\example\BadInline.java") -Value @"
package example;

class BadInline {
    void run(org.slf4j.Logger log, Exception e) {
        log.warn("failed", e);
    }
}
"@ -Encoding UTF8
    $badInline = Invoke-RawExceptionLoggingCheck
    if ($badInline.ExitCode -eq 0) {
        throw "Raw exception logging check did not block inline Throwable logging."
    }

    Reset-Sandbox
    Set-Content -Path (Join-Path $sandbox "src\main\java\example\BadMultiline.java") -Value @"
package example;

class BadMultiline {
    void run(org.slf4j.Logger log, String id, Throwable throwable) {
        log.error(
                "failed id={}",
                id,
                throwable);
    }
}
"@ -Encoding UTF8
    $badMultiline = Invoke-RawExceptionLoggingCheck
    if ($badMultiline.ExitCode -eq 0) {
        throw "Raw exception logging check did not block multiline Throwable logging."
    }

    Reset-Sandbox
    Set-Content -Path (Join-Path $sandbox "src\main\java\example\GoodRedacted.java") -Value @"
package example;

class GoodRedacted {
    void run(org.slf4j.Logger log, Exception e) {
        log.warn("failed: {}", safeError(e));
    }

    private String safeError(Exception e) {
        return e.getClass().getSimpleName();
    }
}
"@ -Encoding UTF8
    $goodRedacted = Invoke-RawExceptionLoggingCheck
    if ($goodRedacted.ExitCode -ne 0) {
        throw "Raw exception logging check should allow redacted logging, but failed: $($goodRedacted.Output)"
    }

    Reset-Sandbox
    Set-Content -Path (Join-Path $sandbox "src\main\java\example\GoodThrow.java") -Value @"
package example;

class GoodThrow {
    void run(Exception e) throws Exception {
        throw e;
    }
}
"@ -Encoding UTF8
    $goodThrow = Invoke-RawExceptionLoggingCheck
    if ($goodThrow.ExitCode -ne 0) {
        throw "Raw exception logging check should ignore non-logger Throwable usage, but failed: $($goodThrow.Output)"
    }
} finally {
    Pop-Location
    if (Test-Path -LiteralPath $sandbox) {
        Remove-Item -LiteralPath $sandbox -Recurse -Force
    }
    $PSNativeCommandUseErrorActionPreference = $originalNativeCommandUseErrorActionPreference
    $global:LASTEXITCODE = 0
}
