#!/usr/bin/env python3
from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parent.parent
SCRIPT_PATH = REPO_ROOT / "scripts" / "check-raw-exception-logging.py"


def run_check(sandbox: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["python3", str(SCRIPT_PATH), "--root-path", str(sandbox)],
        cwd=str(REPO_ROOT),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )


def reset_sandbox(sandbox: Path) -> Path:
    if sandbox.exists():
        shutil.rmtree(sandbox)
    source_dir = sandbox / "src" / "main" / "java" / "example"
    source_dir.mkdir(parents=True)
    return source_dir


def write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def main() -> int:
    sandbox = Path(tempfile.mkdtemp(prefix="jimuqu-raw-exception-logging-selftest-"))
    try:
        source_dir = reset_sandbox(sandbox)
        write_text(
            source_dir / "BadInline.java",
            """package example;

class BadInline {
    void run(org.slf4j.Logger log, Exception e) {
        log.warn("failed", e);
    }
}
""",
        )
        bad_inline = run_check(sandbox)
        if bad_inline.returncode == 0:
            raise AssertionError("Raw exception logging check did not block inline Throwable logging.")

        source_dir = reset_sandbox(sandbox)
        write_text(
            source_dir / "BadMultiline.java",
            """package example;

class BadMultiline {
    void run(org.slf4j.Logger log, String id, Throwable throwable) {
        log.error(
                "failed id={}",
                id,
                throwable);
    }
}
""",
        )
        bad_multiline = run_check(sandbox)
        if bad_multiline.returncode == 0:
            raise AssertionError("Raw exception logging check did not block multiline Throwable logging.")

        source_dir = reset_sandbox(sandbox)
        write_text(
            source_dir / "GoodRedacted.java",
            """package example;

class GoodRedacted {
    void run(org.slf4j.Logger log, Exception e) {
        log.warn("failed: {}", safeError(e));
    }

    private String safeError(Exception e) {
        return e.getClass().getSimpleName();
    }
}
""",
        )
        good_redacted = run_check(sandbox)
        if good_redacted.returncode != 0:
            raise AssertionError("Raw exception logging check should allow redacted logging, but failed: " + good_redacted.stderr)

        source_dir = reset_sandbox(sandbox)
        write_text(
            source_dir / "GoodThrow.java",
            """package example;

class GoodThrow {
    void run(Exception e) throws Exception {
        throw e;
    }
}
""",
        )
        good_throw = run_check(sandbox)
        if good_throw.returncode != 0:
            raise AssertionError("Raw exception logging check should ignore non-logger Throwable usage, but failed: " + good_throw.stderr)
        return 0
    finally:
        shutil.rmtree(sandbox, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
