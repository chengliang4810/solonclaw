#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

from guardlib import REPO_ROOT


LOGGER_CALL_RE = re.compile(r"log\.(trace|debug|info|warn|error)\s*\(")
RAW_THROWABLE_RE = re.compile(r",\s*(e|ex|t|throwable)\s*\)\s*;")


def check_raw_exception_logging(root_path: Path) -> list[str]:
    source_root = root_path / "src" / "main" / "java"
    if not source_root.exists():
        return []

    findings: list[str] = []
    for java_file in sorted(source_root.rglob("*.java")):
        relative_path = java_file.relative_to(root_path).as_posix()
        lines = java_file.read_text(encoding="utf-8", errors="ignore").splitlines()
        for index, line in enumerate(lines):
            if not LOGGER_CALL_RE.search(line):
                continue
            buffer: list[str] = []
            for next_line in lines[index : min(len(lines), index + 12)]:
                buffer.append(next_line.strip())
                if re.search(r"\)\s*;", next_line):
                    break
            if RAW_THROWABLE_RE.search(" ".join(buffer)):
                findings.append(f"{relative_path}:{index + 1}: raw Throwable passed to logger")
    return findings


def main() -> int:
    parser = argparse.ArgumentParser(description="Block raw Throwable logging.")
    parser.add_argument("--root-path", default=str(REPO_ROOT))
    args = parser.parse_args()

    findings = check_raw_exception_logging(Path(args.root_path).resolve())
    if findings:
        print("Raw exception logging is blocked. Use SecretRedactor/safeError and log only redacted strings.", file=sys.stderr)
        for finding in findings:
            print(finding, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
