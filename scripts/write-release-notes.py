#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path

from guardlib import (
    REPO_ROOT,
    GuardFailure,
    add_extra_blocked_terms_argument,
    blocked_regex,
    check_git_text,
    parse_extra_blocked_terms,
    run_cmd,
)


CHANGED_FILE_DISPLAY_LIMIT = 8


@dataclass
class CommitEntry:
    subject: str
    body: str
    files: list[str]


def assert_clean_release_text(text: str, regex: re.Pattern[str]) -> None:
    if text and regex.search(text):
        raise GuardFailure("Release notes input contains blocked legacy project naming. Rewrite the commit subject before publishing.")


def invoke_project_naming_guard(root_path: Path, commit_range: str, extra_terms: list[str]) -> None:
    if not commit_range.strip():
        return
    findings = check_git_text(
        root_path,
        extra_blocked_terms=extra_terms,
        git_commit_range=commit_range,
        check_git_commit_subjects=True,
        check_git_object_text=True,
    )
    if findings:
        raise GuardFailure("Release naming guard failed for commit range: {0}\n{1}".format(commit_range, "\n".join(findings)))


def commit_files(root_path: Path, commit: str) -> list[str]:
    result = run_cmd(["git", "diff-tree", "--no-commit-id", "--name-only", "-r", "--root", commit], cwd=root_path, check=False)
    if result.returncode != 0:
        return []
    return [line.strip() for line in result.stdout.splitlines() if line.strip()]


def commit_entries(root_path: Path, commit_range: str) -> list[CommitEntry]:
    result = run_cmd(["git", "log", "--pretty=format:%H%x1f%s%x1f%b%x1e", commit_range], cwd=root_path, check=False)
    if result.returncode != 0:
        raise GuardFailure(f"Failed to read git commits for range: {commit_range}")
    entries: list[CommitEntry] = []
    for record in result.stdout.split(chr(30)):
        if not record.strip():
            continue
        parts = record.split(chr(31), 2)
        commit = parts[0].strip() if parts else ""
        subject = parts[1].strip() if len(parts) > 1 else ""
        body = parts[2].strip() if len(parts) > 2 else ""
        if subject:
            entries.append(CommitEntry(subject, body, commit_files(root_path, commit)))
    return entries


def head_commit_entry(root_path: Path) -> list[CommitEntry]:
    entries = commit_entries(root_path, "HEAD")
    return entries[:1]


def format_release_details(body: str, files: list[str]) -> str:
    body_lines = [line.strip() for line in re.split(r"\r?\n", body or "") if line.strip()]
    if not body_lines and not files:
        return ""

    formatted = ["  详情 / Details:" if body_lines else "  影响文件 / Changed files:"]
    for line in body_lines:
        formatted.append("  - " + re.sub(r"^[-*]\s+", "", line))
    if not body_lines:
        shown_files = files[:CHANGED_FILE_DISPLAY_LIMIT]
        for file_name in shown_files:
            formatted.append("  - " + file_name)
        remaining = len(files) - len(shown_files)
        if remaining > 0:
            formatted.append(f"  - 另有 {remaining} 个文件未展开。 / {remaining} more files omitted.")
    return "\n".join(formatted)


def normalize_release_item(item: CommitEntry, regex: re.Pattern[str]) -> str:
    assert_clean_release_text(item.subject, regex)
    assert_clean_release_text(item.body, regex)
    for file_name in item.files:
        assert_clean_release_text(file_name, regex)
    subject = item.subject if " / " in item.subject else f"提交：{item.subject} / Commit: {item.subject}"
    details = format_release_details(item.body, item.files)
    return subject if not details else subject + "\n" + details


def select_items(items: list[CommitEntry], pattern: str) -> list[CommitEntry]:
    regex = re.compile(pattern, re.IGNORECASE)
    return [item for item in items if regex.search(item.subject + "\n" + item.body)]


def commit_entry_key(item: CommitEntry) -> tuple[str, str]:
    return item.subject, item.body


def write_items(items: list[CommitEntry], fallback: str, regex: re.Pattern[str]) -> str:
    if not items:
        return "- " + fallback
    return "\n".join("- " + normalize_release_item(item, regex) for item in items)


def generate_release_notes(
    root_path: Path,
    output_path: Path,
    tag: str,
    version: str,
    commit_range: str,
    display_range: str,
    extra_terms: list[str],
) -> None:
    regex = blocked_regex(extra_terms)
    for value in (tag, version, commit_range, display_range):
        assert_clean_release_text(value, regex)
    invoke_project_naming_guard(root_path, commit_range, extra_terms)

    commits = commit_entries(root_path, commit_range)
    range_fallback_note = ""
    if not commits:
        commits = head_commit_entry(root_path)
        display_range = run_cmd(["git", "rev-parse", "--short", "HEAD"], cwd=root_path).stdout.strip()
        assert_clean_release_text(display_range, regex)
        invoke_project_naming_guard(root_path, "HEAD", extra_terms)
        range_fallback_note = (
            "空提交范围，已使用当前提交生成发布说明。\n"
            "Empty commit range; the current commit was used to generate these release notes.\n\n"
        )

    for commit in commits:
        assert_clean_release_text(commit.subject, regex)
        assert_clean_release_text(commit.body, regex)
        for file_name in commit.files:
            assert_clean_release_text(file_name, regex)

    feature_pattern = r"(^|\b)(feat|feature)(\(.+\))?:|功能|新增|支持|对齐|补齐|完善|实现|add|implement|support|align|complete|improve"
    fix_pattern = r"(^|\b)(fix|bugfix)(\(.+\))?:|修复|缺陷|问题|异常|错误|失败|回归|bug|fix|bugfix|resolve|repair"
    features = select_items(commits, feature_pattern)
    fixes = select_items(commits, fix_pattern)
    classified = {commit_entry_key(item) for item in features + fixes}
    others = [item for item in commits if commit_entry_key(item) not in classified]

    body = f"""## jimuqu-agent {tag}

本次发布说明按提交类型整理，并保留中英双语摘要；功能、缺陷修复和其他变更来自本次发布范围内的提交摘要。
These release notes are grouped by commit type and keep bilingual summaries; features, fixes, and other changes are derived from commit summaries in this release range.

提交范围：`{display_range}`
Commit range: `{display_range}`

{range_fallback_note}### 功能 / Features

{write_items(features, "本次发布没有单独标记为 feat 的提交。 / No commits were explicitly marked as feat in this release.", regex)}

### 缺陷修复 / Fixes

{write_items(fixes, "本次发布没有单独标记为 fix 的提交。 / No commits were explicitly marked as fix in this release.", regex)}

### 其他变更 / Other Changes

{write_items(others, "无其他提交。 / No other commits.", regex)}

### 下载内容 / Downloads

- `SolonClaw.jar`：完整运行包，包含后端依赖与 Dashboard 静态资源。
- `SolonClaw.jar`: Full runtime package with backend dependencies and Dashboard static assets.
- `SolonClaw-source.zip`：当前发布提交的源码 ZIP 包。
- `SolonClaw-source.zip`: Source ZIP archive for the published commit.
- `SolonClaw-source.tar.gz`：当前发布提交的源码 tar.gz 包。
- `SolonClaw-source.tar.gz`: Source tar.gz archive for the published commit.
- `SHA256SUMS`：发布包校验文件。
- `SHA256SUMS`: Checksums for release artifacts.

### 快速运行 / Quick Start

```bash
java -jar SolonClaw.jar
```

服务默认监听 `http://127.0.0.1:8080`，运行数据会写入当前目录的 `runtime/`。
The service listens on `http://127.0.0.1:8080` by default and writes runtime data to `runtime/` in the current directory.
"""
    assert_clean_release_text(body, regex)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(body, encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Write bilingual release notes.")
    parser.add_argument("--output-path", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--commit-range", required=True)
    parser.add_argument("--display-range", required=True)
    parser.add_argument("--root-path", default=str(REPO_ROOT))
    add_extra_blocked_terms_argument(parser)
    args = parser.parse_args()

    generate_release_notes(
        Path(args.root_path).resolve(),
        Path(args.output_path),
        args.tag,
        args.version,
        args.commit_range,
        args.display_range,
        parse_extra_blocked_terms(args.extra_blocked_terms),
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except GuardFailure as exc:
        print(str(exc), file=sys.stderr)
        raise SystemExit(1)
