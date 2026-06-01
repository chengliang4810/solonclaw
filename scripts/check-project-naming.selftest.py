#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parent.parent
SCRIPT_DIR = REPO_ROOT / "scripts"
NAMING_SCRIPT = SCRIPT_DIR / "check-project-naming.py"
GITHUB_PACKAGE_SCRIPT = SCRIPT_DIR / "check-github-package-name.py"
RELEASE_NOTES_SCRIPT = SCRIPT_DIR / "write-release-notes.py"
RELEASE_RANGE_SCRIPT = SCRIPT_DIR / "resolve-release-range.py"
GUARD_RANGE_SCRIPT = SCRIPT_DIR / "resolve-guard-range.py"
PUBLISHED_RELEASE_SCRIPT = SCRIPT_DIR / "check-release-naming.py"
ARCHIVE_NAMING_SCRIPT = SCRIPT_DIR / "check-archive-naming.py"

BLOCKED_FIXTURE = "BLOCKED_LEGACY_TOKEN_FIXTURE"
BLOCKED_FIXTURE_LOWER = BLOCKED_FIXTURE.lower()
BLOCKED_ENV_FIXTURE = BLOCKED_FIXTURE + "_ALLOW_PRIVATE_URLS"
BLOCKED_DEFAULT_ENV_FIXTURE = "BAD_" + "LEGACY_" + "PREFIX_ALLOW_PRIVATE_URLS"
BLOCKED_LEGACY_PROJECT_A = "HER" + "MES"
BLOCKED_LEGACY_PROJECT_B = "Open" + "Claw"
BLOCKED_LEGACY_PROJECT_A_ENV = BLOCKED_LEGACY_PROJECT_A + "_ALLOW_PRIVATE_URLS"
BLOCKED_LEGACY_PROJECT_B_ENV = BLOCKED_LEGACY_PROJECT_B.upper() + "_ALLOW_PRIVATE_URLS"


def run(args: list[str], cwd: Path | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        args,
        cwd=str(cwd or REPO_ROOT),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )


def require_success(result: subprocess.CompletedProcess[str], message: str) -> None:
    if result.returncode != 0:
        raise AssertionError(message + ": " + result.stdout + result.stderr)


def require_failure(result: subprocess.CompletedProcess[str], message: str) -> None:
    if result.returncode == 0:
        raise AssertionError(message)


def assert_no_raw_blocked_output(output: str, blocked_values: list[str], scenario: str) -> None:
    for blocked_value in blocked_values:
        if blocked_value and blocked_value in output:
            raise AssertionError(f"Naming guard leaked a raw blocked term in output for scenario: {scenario}")


def reset_sandbox(sandbox: Path) -> None:
    if sandbox.exists():
        shutil.rmtree(sandbox)
    sandbox.mkdir(parents=True)


def write_text(path: Path, value: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(value, encoding="utf-8")


def invoke_naming_check(sandbox: Path, with_extra_fixture: bool = False) -> subprocess.CompletedProcess[str]:
    args = ["python3", str(NAMING_SCRIPT), "--root-path", str(sandbox)]
    if with_extra_fixture:
        args.extend(["--extra-blocked-terms", BLOCKED_FIXTURE])
    return run(args)


def invoke_git_naming_check(
    sandbox: Path,
    git_range: str = "",
    with_extra_fixture: bool = False,
    check_object_text: bool = False,
    check_all_git_refs: bool = False,
    check_current_branch_range: bool = False,
) -> subprocess.CompletedProcess[str]:
    args = [
        "python3",
        str(NAMING_SCRIPT),
        "--root-path",
        str(sandbox),
        "--check-git-commit-subjects",
    ]
    if check_object_text:
        args.append("--check-git-object-text")
    if check_all_git_refs:
        args.append("--check-all-git-refs")
    if check_current_branch_range:
        args.append("--check-current-branch-range")
    if git_range:
        args.extend(["--git-commit-range", git_range])
    if with_extra_fixture:
        args.extend(["--extra-blocked-terms", BLOCKED_FIXTURE])
    return run(args)


def invoke_github_package_check(sandbox: Path, workflow_text: str | None = None) -> subprocess.CompletedProcess[str]:
    packages_workflow = sandbox / ".github" / "workflows" / "packages.yml"
    packages_workflow.parent.mkdir(parents=True, exist_ok=True)
    if workflow_text is None:
        shutil.copy2(REPO_ROOT / ".github" / "workflows" / "packages.yml", packages_workflow)
    else:
        packages_workflow.write_text(workflow_text, encoding="utf-8")
    return run(["python3", str(GITHUB_PACKAGE_SCRIPT), "--root-path", str(sandbox)], cwd=sandbox)


def git(cwd: Path, *args: str) -> str:
    result = run(["git", *args], cwd=cwd)
    require_success(result, "git command failed")
    return result.stdout.strip()


def git_init(sandbox: Path) -> None:
    git(sandbox, "init", "--initial-branch=main")
    git(sandbox, "config", "user.name", "Jimuqu Naming Check")
    git(sandbox, "config", "user.email", "naming-check@example.invalid")


def create_zip(source_dir: Path, archive_path: Path) -> None:
    with zipfile.ZipFile(archive_path, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in source_dir.rglob("*"):
            if path.is_file():
                archive.write(path, path.relative_to(source_dir).as_posix())


def main() -> int:
    sandbox = Path(tempfile.mkdtemp(prefix="jimuqu-naming-check-selftest-"))
    try:
        reset_sandbox(sandbox)
        write_text(sandbox / "src" / "config.txt", BLOCKED_FIXTURE + "=true")
        blocked = invoke_naming_check(sandbox, with_extra_fixture=True)
        require_failure(blocked, "Naming check did not block a forbidden legacy environment variable.")
        assert_no_raw_blocked_output(blocked.stdout + blocked.stderr, [BLOCKED_FIXTURE], "directory text scan")

        reset_sandbox(sandbox)
        write_text(sandbox / "src" / "external-env.txt", BLOCKED_ENV_FIXTURE + "=true")
        blocked_external_env = invoke_naming_check(sandbox, with_extra_fixture=True)
        require_failure(blocked_external_env, "Naming check did not block a forbidden external-name environment variable.")
        assert_no_raw_blocked_output(blocked_external_env.stdout + blocked_external_env.stderr, [BLOCKED_ENV_FIXTURE], "external-name environment variable scan")

        reset_sandbox(sandbox)
        write_text(sandbox / "src" / "legacy-project-a-env.txt", BLOCKED_LEGACY_PROJECT_A_ENV + "=true")
        blocked_legacy_project_a = invoke_naming_check(sandbox)
        require_failure(blocked_legacy_project_a, "Naming check did not block a forbidden project-specific private URL environment variable.")
        assert_no_raw_blocked_output(blocked_legacy_project_a.stdout + blocked_legacy_project_a.stderr, [BLOCKED_LEGACY_PROJECT_A_ENV], "project-specific private URL environment variable scan")

        reset_sandbox(sandbox)
        write_text(sandbox / "src" / "legacy-project-b-env.txt", BLOCKED_LEGACY_PROJECT_B_ENV + "=true")
        blocked_legacy_project_b = invoke_naming_check(sandbox)
        require_failure(blocked_legacy_project_b, "Naming check did not block a forbidden alternate-project private URL environment variable.")
        assert_no_raw_blocked_output(blocked_legacy_project_b.stdout + blocked_legacy_project_b.stderr, [BLOCKED_LEGACY_PROJECT_B_ENV], "alternate-project private URL environment variable scan")

        reset_sandbox(sandbox)
        write_text(sandbox / "docs" / "external-name.md", "Old upstream name: " + BLOCKED_ENV_FIXTURE)
        blocked_external_name = invoke_naming_check(sandbox, with_extra_fixture=True)
        require_failure(blocked_external_name, "Naming check did not block a forbidden external project name.")
        assert_no_raw_blocked_output(blocked_external_name.stdout + blocked_external_name.stderr, [BLOCKED_ENV_FIXTURE], "external project name scan")

        reset_sandbox(sandbox)
        (sandbox / (BLOCKED_FIXTURE + "-dir")).mkdir(parents=True)
        blocked_directory_path = invoke_naming_check(sandbox, with_extra_fixture=True)
        require_failure(blocked_directory_path, "Naming check did not block forbidden naming in directory paths.")
        assert_no_raw_blocked_output(blocked_directory_path.stdout + blocked_directory_path.stderr, [BLOCKED_FIXTURE], "directory path scan")

        reset_sandbox(sandbox)
        write_text(sandbox / "web" / "node_modules" / "fixture" / "README.md", "Third-party text may mention " + BLOCKED_FIXTURE + ".")
        ignored = invoke_naming_check(sandbox, with_extra_fixture=True)
        require_success(ignored, "Naming check should ignore third-party dependency directories")

        reset_sandbox(sandbox)
        write_text(sandbox / "src" / "config.txt", BLOCKED_FIXTURE_LOWER + "=true")
        case_insensitive = invoke_naming_check(sandbox, with_extra_fixture=True)
        require_failure(case_insensitive, "Naming check did not block a forbidden term with different casing.")
        assert_no_raw_blocked_output(case_insensitive.stdout + case_insensitive.stderr, [BLOCKED_FIXTURE_LOWER], "case-insensitive directory text scan")

        reset_sandbox(sandbox)
        git_init(sandbox)
        write_text(sandbox / "README.md", "Clean fixture")
        git(sandbox, "add", "README.md")
        git(sandbox, "commit", "-m", "feat: clean fixture / Clean fixture")
        write_text(sandbox / "README.md", "Second fixture")
        git(sandbox, "add", "README.md")
        git(sandbox, "commit", "-m", "feat: " + BLOCKED_FIXTURE + " fixture")
        blocked_commit = invoke_git_naming_check(sandbox, git_range="HEAD", with_extra_fixture=True)
        require_failure(blocked_commit, "Naming check did not block forbidden naming in git commit subjects.")
        assert_no_raw_blocked_output(blocked_commit.stdout + blocked_commit.stderr, [BLOCKED_FIXTURE], "git commit subject scan")

        write_text(sandbox / "README.md", "Clean body fixture")
        git(sandbox, "add", "README.md")
        git(sandbox, "commit", "-m", "fix: clean subject with polluted body / Clean subject with polluted body", "-m", "body uses " + BLOCKED_DEFAULT_ENV_FIXTURE)
        blocked_commit_body = invoke_git_naming_check(sandbox, git_range="HEAD")
        require_failure(blocked_commit_body, "Naming check did not block forbidden naming in git commit messages.")
        assert_no_raw_blocked_output(blocked_commit_body.stdout + blocked_commit_body.stderr, [BLOCKED_DEFAULT_ENV_FIXTURE], "git commit body scan")

        write_text(sandbox / "README.md", BLOCKED_FIXTURE + " removed later")
        git(sandbox, "add", "README.md")
        git(sandbox, "commit", "-m", "fix: temporary polluted file / Temporary polluted file")
        write_text(sandbox / "README.md", "Clean again")
        git(sandbox, "add", "README.md")
        git(sandbox, "commit", "-m", "fix: clean polluted file / Clean polluted file")
        blocked_object_text = invoke_git_naming_check(sandbox, git_range="HEAD~2..HEAD", with_extra_fixture=True, check_object_text=True)
        require_failure(blocked_object_text, "Naming check did not block forbidden naming in git object text inside a release range.")
        assert_no_raw_blocked_output(blocked_object_text.stdout + blocked_object_text.stderr, [BLOCKED_FIXTURE], "git object text scan")

        write_text(sandbox / "README.md", "Clean default branch again")
        git(sandbox, "add", "README.md")
        git(sandbox, "commit", "-m", "fix: clean default branch again / Clean default branch again")
        git(sandbox, "checkout", "-b", "polluted-history-fixture", "HEAD~1")
        write_text(sandbox / "README.md", BLOCKED_FIXTURE + " only on another ref")
        git(sandbox, "add", "README.md")
        git(sandbox, "commit", "-m", "fix: polluted all-ref fixture / Polluted all-ref fixture")
        git(sandbox, "checkout", "main")
        default_range_clean = invoke_git_naming_check(sandbox, git_range="HEAD^..HEAD", with_extra_fixture=True, check_object_text=True)
        require_success(default_range_clean, "Naming check should keep explicit clean ranges independent from other refs")
        all_refs_blocked = invoke_git_naming_check(sandbox, with_extra_fixture=True, check_object_text=True, check_all_git_refs=True)
        require_failure(all_refs_blocked, "Naming check did not block forbidden naming in all reachable git refs.")
        assert_no_raw_blocked_output(all_refs_blocked.stdout + all_refs_blocked.stderr, [BLOCKED_FIXTURE], "all refs git object text scan")

        reset_sandbox(sandbox)
        git_init(sandbox)
        write_text(sandbox / "README.md", "Clean root")
        git(sandbox, "add", "README.md")
        git(sandbox, "commit", "-m", "chore: clean root / Clean root")
        root_commit = git(sandbox, "rev-parse", "HEAD")
        write_text(sandbox / "README.md", BLOCKED_FIXTURE + " only in historical upstream")
        git(sandbox, "add", "README.md")
        git(sandbox, "commit", "-m", "fix: historical upstream fixture / Historical upstream fixture")
        write_text(sandbox / "README.md", "Clean upstream after historical fixture")
        git(sandbox, "add", "README.md")
        git(sandbox, "commit", "-m", "fix: clean upstream after history / Clean upstream after history")
        git(sandbox, "checkout", "-b", "clean-pr")
        write_text(sandbox / "feature.txt", "Clean feature")
        git(sandbox, "add", "feature.txt")
        git(sandbox, "commit", "-m", "fix: clean feature / Clean feature")
        git(sandbox, "checkout", "main")
        git(sandbox, "merge", "--no-ff", "clean-pr", "-m", "merge: clean feature / Clean feature")
        rewritten_head = git(sandbox, "rev-parse", "HEAD")
        git(sandbox, "checkout", "-b", "clean-rewrite", root_commit)
        write_text(sandbox / "README.md", "Clean rewritten main")
        git(sandbox, "add", "README.md")
        git(sandbox, "commit", "-m", "merge: clean rewritten main / Clean rewritten main")
        previous_head = git(sandbox, "rev-parse", "HEAD")
        unsafe_range = invoke_git_naming_check(
            sandbox,
            git_range=f"{previous_head}..{rewritten_head}",
            with_extra_fixture=True,
            check_object_text=True,
        )
        require_failure(unsafe_range, "Unsafe force-push range should reproduce historical object scan failures")
        guard_range_output = run([
            "python3",
            str(GUARD_RANGE_SCRIPT),
            "--root-path",
            str(sandbox),
            "--event-name",
            "push",
            "--before-sha",
            previous_head,
            "--head-sha",
            rewritten_head,
            "--github-output-path",
            "",
        ], cwd=sandbox)
        require_success(guard_range_output, "Guard range resolver should handle non-fast-forward pushes")
        guard_range_text = guard_range_output.stdout + guard_range_output.stderr
        expected_guard_range = f"{rewritten_head}^..{rewritten_head}"
        if f"git_range={expected_guard_range}" not in guard_range_text:
            raise AssertionError("Guard range resolver should fall back to the head commit window for non-fast-forward pushes.")
        safe_range = invoke_git_naming_check(
            sandbox,
            git_range=expected_guard_range,
            with_extra_fixture=True,
            check_object_text=True,
        )
        require_success(safe_range, "Resolved guard range should avoid unrelated historical object text")

        reset_sandbox(sandbox)
        git_init(sandbox)
        write_text(sandbox / "README.md", "Clean baseline")
        git(sandbox, "add", "README.md")
        git(sandbox, "commit", "-m", "fix: " + BLOCKED_FIXTURE + " historical fixture")
        write_text(sandbox / "README.md", "Clean current branch")
        git(sandbox, "add", "README.md")
        git(sandbox, "commit", "-m", "fix: clean current branch / Clean current branch")
        git(sandbox, "remote", "add", "origin", str(sandbox))
        git(sandbox, "update-ref", "refs/remotes/origin/main", "HEAD")
        empty_current_branch_range = invoke_git_naming_check(sandbox, check_object_text=True, check_current_branch_range=True)
        require_success(empty_current_branch_range, "Naming check should skip git history scanning when current branch has no commits ahead")

        reset_sandbox(sandbox)
        git_init(sandbox)
        write_text(sandbox / "README.md", BLOCKED_FIXTURE + " before clean baseline")
        git(sandbox, "add", "README.md")
        git(sandbox, "commit", "-m", "fix: historical polluted release base / Historical polluted release base")
        git(sandbox, "tag", "-a", "v2000.01.01-deadbee", "-m", "Release v2000.01.01-deadbee")
        write_text(sandbox / "README.md", "Clean naming baseline")
        git(sandbox, "add", "README.md")
        git(sandbox, "commit", "-m", "chore: clean naming baseline / Clean naming baseline")
        clean_base = git(sandbox, "rev-parse", "HEAD")
        write_text(sandbox / "README.md", "Clean release change")
        git(sandbox, "add", "README.md")
        git(sandbox, "commit", "-m", "feat: clean release range / Clean release range")
        head = git(sandbox, "rev-parse", "HEAD")
        range_output = run([
            "python3",
            str(RELEASE_RANGE_SCRIPT),
            "--root-path",
            str(sandbox),
            "--head-sha",
            head,
            "--clean-naming-base",
            clean_base,
            "--github-output-path",
            "",
        ])
        require_success(range_output, "Release range resolver failed with an older polluted tag")
        range_text = range_output.stdout + range_output.stderr
        if clean_base + ".." + head not in range_text:
            raise AssertionError("Release range resolver should prefer the clean naming baseline over older tags.")
        if re.search(r"v2000\.01\.01-deadbee", range_text):
            raise AssertionError("Release range resolver should not use an older polluted tag as the release base.")

        reset_sandbox(sandbox)
        git_init(sandbox)
        write_text(sandbox / "README.md", "Clean release baseline")
        git(sandbox, "add", "README.md")
        git(sandbox, "commit", "-m", "chore: clean release baseline / Clean release baseline")
        clean_base = git(sandbox, "rev-parse", "HEAD")
        write_text(sandbox / "README.md", BLOCKED_DEFAULT_ENV_FIXTURE + " legacy release detail")
        git(sandbox, "add", "README.md")
        git(sandbox, "commit", "-m", "fix: historical release detail / Historical release detail")
        write_text(sandbox / "README.md", "Clean release head")
        git(sandbox, "add", "README.md")
        git(sandbox, "commit", "-m", "fix: clean release head / Clean release head")
        head = git(sandbox, "rev-parse", "HEAD")
        release_dir = sandbox / "dist"
        release_dir.mkdir(parents=True, exist_ok=True)
        release_notes_path = release_dir / "release-notes-polluted-range.md"
        polluted_release_output = run([
            "python3",
            str(RELEASE_NOTES_SCRIPT),
            "--root-path",
            str(sandbox),
            "--output-path",
            str(release_notes_path),
            "--tag",
            "v2099.01.02-bcdef01",
            "--version",
            "0.0.0-test",
            "--commit-range",
            clean_base + ".." + head,
            "--display-range",
            "clean naming baseline.." + head[:7],
        ], cwd=sandbox)
        require_success(polluted_release_output, "Release notes generation should ignore historical object text that is not emitted")
        assert_no_raw_blocked_output(polluted_release_output.stdout + polluted_release_output.stderr, [BLOCKED_DEFAULT_ENV_FIXTURE], "polluted release range fallback")
        polluted_release_text = release_notes_path.read_text(encoding="utf-8")
        if "fix: clean release head / Clean release head" not in polluted_release_text:
            raise AssertionError("Release notes should still include the current clean commit.")
        if "fix: historical release detail / Historical release detail" not in polluted_release_text:
            raise AssertionError("Release notes should keep clean commit summaries even when historical object text is polluted.")
        if "历史发布范围中有" in polluted_release_text:
            raise AssertionError("Release notes should not claim summary omission when only historical object text was polluted.")
        assert_no_raw_blocked_output(polluted_release_text, [BLOCKED_DEFAULT_ENV_FIXTURE], "polluted release notes file")

        write_text(sandbox / "README.md", "Clean release after polluted summary")
        git(sandbox, "add", "README.md")
        git(sandbox, "commit", "-m", "fix: clean after polluted summary / Clean after polluted summary")
        clean_after_polluted_summary = git(sandbox, "rev-parse", "HEAD")
        git(sandbox, "commit", "--allow-empty", "-m", "fix: " + BLOCKED_FIXTURE + " release summary")
        polluted_summary_head = git(sandbox, "rev-parse", "HEAD")
        release_notes_path = release_dir / "release-notes-polluted-summary.md"
        polluted_summary_output = run([
            "python3",
            str(RELEASE_NOTES_SCRIPT),
            "--root-path",
            str(sandbox),
            "--output-path",
            str(release_notes_path),
            "--tag",
            "v2099.01.03-cdef012",
            "--version",
            "0.0.0-test",
            "--commit-range",
            clean_after_polluted_summary + ".." + polluted_summary_head,
            "--display-range",
            "summary fixture range",
            "--extra-blocked-terms",
            BLOCKED_FIXTURE,
        ], cwd=sandbox)
        require_success(polluted_summary_output, "Release notes generation should omit polluted summary entries instead of failing")
        polluted_summary_text = release_notes_path.read_text(encoding="utf-8")
        if BLOCKED_FIXTURE in polluted_summary_text:
            raise AssertionError("Release notes should not emit blocked summary text.")
        if "历史发布范围中有 1 条提交摘要未通过命名检查" not in polluted_summary_text:
            raise AssertionError("Release notes should explain omitted polluted summary entries.")

        reset_sandbox(sandbox)
        git_init(sandbox)
        release_dir = sandbox / "dist"
        release_dir.mkdir(parents=True)
        release_notes_path = release_dir / "release-notes.md"
        release_output = run([
            "python3",
            str(RELEASE_NOTES_SCRIPT),
            "--root-path",
            str(sandbox),
            "--output-path",
            str(release_notes_path),
            "--tag",
            "v2099.01.01-abcdef0",
            "--version",
            "0.0.0-test",
            "--commit-range",
            "HEAD",
            "--display-range",
            "HEAD",
            "--extra-blocked-terms",
            BLOCKED_FIXTURE,
        ], cwd=sandbox)
        require_failure(release_output, "Release notes generation should fail on blocked legacy naming.")
        assert_no_raw_blocked_output(release_output.stdout + release_output.stderr, [BLOCKED_FIXTURE], "release notes generation")

        reset_sandbox(sandbox)
        release_dir = sandbox / "dist"
        release_dir.mkdir(parents=True)
        release_notes_path = release_dir / "release-notes.md"
        git_init(sandbox)
        write_text(sandbox / "README.md", "Clean release note fixture")
        for i in range(1, 10):
            write_text(sandbox / f"changed-{i}.txt", f"changed file {i}")
        git(sandbox, "add", "README.md")
        git(sandbox, "add", "changed-1.txt", "changed-2.txt", "changed-3.txt", "changed-4.txt", "changed-5.txt", "changed-6.txt", "changed-7.txt", "changed-8.txt", "changed-9.txt")
        git(sandbox, "commit", "-m", "fix: clean release notes / Clean release notes")
        write_text(sandbox / "README.md", "Scoped feature fixture")
        git(sandbox, "add", "README.md")
        git(sandbox, "commit", "-m", "feat(cron): scoped feature release note / Scoped feature release note", "-m", "功能：补充计划任务投递策略说明。", "-m", "Feature: add scheduled delivery policy details.")
        write_text(sandbox / "README.md", "Scoped fix fixture")
        git(sandbox, "add", "README.md")
        git(sandbox, "commit", "-m", "fix(api): scoped fix release note / Scoped fix release note", "-m", "缺陷修复：避免空提交范围只生成占位说明。", "-m", "Fix: avoid placeholder-only notes for empty ranges.")
        clean_release_output = run([
            "python3",
            str(RELEASE_NOTES_SCRIPT),
            "--root-path",
            str(sandbox),
            "--output-path",
            str(release_notes_path),
            "--tag",
            "v2099.01.03-cdef012",
            "--version",
            "0.0.0-test",
            "--commit-range",
            "HEAD",
            "--display-range",
            "HEAD",
        ], cwd=sandbox)
        require_success(clean_release_output, "Release notes generation should succeed for a clean range")
        clean_release_text = release_notes_path.read_text(encoding="utf-8")
        if "fix: clean release notes / Clean release notes" not in clean_release_text:
            raise AssertionError("Release notes generation did not include the clean commit subject.")
        if "影响文件 / Changed files:" in clean_release_text:
            raise AssertionError("Release notes generation should not include changed-file details for commits without body details.")
        if "README.md" in clean_release_text or "changed-1.txt" in clean_release_text:
            raise AssertionError("Release notes generation should not list changed files for commits without body details.")
        if not re.search(r"### 功能 / Features[\s\S]*feat\(cron\): scoped feature release note / Scoped feature release note", clean_release_text):
            raise AssertionError("Release notes generation did not classify scoped feat commits as features.")
        if not re.search(r"### 缺陷修复 / Fixes[\s\S]*fix\(api\): scoped fix release note / Scoped fix release note", clean_release_text):
            raise AssertionError("Release notes generation did not classify scoped fix commits as fixes.")
        if "`solon-claw.jar`" not in clean_release_text:
            raise AssertionError("Release notes generation should document the fixed release jar asset name.")
        if "`solon-claw-source.zip`" not in clean_release_text or "`solon-claw-source.tar.gz`" not in clean_release_text:
            raise AssertionError("Release notes generation should document source archive assets.")
        if "java -jar solon-claw.jar" not in clean_release_text:
            raise AssertionError("Release notes generation should use the fixed release jar asset in quick start.")
        if "legacy-agent-0.0.0-test.jar" in clean_release_text:
            raise AssertionError("Release notes generation should not document the versioned repository jar asset name.")

        package_name_check = invoke_github_package_check(sandbox)
        require_success(package_name_check, "GitHub package name check should pass for the configured solon-claw package image")
        unexpected_package_name_check = invoke_github_package_check(
            sandbox,
            "images: ghcr.io/chengliang4810/not-solon-claw\n",
        )
        require_failure(unexpected_package_name_check, "GitHub package name check should reject non solon-claw package images")

        reset_sandbox(sandbox)
        release_dir = sandbox / "dist"
        release_dir.mkdir(parents=True)
        release_notes_path = release_dir / "release-notes-empty-range.md"
        git_init(sandbox)
        write_text(sandbox / "README.md", "Fallback release note fixture")
        git(sandbox, "add", "README.md")
        git(sandbox, "commit", "-m", "fix: fallback release notes / Fallback release notes")
        empty_range_release = run([
            "python3",
            str(RELEASE_NOTES_SCRIPT),
            "--root-path",
            str(sandbox),
            "--output-path",
            str(release_notes_path),
            "--tag",
            "v2099.01.06-f012345",
            "--version",
            "0.0.0-test",
            "--commit-range",
            "HEAD..HEAD",
            "--display-range",
            "HEAD..HEAD",
        ], cwd=sandbox)
        require_success(empty_range_release, "Release notes generation should fall back to current commit for an empty range")
        empty_text = release_notes_path.read_text(encoding="utf-8")
        if "fix: fallback release notes / Fallback release notes" not in empty_text:
            raise AssertionError("Release notes empty-range fallback did not include the current commit subject.")
        if "No commits were explicitly marked as fix" in empty_text:
            raise AssertionError("Release notes empty-range fallback should not emit only the fix placeholder.")
        if not re.search(r"Commit range: `[0-9a-f]{7,}`", empty_text):
            raise AssertionError("Release notes empty-range fallback did not replace display range with current short commit.")

        reset_sandbox(sandbox)
        published_release_fixture = {
            "name": "solon-claw v2099.01.04-def0123",
            "tag_name": "v2099.01.04-def0123",
            "body": "Published release body mentions " + BLOCKED_DEFAULT_ENV_FIXTURE,
            "assets": [{"name": "solon-claw.jar", "label": "", "content_type": "application/java-archive"}],
        }
        published_release_path = sandbox / "published-release.json"
        published_release_path.write_text(json.dumps(published_release_fixture), encoding="utf-8")
        published_release = run(["python3", str(PUBLISHED_RELEASE_SCRIPT), "--local-json-path", str(published_release_path)])
        require_failure(published_release, "Published release naming check should fail when blocked naming exists in release metadata.")
        assert_no_raw_blocked_output(published_release.stdout + published_release.stderr, [BLOCKED_DEFAULT_ENV_FIXTURE], "published release metadata scan")

        clean_published_release_fixture = {
            "name": "solon-claw v2099.01.05-ef01234",
            "tag_name": "v2099.01.05-ef01234",
            "body": "Clean published release body",
            "assets": [{"name": "solon-claw.jar", "label": "", "content_type": "application/java-archive"}],
        }
        clean_published_release_path = sandbox / "published-release-clean.json"
        clean_published_release_path.write_text(json.dumps(clean_published_release_fixture), encoding="utf-8")
        clean_published_release = run(["python3", str(PUBLISHED_RELEASE_SCRIPT), "--local-json-path", str(clean_published_release_path)])
        require_success(clean_published_release, "Published release naming check should pass for clean release metadata")

        reset_sandbox(sandbox)
        archive_root = sandbox / "archive-root"
        archive_path = sandbox / "fixture.jar"
        (archive_root / "com" / "jimuqu" / "solon" / "claw").mkdir(parents=True)
        (archive_root / "com" / "jimuqu" / "solon" / "claw" / "Binary.class").write_bytes(("constant-pool " + BLOCKED_DEFAULT_ENV_FIXTURE + " value").encode("ascii"))
        create_zip(archive_root, archive_path)
        archive_output = run(["python3", str(ARCHIVE_NAMING_SCRIPT), "--archive-path", str(archive_path)])
        require_failure(archive_output, "Archive naming check should fail when blocked naming exists inside first-party packaged binary constants.")
        assert_no_raw_blocked_output(archive_output.stdout + archive_output.stderr, [BLOCKED_DEFAULT_ENV_FIXTURE], "archive binary constant scan")

        reset_sandbox(sandbox)
        third_party_root = sandbox / "third-party-archive-root"
        third_party_archive = sandbox / "third-party-fixture.jar"
        (third_party_root / "com" / "jimuqu" / "solon" / "claw").mkdir(parents=True)
        (third_party_root / "mozilla").mkdir(parents=True)
        (third_party_root / "com" / "lark" / "oapi" / "google" / "protobuf").mkdir(parents=True)
        write_text(third_party_root / "com" / "jimuqu" / "solon" / "claw" / "App.class", "clean first-party payload")
        write_text(third_party_root / "mozilla" / "public-suffix-list.txt", "third-party text may contain " + BLOCKED_DEFAULT_ENV_FIXTURE)
        (third_party_root / "com" / "lark" / "oapi" / "google" / "protobuf" / "AbstractMessage.class").write_bytes(("third-party binary " + BLOCKED_DEFAULT_ENV_FIXTURE + " value").encode("ascii"))
        create_zip(third_party_root, third_party_archive)
        third_party_archive_output = run(["python3", str(ARCHIVE_NAMING_SCRIPT), "--archive-path", str(third_party_archive)])
        require_success(third_party_archive_output, "Archive naming check should ignore shaded third-party dependency content")

        reset_sandbox(sandbox)
        clean_archive_root = sandbox / "clean-archive-root"
        clean_archive_path = sandbox / "clean-fixture.jar"
        write_text(clean_archive_root / "app.properties", "app.name=solon-claw")
        create_zip(clean_archive_root, clean_archive_path)
        clean_archive_output = run(["python3", str(ARCHIVE_NAMING_SCRIPT), "--archive-path", str(clean_archive_path)])
        require_success(clean_archive_output, "Archive naming check should pass for clean packaged content")
        return 0
    finally:
        shutil.rmtree(sandbox, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
