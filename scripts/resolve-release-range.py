#!/usr/bin/env python3
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

from guardlib import REPO_ROOT, GuardFailure, git_is_ancestor, git_line, run_cmd, write_github_output


DEFAULT_CLEAN_NAMING_BASE = "a6e245c53d8eacdc041a2314390448e92e31ab10"


def resolve_release_range(root_path: Path, head_sha: str, clean_naming_base: str) -> tuple[str, str]:
    if not head_sha.strip():
        raise GuardFailure("HeadSha is required.")

    short_head = git_line(root_path, ["rev-parse", "--short", head_sha])
    if not short_head:
        raise GuardFailure(f"Cannot resolve release head: {head_sha}")

    clean_base_reachable = git_is_ancestor(root_path, clean_naming_base, head_sha)
    if clean_base_reachable and head_sha == clean_naming_base:
        return head_sha, short_head
    if clean_base_reachable:
        return f"{clean_naming_base}..{head_sha}", f"clean naming baseline..{short_head}"

    previous_tag = git_line(root_path, ["describe", "--tags", "--match", "v*", "--abbrev=0", f"{head_sha}^"])
    if previous_tag:
        return f"{previous_tag}..{head_sha}", f"{previous_tag}..{short_head}"

    rev_list = run_cmd(["git", "rev-list", "--max-count=30", head_sha], cwd=root_path, check=False)
    commits = [line.strip() for line in rev_list.stdout.splitlines() if line.strip()]
    if rev_list.returncode != 0 or not commits:
        raise GuardFailure(f"Cannot resolve fallback release range for: {head_sha}")
    oldest_commit = commits[-1]
    parent_exists = run_cmd(["git", "rev-parse", "-q", "--verify", f"{oldest_commit}^"], cwd=root_path, check=False).returncode == 0
    git_range = f"{oldest_commit}^..{head_sha}" if parent_exists else head_sha
    return git_range, f"latest 30 commits ending at {short_head}"


def main() -> int:
    parser = argparse.ArgumentParser(description="Resolve release commit range.")
    parser.add_argument("--head-sha", default=os.environ.get("GITHUB_SHA", ""))
    parser.add_argument("--clean-naming-base", default=DEFAULT_CLEAN_NAMING_BASE)
    parser.add_argument("--github-output-path", default=os.environ.get("GITHUB_OUTPUT", ""))
    parser.add_argument("--root-path", default=str(REPO_ROOT))
    args = parser.parse_args()

    git_range, display_range = resolve_release_range(
        Path(args.root_path).resolve(),
        args.head_sha,
        args.clean_naming_base,
    )
    write_github_output(
        {"git_range": git_range, "display_range": display_range},
        args.github_output_path,
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except GuardFailure as exc:
        print(str(exc), file=sys.stderr)
        raise SystemExit(1)
