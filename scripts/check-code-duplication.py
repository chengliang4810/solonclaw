#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
import sys
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path

from guardlib import REPO_ROOT, is_ignored_path, probably_text_file


CODE_EXTENSIONS = {
    ".css",
    ".html",
    ".java",
    ".js",
    ".jsx",
    ".mjs",
    ".scss",
    ".ts",
    ".tsx",
    ".vue",
}
COMMENT_PREFIXES = ("//", "/*", "*", "*/", "<!--", "-->")


@dataclass(frozen=True)
class CodeLine:
    line_number: int
    text: str


@dataclass(frozen=True)
class DuplicateLocation:
    path: str
    start_line: int
    end_line: int


def is_comment_only(line: str) -> bool:
    return any(line.startswith(prefix) for prefix in COMMENT_PREFIXES)


def normalize_code_lines(path: Path) -> list[CodeLine]:
    lines: list[CodeLine] = []
    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8", errors="ignore").splitlines(), start=1):
        stripped = raw_line.strip()
        if not stripped or is_comment_only(stripped):
            continue
        lines.append(CodeLine(line_number, re.sub(r"\s+", " ", stripped)))
    return lines


def iter_code_files(root_path: Path, requested_paths: list[str]) -> list[Path]:
    start_paths = [root_path / value for value in requested_paths] if requested_paths else [root_path]
    files: list[Path] = []
    for start_path in start_paths:
        if start_path.is_file():
            candidates = [start_path]
        elif start_path.is_dir():
            candidates = sorted(start_path.rglob("*"))
        else:
            continue
        for candidate in candidates:
            if not candidate.is_file() or candidate.suffix.lower() not in CODE_EXTENSIONS:
                continue
            relative_path = candidate.relative_to(root_path).as_posix()
            if is_ignored_path(relative_path) or not probably_text_file(candidate):
                continue
            files.append(candidate)
    return sorted(set(files))


def find_duplicate_blocks(root_path: Path, requested_paths: list[str], min_lines: int) -> list[tuple[tuple[str, ...], list[DuplicateLocation]]]:
    blocks: dict[tuple[str, ...], list[DuplicateLocation]] = defaultdict(list)
    for code_file in iter_code_files(root_path, requested_paths):
        relative_path = code_file.relative_to(root_path).as_posix()
        lines = normalize_code_lines(code_file)
        if len(lines) < min_lines:
            continue
        for index in range(0, len(lines) - min_lines + 1):
            window = tuple(line.text for line in lines[index : index + min_lines])
            blocks[window].append(
                DuplicateLocation(
                    path=relative_path,
                    start_line=lines[index].line_number,
                    end_line=lines[index + min_lines - 1].line_number,
                )
            )
    duplicates = [(block, locations) for block, locations in blocks.items() if len(locations) > 1]
    return collapse_overlapping_duplicates(sorted(duplicates, key=lambda item: (item[1][0].path, item[1][0].start_line)))


def overlaps(existing: list[tuple[int, int]], start_line: int, end_line: int) -> bool:
    return any(start_line <= existing_end and end_line >= existing_start for existing_start, existing_end in existing)


def collapse_overlapping_duplicates(
    duplicates: list[tuple[tuple[str, ...], list[DuplicateLocation]]],
) -> list[tuple[tuple[str, ...], list[DuplicateLocation]]]:
    collapsed: list[tuple[tuple[str, ...], list[DuplicateLocation]]] = []
    covered_ranges: dict[str, list[tuple[int, int]]] = defaultdict(list)
    for block, locations in duplicates:
        if all(overlaps(covered_ranges[location.path], location.start_line, location.end_line) for location in locations):
            continue
        collapsed.append((block, locations))
        for location in locations:
            covered_ranges[location.path].append((location.start_line, location.end_line))
    return collapsed


def main() -> int:
    parser = argparse.ArgumentParser(description="Find exact duplicated normalized code blocks.")
    parser.add_argument("paths", nargs="*", help="Files or directories to scan. Defaults to the repository root.")
    parser.add_argument("--root-path", default=str(REPO_ROOT))
    parser.add_argument("--min-lines", type=int, default=40, help="Minimum normalized code lines in a duplicate block.")
    parser.add_argument("--max-findings", type=int, default=20, help="Maximum duplicate groups to print.")
    parser.add_argument("--report-only", action="store_true", help="Print findings without returning a failing exit code.")
    args = parser.parse_args()

    if args.min_lines < 2:
        print("--min-lines must be at least 2", file=sys.stderr)
        return 2

    root_path = Path(args.root_path).resolve()
    duplicates = find_duplicate_blocks(root_path, args.paths, args.min_lines)
    if not duplicates:
        return 0

    print(f"Exact duplicated code blocks found: {len(duplicates)} group(s), min_lines={args.min_lines}", file=sys.stderr)
    for index, (_, locations) in enumerate(duplicates[: args.max_findings], start=1):
        rendered_locations = ", ".join(f"{location.path}:{location.start_line}-{location.end_line}" for location in locations)
        print(f"{index}. {rendered_locations}", file=sys.stderr)
    if len(duplicates) > args.max_findings:
        print(f"... {len(duplicates) - args.max_findings} more group(s) hidden by --max-findings", file=sys.stderr)
    return 0 if args.report_only else 1


if __name__ == "__main__":
    raise SystemExit(main())
