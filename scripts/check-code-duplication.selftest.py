#!/usr/bin/env python3
from __future__ import annotations

import shutil
import subprocess
import tempfile
from pathlib import Path

from guardlib import REPO_ROOT, temp_prefix

SCRIPT_PATH = REPO_ROOT / "scripts" / "check-code-duplication.py"


def run_check(sandbox: Path, *extra_args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["python3", str(SCRIPT_PATH), "--root-path", str(sandbox), *extra_args],
        cwd=str(REPO_ROOT),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )


def write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def main() -> int:
    sandbox = Path(tempfile.mkdtemp(prefix=temp_prefix("code-duplication-selftest")))
    try:
        write_text(
            sandbox / "src" / "main" / "java" / "example" / "First.java",
            """package example;

class First {
    void run() {
        String name = "solonclaw";
        String mode = name.trim();
        System.out.println(mode);
    }
}
""",
        )
        write_text(
            sandbox / "src" / "main" / "java" / "example" / "Second.java",
            """package example;

class Second {
    void run() {
        String name = "solonclaw";
        String mode = name.trim();
        System.out.println(mode);
    }
}
""",
        )
        duplicated = run_check(sandbox, "--min-lines", "3")
        if duplicated.returncode == 0:
            raise AssertionError("Duplicate detector should fail when exact duplicate blocks exist.")
        if "First.java" not in duplicated.stderr or "Second.java" not in duplicated.stderr:
            raise AssertionError("Duplicate detector did not report both duplicate locations: " + duplicated.stderr)

        report_only = run_check(sandbox, "--min-lines", "3", "--report-only")
        if report_only.returncode != 0:
            raise AssertionError("Report-only duplicate scan should not fail.")

        shutil.rmtree(sandbox)
        sandbox.mkdir(parents=True)
        write_text(
            sandbox / "src" / "main" / "java" / "example" / "Unique.java",
            """package example;

class Unique {
    void run() {
        String name = "solonclaw";
        String mode = name.toUpperCase();
        System.out.println(mode);
    }
}
""",
        )
        unique = run_check(sandbox, "--min-lines", "3")
        if unique.returncode != 0:
            raise AssertionError("Duplicate detector should allow unique code, but failed: " + unique.stderr)
        return 0
    finally:
        shutil.rmtree(sandbox, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
