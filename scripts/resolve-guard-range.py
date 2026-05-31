#!/usr/bin/env python3
from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path

from guardlib import REPO_ROOT, GuardFailure, git_exists, git_is_ancestor, run_cmd, write_github_output


def single_commit_range(root_path: Path, head_sha: str) -> str:
    if not git_exists(root_path, head_sha):
        raise GuardFailure(f"Cannot resolve guard head: {head_sha}")
    return head_sha if not git_exists(root_path, f"{head_sha}^") else f"{head_sha}^..{head_sha}"


def resolve_guard_range(
    root_path: Path,
    event_name: str,
    before_sha: str,
    base_sha: str,
    head_sha: str,
) -> tuple[str, str]:
    if not head_sha.strip():
        raise GuardFailure("HeadSha is required.")

    event = event_name.strip()
    head = head_sha.strip()
    before = before_sha.strip()
    base = base_sha.strip()

    if event == "pull_request":
        if not base:
            raise GuardFailure("BaseSha is required for pull_request guard ranges.")
        return f"{base}..{head}", "pull request base..head"

    if before and not re.fullmatch(r"0+", before):
        if git_is_ancestor(root_path, before, head):
            return f"{before}..{head}", "push before..head"
        return single_commit_range(root_path, head), "non-fast-forward push head commit"

    return single_commit_range(root_path, head), "new branch head commit"


def main() -> int:
    parser = argparse.ArgumentParser(description="Resolve the commit range used by repository guard checks.")
    parser.add_argument("--event-name", default=os.environ.get("EVENT_NAME", ""))
    parser.add_argument("--before-sha", default=os.environ.get("BEFORE_SHA", ""))
    parser.add_argument("--base-sha", default=os.environ.get("BASE_SHA", ""))
    parser.add_argument("--head-sha", default=os.environ.get("HEAD_SHA", os.environ.get("GITHUB_SHA", "")))
    parser.add_argument("--github-output-path", default=os.environ.get("GITHUB_OUTPUT", ""))
    parser.add_argument("--root-path", default=str(REPO_ROOT))
    args = parser.parse_args()

    root_path = Path(args.root_path).resolve()
    git_range, reason = resolve_guard_range(
        root_path,
        args.event_name,
        args.before_sha,
        args.base_sha,
        args.head_sha,
    )
    head_short = run_cmd(["git", "rev-parse", "--short", args.head_sha], cwd=root_path).stdout.strip()
    write_github_output(
        {
            "git_range": git_range,
            "reason": reason,
            "head_short": head_short,
        },
        args.github_output_path,
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except GuardFailure as exc:
        print(str(exc), file=sys.stderr)
        raise SystemExit(1)
