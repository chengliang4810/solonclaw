#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.parse
import urllib.error
import urllib.request
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence


REPO_ROOT = Path(__file__).resolve().parent.parent

IGNORED_DIRS = {
    ".git",
    ".idea",
    "node_modules",
    ".gradle",
    ".mvn",
    ".turbo",
    ".vite",
    "coverage",
    "target",
}
IGNORED_FILES = {
    "package-lock.json",
    "pnpm-lock.yaml",
    "yarn.lock",
}
BINARY_EXTENSIONS = {
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
    ".zip",
}


class GuardFailure(RuntimeError):
    pass


@dataclass
class CommandResult:
    returncode: int
    stdout: str
    stderr: str

    @property
    def output(self) -> str:
        return self.stdout + self.stderr


def run_cmd(
    args: Sequence[str],
    cwd: Path | None = None,
    check: bool = True,
    text: bool = True,
) -> CommandResult:
    completed = subprocess.run(
        list(args),
        cwd=str(cwd) if cwd else None,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=text,
    )
    result = CommandResult(
        completed.returncode,
        completed.stdout if isinstance(completed.stdout, str) else completed.stdout.decode("utf-8", "replace"),
        completed.stderr if isinstance(completed.stderr, str) else completed.stderr.decode("utf-8", "replace"),
    )
    if check and result.returncode != 0:
        raise GuardFailure(f"Command failed: {' '.join(args)}\n{result.output}")
    return result


def code_text(codes: Sequence[int]) -> str:
    return "".join(chr(code) for code in codes)


def blocked_patterns(extra_terms: Iterable[str] = ()) -> list[str]:
    first_external_name = r"[Hh][Ee][Rr][Mm][Ee][Ss]"
    second_external_name = r"[Oo][Pp][Ee][Nn](?:[_\-\.\s])?[Cc][Ll][Aa][Ww]"
    patterns = [
        first_external_name + r"_?",
        second_external_name + r"[_\-]?",
        r"[Bb][Aa][Dd][_\-.]?[Ll][Ee][Gg][Aa][Cc][Yy][_\-.]?[Pp][Rr][Ee][Ff][Ii][Xx](?:[_\-.])",
        first_external_name + r"(?:[_\-.])",
        second_external_name + r"(?:[_\-.])",
    ]
    for term in extra_terms:
        if term and term.strip():
            patterns.append(re.escape(term))
    return patterns


def blocked_regex(extra_terms: Iterable[str] = ()) -> re.Pattern[str]:
    return re.compile("|".join(blocked_patterns(extra_terms)), re.IGNORECASE)


def git_grep_blocked_patterns(extra_terms: Iterable[str] = ()) -> list[str]:
    first_external_name = r"[Hh][Ee][Rr][Mm][Ee][Ss]"
    second_external_name = r"[Oo][Pp][Ee][Nn]([_[:space:].-])?[Cc][Ll][Aa][Ww]"
    patterns = [
        first_external_name + r"_?",
        second_external_name + r"[_ -]?",
        r"[Bb][Aa][Dd][_.-]?[Ll][Ee][Gg][Aa][Cc][Yy][_.-]?[Pp][Rr][Ee][Ff][Ii][Xx]([_.-])",
        first_external_name + r"([_.-])",
        second_external_name + r"([_.-])",
    ]
    for term in extra_terms:
        if term and term.strip():
            patterns.append(re.escape(term))
    return patterns


def hide_blocked_text(text: str, regex: re.Pattern[str]) -> str:
    return regex.sub("<blocked>", text)


def is_ignored_path(relative_path: str) -> bool:
    parts = [part for part in re.split(r"[\\/]+", relative_path) if part]
    if any(part in IGNORED_DIRS for part in parts):
        return True
    return bool(parts and parts[-1] in IGNORED_FILES)


def probably_text_file(path: Path) -> bool:
    if path.suffix.lower() in BINARY_EXTENSIONS:
        return False
    try:
        size = path.stat().st_size
    except OSError:
        return False
    if size > 10 * 1024 * 1024:
        return False
    try:
        with path.open("rb") as handle:
            sample = handle.read(min(4096, size))
    except OSError:
        return False
    return b"\0" not in sample


def search_binary_file(path: Path, relative_path: str, regex: re.Pattern[str]) -> str | None:
    try:
        if path.stat().st_size > 100 * 1024 * 1024:
            return None
        data = path.read_bytes()
    except OSError:
        return None
    if not data:
        return None
    if regex.search(data.decode("latin-1", "ignore")):
        return f"{hide_blocked_text(relative_path, regex)}:0:<binary>"
    return None


def scan_directory(
    root_path: Path,
    extra_blocked_terms: Iterable[str] = (),
    check_binary_text: bool = False,
) -> list[str]:
    scan_root = root_path.resolve()
    regex = blocked_regex(extra_blocked_terms)
    findings: list[str] = []

    for current_root, dir_names, file_names in os.walk(scan_root):
        current_path = Path(current_root)
        current_relative = current_path.relative_to(scan_root).as_posix() if current_path != scan_root else ""
        if current_relative and regex.search(current_relative):
            findings.append(f"{hide_blocked_text(current_relative, regex)}:0:<path>")
        dir_names[:] = [
            name
            for name in sorted(dir_names)
            if not is_ignored_path(f"{current_relative}/{name}" if current_relative else name)
        ]
        for file_name in sorted(file_names):
            path = current_path / file_name
            relative_path = path.relative_to(scan_root).as_posix()
            if is_ignored_path(relative_path):
                continue
            if regex.search(relative_path):
                findings.append(f"{hide_blocked_text(relative_path, regex)}:0:<path>")
            if not probably_text_file(path):
                if check_binary_text:
                    binary_finding = search_binary_file(path, relative_path, regex)
                    if binary_finding:
                        findings.append(binary_finding)
                continue
            try:
                with path.open("r", encoding="utf-8", errors="ignore") as handle:
                    for line_number, line in enumerate(handle, start=1):
                        if regex.search(line):
                            findings.append(f"{hide_blocked_text(relative_path, regex)}:{line_number}:<blocked>")
            except OSError:
                continue

    return findings


def ensure_git_work_tree(root_path: Path) -> None:
    result = run_cmd(["git", "rev-parse", "--is-inside-work-tree"], cwd=root_path, check=False)
    if result.returncode != 0 or result.stdout.strip() != "true":
        raise GuardFailure("Current path is not a git work tree, cannot check commit text.")


def git_line(root_path: Path, args: Sequence[str]) -> str:
    result = run_cmd(["git", *args], cwd=root_path, check=False)
    if result.returncode != 0:
        return ""
    return result.stdout.splitlines()[0].strip() if result.stdout.splitlines() else ""


def git_exists(root_path: Path, rev: str) -> bool:
    return run_cmd(["git", "rev-parse", "-q", "--verify", rev], cwd=root_path, check=False).returncode == 0


def git_is_ancestor(root_path: Path, ancestor: str, descendant: str) -> bool:
    if not ancestor.strip() or not descendant.strip():
        return False
    return run_cmd(["git", "merge-base", "--is-ancestor", ancestor, descendant], cwd=root_path, check=False).returncode == 0


def get_default_branch_range(root_path: Path) -> str:
    ensure_git_work_tree(root_path)
    head = git_line(root_path, ["rev-parse", "HEAD"])
    if not head:
        raise GuardFailure("Cannot resolve HEAD for current branch range.")
    for candidate in ("origin/main", "main", "origin/master", "master"):
        if not git_exists(root_path, candidate):
            continue
        merge_base = git_line(root_path, ["merge-base", candidate, "HEAD"])
        if merge_base:
            return "" if merge_base == head else f"{merge_base}..{head}"
    raise GuardFailure("Cannot resolve a default branch base for current branch range.")


def resolve_git_range(root_path: Path, git_range: str) -> str:
    match = re.fullmatch(r"([0-9a-fA-F]{7,40})\^\.\.\1", git_range)
    if not match:
        return git_range
    head = match.group(1)
    return head if not git_exists(root_path, f"{head}^") else git_range


def resolve_scan_range(
    root_path: Path,
    git_commit_range: str,
    check_all_git_refs: bool,
    check_current_branch_range: bool,
) -> tuple[str, bool]:
    if check_all_git_refs:
        return "--all", False
    if check_current_branch_range:
        value = get_default_branch_range(root_path)
        return value, not bool(value.strip())
    if git_commit_range.strip():
        return resolve_git_range(root_path, git_commit_range.strip()), False
    return "HEAD", False


def git_log_lines(root_path: Path, args: Sequence[str]) -> list[str]:
    result = run_cmd(["git", "log", *args], cwd=root_path, check=False)
    if result.returncode != 0:
        raise GuardFailure(f"Failed to read git log for arguments: {' '.join(args)}")
    return [line for line in result.stdout.splitlines() if line.strip()]


def git_rev_list(root_path: Path, args: Sequence[str]) -> list[str]:
    result = run_cmd(["git", "rev-list", *args], cwd=root_path, check=False)
    if result.returncode != 0:
        raise GuardFailure(f"Failed to read git commits for range: {' '.join(args)}")
    return [line.strip() for line in result.stdout.splitlines() if line.strip()]


def object_text_findings(
    root_path: Path,
    commit: str,
    regex: re.Pattern[str],
    grep_patterns: Sequence[str],
    limit_remaining: int,
) -> tuple[int, list[str]]:
    grep_args = [
        "git",
        "grep",
        "-I",
        "-n",
        "-i",
        "-E",
    ]
    for pattern in grep_patterns:
        grep_args.extend(["-e", pattern])
    grep_args.extend([
        commit,
        "--",
        ".",
        ":(exclude).git/**",
        ":(exclude)web/node_modules/**",
        ":(exclude)node_modules/**",
        ":(exclude)target/**",
    ])
    result = subprocess.run(
        grep_args,
        cwd=str(root_path),
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        text=True,
    )
    if result.returncode not in (0, 1):
        raise GuardFailure(f"Failed to scan git object text for commit: {commit}")
    total = 0
    findings: list[str] = []
    for line in result.stdout.splitlines():
        if not line.strip():
            continue
        total += 1
        if len(findings) < limit_remaining:
            parts = line.split(":", 3)
            if len(parts) >= 3:
                findings.append(f"{parts[0]}:{hide_blocked_text(parts[1], regex)}:{parts[2]}:<blocked>")
            else:
                findings.append(f"{commit[:12]}:<blocked>")
    return total, findings


def check_git_text(
    root_path: Path,
    extra_blocked_terms: Iterable[str] = (),
    git_commit_range: str = "",
    check_git_commit_subjects: bool = False,
    check_git_object_text: bool = False,
    check_all_git_refs: bool = False,
    check_current_branch_range: bool = False,
) -> list[str]:
    if not check_git_commit_subjects and not check_git_object_text:
        return []
    ensure_git_work_tree(root_path)
    regex = blocked_regex(extra_blocked_terms)
    scan_range, skip_range_check = resolve_scan_range(
        root_path,
        git_commit_range,
        check_all_git_refs,
        check_current_branch_range,
    )
    findings: list[str] = []

    if check_git_commit_subjects:
        subject_args = ["--all", "--format=%h %s"] if check_all_git_refs else [f"--format=%h %s", scan_range]
        subjects = [] if skip_range_check else git_log_lines(root_path, subject_args)
        subject_matches = [hide_blocked_text(subject, regex) for subject in subjects if regex.search(subject)]
        if subject_matches:
            findings.append("Blocked legacy project naming in git commit subjects. Rewrite or replace the commit subject before publishing release notes.")
            findings.extend(subject_matches)

        commit_args = ["--all", "--format=%H"] if check_all_git_refs else ["--format=%H", scan_range]
        commits = [] if skip_range_check else git_log_lines(root_path, commit_args)
        message_matches: list[str] = []
        for commit in commits:
            if not re.fullmatch(r"[0-9a-fA-F]{7,40}", commit):
                raise GuardFailure("Unexpected git commit id while scanning commit messages.")
            message = run_cmd(["git", "log", "-1", "--format=%B", commit], cwd=root_path).stdout
            if regex.search(message):
                message_matches.append(f"{commit[:12]}:<blocked>")
        if message_matches:
            findings.append("Blocked legacy project naming in git commit messages. Rewrite or replace the commit before publishing release notes.")
            findings.extend(message_matches)

    if check_git_object_text:
        commits = [] if skip_range_check else (
            git_rev_list(root_path, ["--all"]) if check_all_git_refs else git_rev_list(root_path, [scan_range])
        )
        object_matches: list[str] = []
        object_match_count = 0
        object_match_limit = 200
        grep_patterns = git_grep_blocked_patterns(extra_blocked_terms)
        for commit in commits:
            total, commit_findings = object_text_findings(
                root_path,
                commit,
                regex,
                grep_patterns,
                max(0, object_match_limit - len(object_matches)),
            )
            object_match_count += total
            object_matches.extend(commit_findings)
        if object_match_count:
            findings.append("Blocked legacy project naming in git object text. Rewrite the range, remove the polluted release range, or publish from a clean range before generating release notes.")
            findings.append(f"Total blocked git object text matches: {object_match_count}")
            findings.extend(object_matches)
            if object_match_count > len(object_matches):
                findings.append(f"Additional matches omitted: {object_match_count - len(object_matches)}")

    return findings


def fail_with_findings(header: str, findings: Sequence[str]) -> None:
    if findings:
        print(header, file=sys.stderr)
        for finding in findings:
            print(finding, file=sys.stderr)
        raise SystemExit(1)


def parse_extra_blocked_terms(values: Sequence[Sequence[str]] | None) -> list[str]:
    terms: list[str] = []
    for group in values or []:
        for term in group:
            if term and term.strip():
                terms.append(term)
    return terms


def write_github_output(values: dict[str, str], output_path: str | None) -> None:
    if output_path:
        with open(output_path, "a", encoding="utf-8") as handle:
            for key, value in values.items():
                handle.write(f"{key}={value}\n")
    else:
        for key, value in values.items():
            print(f"{key}={value}")


def fetch_github_release(repository: str, tag: str, token: str, api_base_url: str) -> dict:
    if not repository:
        raise GuardFailure("Repository is required.")
    if not tag:
        raise GuardFailure("Tag is required.")
    if not token:
        raise GuardFailure("Token is required.")
    encoded_tag = urllib.parse.quote(tag, safe="")
    url = f"{api_base_url.rstrip('/')}/repos/{repository}/releases/tags/{encoded_tag}"
    request = urllib.request.Request(
        url,
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.URLError as exc:
        raise GuardFailure(f"Failed to read published release metadata: {exc}") from exc


def add_extra_blocked_terms_argument(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--extra-blocked-terms",
        nargs="+",
        action="append",
        default=[],
        help="Additional blocked terms used by self-tests and manual audits.",
    )
