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
    parse_extra_blocked_terms,
    run_cmd,
)


MAX_SUMMARY_ITEMS = 3


@dataclass
class CommitEntry:
    subject: str
    body: str
    files: list[str]


def assert_clean_release_text(text: str, regex: re.Pattern[str]) -> None:
    if text and regex.search(text):
        raise GuardFailure("Release notes input contains blocked legacy project naming. Rewrite the commit subject before publishing.")


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


def single_head_commit_range(root_path: Path) -> tuple[str, str]:
    head_result = run_cmd(["git", "rev-parse", "HEAD"], cwd=root_path, check=False)
    short_result = run_cmd(["git", "rev-parse", "--short", "HEAD"], cwd=root_path, check=False)
    head = head_result.stdout.strip()
    short_head = short_result.stdout.strip()
    if head_result.returncode != 0 or short_result.returncode != 0 or not head or not short_head:
        raise GuardFailure("Cannot resolve the current release commit.")
    return f"{head}^!", short_head


def clean_commit_entries(items: list[CommitEntry], regex: re.Pattern[str]) -> tuple[list[CommitEntry], int]:
    clean_items: list[CommitEntry] = []
    omitted = 0
    for item in items:
        release_text_values = [item.subject, item.body, *item.files]
        if any(regex.search(value) for value in release_text_values if value):
            omitted += 1
            continue
        clean_items.append(item)
    return clean_items, omitted


def normalize_release_subject(item: CommitEntry, regex: re.Pattern[str]) -> str:
    assert_clean_release_text(item.subject, regex)
    assert_clean_release_text(item.body, regex)
    for file_name in item.files:
        assert_clean_release_text(file_name, regex)
    return item.subject if " / " in item.subject else f"提交：{item.subject} / Commit: {item.subject}"


def summarize_items(items: list[CommitEntry], fallback: str, regex: re.Pattern[str]) -> str:
    if not items:
        return "- " + fallback

    lines = [f"- 共 {len(items)} 项 / {len(items)} item(s)"]
    for item in items[:MAX_SUMMARY_ITEMS]:
        lines.append("  - " + normalize_release_subject(item, regex))
    remaining = len(items) - MAX_SUMMARY_ITEMS
    if remaining > 0:
        lines.append(f"  - 其余 {remaining} 项未展开。 / {remaining} more item(s) not expanded.")
    return "\n".join(lines)


def select_items(items: list[CommitEntry], pattern: str) -> list[CommitEntry]:
    regex = re.compile(pattern, re.IGNORECASE)
    return [item for item in items if regex.search(item.subject + "\n" + item.body)]


def commit_entry_key(item: CommitEntry) -> tuple[str, str]:
    return item.subject, item.body


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

    commits = commit_entries(root_path, commit_range)
    range_fallback_note = ""
    if not commits:
        fallback_range, short_head = single_head_commit_range(root_path)
        commits = commit_entries(root_path, fallback_range)
        display_range = short_head
        assert_clean_release_text(display_range, regex)
        range_fallback_note += (
            "空提交范围，已使用当前提交生成发布说明。\n"
            "Empty commit range; the current commit was used to generate these release notes.\n\n"
        )

    commits, omitted_count = clean_commit_entries(commits, regex)
    if omitted_count:
        plural = "entry was" if omitted_count == 1 else "entries were"
        pronoun = "it" if omitted_count == 1 else "they"
        range_fallback_note += (
            f"历史发布范围中有 {omitted_count} 条提交摘要未通过命名检查，已从发布说明中省略。\n"
            f"{omitted_count} commit summary {plural} omitted from these release notes because {pronoun} contained blocked naming.\n\n"
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

    body = f"""## solon-claw {tag}

本次发布说明按提交类型生成摘要，默认仅展开每类前 {MAX_SUMMARY_ITEMS} 条提交主题，用于突出相对上一版的主要差异。
These release notes summarize commit types and expand only the first {MAX_SUMMARY_ITEMS} subjects in each section by default, highlighting the main differences from the previous release.

提交范围：`{display_range}`
Commit range: `{display_range}`

{range_fallback_note}### 功能 / Features

{summarize_items(features, "本次发布没有功能类提交。 / No feature-classified commits in this release.", regex)}

### 缺陷修复 / Fixes

{summarize_items(fixes, "本次发布没有缺陷修复类提交。 / No fix-classified commits in this release.", regex)}

### 其他变更 / Other Changes

{summarize_items(others, "无其他提交。 / No other commits.", regex)}

### 下载内容 / Downloads

- `solon-claw.jar`：完整运行包，包含后端依赖与 Dashboard 静态资源。
- `solon-claw.jar`: Full runtime package with backend dependencies and Dashboard static assets.
- `solon-claw-source.zip`：当前发布提交的源码 ZIP 包。
- `solon-claw-source.zip`: Source ZIP archive for the published commit.
- `solon-claw-source.tar.gz`：当前发布提交的源码 tar.gz 包。
- `solon-claw-source.tar.gz`: Source tar.gz archive for the published commit.
- `SHA256SUMS`：发布包校验文件。
- `SHA256SUMS`: Checksums for release artifacts.

### 快速运行 / Quick Start

```bash
java -jar solon-claw.jar
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
