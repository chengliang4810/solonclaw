#!/usr/bin/env python3
from __future__ import annotations

import argparse
import sys
import tempfile
import zipfile
from pathlib import Path

from guardlib import (
    GuardFailure,
    add_extra_blocked_terms_argument,
    parse_extra_blocked_terms,
    scan_directory,
    temp_prefix,
)

FIRST_PARTY_FILES = {
    "META-INF/MANIFEST.MF",
    "app.yml",
    "config.example.yml",
    "logback-solon.xml",
}
FIRST_PARTY_PREFIXES = (
    "com/jimuqu/solon/claw/",
    "persona-templates/",
    "plugins/",
    "static/",
)


def is_first_party_archive_path(relative_path: str) -> bool:
    normalized = relative_path.replace("\\", "/").lstrip("/")
    if normalized in FIRST_PARTY_FILES:
        return True
    return any(normalized.startswith(prefix) for prefix in FIRST_PARTY_PREFIXES)


def check_archive_naming(archive_paths: list[str], extra_terms: list[str]) -> None:
    for archive in archive_paths:
        if not archive.strip():
            continue
        archive_path = Path(archive).resolve()
        with tempfile.TemporaryDirectory(prefix=temp_prefix("archive-naming")) as temp_dir:
            temp_path = Path(temp_dir)
            extract_path = temp_path / "extract"
            scan_path = temp_path / "scan"
            extract_path.mkdir()
            scan_path.mkdir()
            try:
                with zipfile.ZipFile(archive_path) as zip_file:
                    zip_file.extractall(extract_path)
            except zipfile.BadZipFile as exc:
                raise GuardFailure(f"Archive could not be read: {archive_path}") from exc
            for path in extract_path.rglob("*"):
                if path.is_dir():
                    continue
                relative_path = path.relative_to(extract_path).as_posix()
                if not is_first_party_archive_path(relative_path):
                    continue
                target_path = scan_path / relative_path
                target_path.parent.mkdir(parents=True, exist_ok=True)
                target_path.write_bytes(path.read_bytes())
            findings = scan_directory(scan_path, extra_blocked_terms=extra_terms, check_binary_text=True)
            if findings:
                raise GuardFailure("Archive naming guard failed for: {0}\n{1}".format(archive_path, "\n".join(findings)))


def main() -> int:
    parser = argparse.ArgumentParser(description="Check naming inside archive artifacts.")
    parser.add_argument("--archive-path", nargs="+", required=True)
    add_extra_blocked_terms_argument(parser)
    args = parser.parse_args()
    check_archive_naming(args.archive_path, parse_extra_blocked_terms(args.extra_blocked_terms))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except GuardFailure as exc:
        print(str(exc), file=sys.stderr)
        raise SystemExit(1)
