#!/usr/bin/env python3
from __future__ import annotations

import argparse
import sys
from pathlib import Path

from guardlib import (
    REPO_ROOT,
    GuardFailure,
    add_extra_blocked_terms_argument,
    check_git_text,
    fail_with_findings,
    parse_extra_blocked_terms,
    scan_directory,
)


def main() -> int:
    parser = argparse.ArgumentParser(description="Check repository naming guardrails.")
    parser.add_argument("--root-path", default=str(REPO_ROOT))
    parser.add_argument("--git-commit-range", default="")
    parser.add_argument("--check-git-commit-subjects", action="store_true")
    parser.add_argument("--check-git-object-text", action="store_true")
    parser.add_argument("--check-all-git-refs", action="store_true")
    parser.add_argument("--check-current-branch-range", action="store_true")
    parser.add_argument("--check-binary-text", action="store_true")
    add_extra_blocked_terms_argument(parser)
    args = parser.parse_args()

    root_path = Path(args.root_path).resolve()
    extra_terms = parse_extra_blocked_terms(args.extra_blocked_terms)

    directory_findings = scan_directory(
        root_path,
        extra_blocked_terms=extra_terms,
        check_binary_text=args.check_binary_text,
    )
    fail_with_findings(
        "Blocked legacy project naming in repository paths or text. Use Jimuqu/JIMUQU naming for code, docs, config keys, routes, storage keys, environment variables, generated source, and release artifacts.",
        directory_findings,
    )

    git_findings = check_git_text(
        root_path,
        extra_blocked_terms=extra_terms,
        git_commit_range=args.git_commit_range,
        check_git_commit_subjects=args.check_git_commit_subjects,
        check_git_object_text=args.check_git_object_text,
        check_all_git_refs=args.check_all_git_refs,
        check_current_branch_range=args.check_current_branch_range,
    )
    if git_findings:
        for finding in git_findings:
            print(finding, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except GuardFailure as exc:
        print(str(exc), file=sys.stderr)
        raise SystemExit(1)
