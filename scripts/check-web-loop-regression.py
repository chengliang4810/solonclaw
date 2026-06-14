#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_BASE_URL = "http://127.0.0.1:8080"
DEFAULT_SESSION_ID = "web-loop-todo-20260612-2234"
FINAL_MARKER = "web-loop-final-marker-summary-20260614-1850"
CHAIN_MARKERS = [
    "web-loop-due-noagent-local-20260614-1542",
    "web-loop-session-recovery-log-analysis-20260614-1554",
    "web-loop-todo-continuity-refresh-20260614-1629",
    "web-loop-todo-continuity-refresh-cleanup-20260614-1638",
    "web-loop-memory-continuity-20260614-1648",
    "web-loop-memory-replace-20260614-1700",
    "web-loop-memory-remove-20260614-1706",
    "web-loop-memory-search-20260614-1713",
    "web-loop-session-search-around-fix-20260614-1740",
    "web-loop-closeout-state-fix-20260614-1830",
]
REQUIRED_COVERAGE = [
    "long_conversation",
    "context_compression",
    "todo_continuity",
    "cron_trigger_cleanup",
    "memory_lifecycle",
    "session_search",
    "tool_output_assertability",
    "web_dashboard_visibility",
]


@dataclass
class Check:
    name: str
    ok: bool
    detail: Any = None


def load_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def api_get(base_url: str, token: str, path: str, timeout: float) -> Any:
    request = urllib.request.Request(
        base_url.rstrip("/") + path,
        headers={"Authorization": f"Bearer {token}"},
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        raise RuntimeError(f"GET {path} failed with HTTP {exc.code}: {exc.read().decode('utf-8', 'replace')}") from exc
    except urllib.error.URLError as exc:
        raise RuntimeError(f"GET {path} failed: {exc}") from exc
    return json.loads(body)


def record(checks: list[Check], name: str, ok: bool, detail: Any = None) -> None:
    checks.append(Check(name=name, ok=bool(ok), detail=detail))


def expect_equal(checks: list[Check], name: str, actual: Any, expected: Any) -> None:
    record(checks, name, actual == expected, {"actual": actual, "expected": expected})


def main() -> int:
    parser = argparse.ArgumentParser(description="Check the long Web Agent regression loop archive and live API state.")
    parser.add_argument("--root-path", default=str(REPO_ROOT))
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--token", default="admin")
    parser.add_argument("--session-id", default=DEFAULT_SESSION_ID)
    parser.add_argument("--timeout", type=float, default=10.0)
    parser.add_argument("--output", default="")
    args = parser.parse_args()

    root = Path(args.root_path).resolve()
    logs_dir = root / "runtime" / "logs"
    checks: list[Check] = []
    errors: list[str] = []

    archive_files: dict[str, str] = {}
    for marker in CHAIN_MARKERS:
        path = logs_dir / f"{marker}.final-summary.json"
        archive_files[marker] = str(path.relative_to(root))
        record(checks, f"archive_exists:{marker}", path.exists(), archive_files[marker])
        if path.exists():
            try:
                load_json(path)
                record(checks, f"archive_json:{marker}", True, archive_files[marker])
            except (OSError, json.JSONDecodeError) as exc:
                record(checks, f"archive_json:{marker}", False, str(exc))
                errors.append(str(exc))

    final_summary_path = logs_dir / f"{FINAL_MARKER}.final-summary.json"
    final_parse_path = logs_dir / f"{FINAL_MARKER}.final-parse.json"
    record(checks, "final_archive_exists", final_summary_path.exists(), str(final_summary_path.relative_to(root)))
    if final_summary_path.exists():
        try:
            load_json(final_summary_path)
            record(checks, "final_archive_json", True, str(final_summary_path.relative_to(root)))
        except (OSError, json.JSONDecodeError) as exc:
            record(checks, "final_archive_json", False, str(exc))
            errors.append(str(exc))

    final_summary: dict[str, Any] = {}
    record(checks, "final_parse_exists", final_parse_path.exists(), str(final_parse_path.relative_to(root)))
    if final_parse_path.exists():
        try:
            final_parse = load_json(final_parse_path)
            record(checks, "final_parse_json", True, str(final_parse_path.relative_to(root)))
            record(checks, "final_parse_ok", final_parse.get("ok") is True, final_parse.get("ok"))
            final_summary = final_parse.get("parsed") or {}
        except (OSError, json.JSONDecodeError) as exc:
            record(checks, "final_parse_json", False, str(exc))
            errors.append(str(exc))

    marker_results = final_summary.get("marker_results") or []
    marker_result_map = {item.get("marker"): item for item in marker_results if isinstance(item, dict)}
    expect_equal(checks, "final_marker", final_summary.get("marker"), FINAL_MARKER)
    expect_equal(checks, "chain_length", final_summary.get("chain_length"), len(CHAIN_MARKERS))
    expect_equal(checks, "all_prior_verdicts_pass", final_summary.get("all_prior_verdicts_pass"), True)
    expect_equal(checks, "final_task_completed", final_summary.get("task_completed"), True)
    expect_equal(checks, "final_failure_layer", final_summary.get("failure_layer"), "none")
    expect_equal(checks, "final_needs_code_fix", final_summary.get("needs_code_fix"), False)

    coverage = final_summary.get("coverage_summary") or {}
    for name in REQUIRED_COVERAGE:
        expect_equal(checks, f"coverage:{name}", coverage.get(name), True)

    for marker in CHAIN_MARKERS:
        result = marker_result_map.get(marker) or {}
        record(checks, f"final_result_present:{marker}", bool(result), result)
        expect_equal(checks, f"final_result_verdict:{marker}", result.get("verdict"), "pass")
        expect_equal(checks, f"final_result_unresolved:{marker}", result.get("unresolved_failure"), False)

    live_status: dict[str, Any] = {}
    cron_status: dict[str, Any] = {}
    sessions: dict[str, Any] = {}
    try:
        live_status = api_get(args.base_url, args.token, "/api/status", args.timeout)
        record(checks, "api_status_reachable", True, args.base_url)
    except RuntimeError as exc:
        record(checks, "api_status_reachable", False, str(exc))
        errors.append(str(exc))
    try:
        cron_status = api_get(args.base_url, args.token, "/api/cron/jobs/status?include_disabled=true&limit=5", args.timeout)
        record(checks, "api_cron_status_reachable", True, args.base_url)
    except RuntimeError as exc:
        record(checks, "api_cron_status_reachable", False, str(exc))
        errors.append(str(exc))
    try:
        sessions = api_get(args.base_url, args.token, "/api/sessions?limit=25&offset=0", args.timeout)
        record(checks, "api_sessions_reachable", True, args.base_url)
    except RuntimeError as exc:
        record(checks, "api_sessions_reachable", False, str(exc))
        errors.append(str(exc))

    status_data = live_status.get("data") or {}
    runtime_status = status_data.get("runtime_status") or {}
    expect_equal(checks, "runtime_status_ok", runtime_status.get("status"), "ok")
    expect_equal(checks, "running_agent_runs", status_data.get("running_agent_runs"), 0)
    expect_equal(checks, "model", (runtime_status.get("model") or {}).get("model"), "mimo-v2.5-pro")

    cron_data = cron_status.get("data") or {}
    expect_equal(checks, "cron_total", cron_data.get("total"), 36)
    expect_equal(checks, "cron_active", cron_data.get("active"), 0)
    expect_equal(checks, "cron_due", cron_data.get("due"), 0)

    session_data = sessions.get("data") or {}
    session_rows = session_data.get("sessions") or []
    session_ids = [row.get("id") for row in session_rows if isinstance(row, dict)]
    record(checks, "session_present", args.session_id in session_ids, {"session_id": args.session_id, "seen": session_ids})

    check_rows = [{"name": item.name, "ok": item.ok, "detail": item.detail} for item in checks]
    ok = all(item.ok for item in checks)
    result = {
        "generated_at_epoch_ms": int(time.time() * 1000),
        "task_completed": ok,
        "failure_layer": "none" if ok else "dashboard_api_regression_harness",
        "needs_code_fix": False,
        "checked_markers": CHAIN_MARKERS,
        "final_marker": FINAL_MARKER,
        "session_id": args.session_id,
        "base_url": args.base_url,
        "checks": check_rows,
        "errors": errors,
        "live_summary": {
            "runtime_status": runtime_status.get("status"),
            "running_agent_runs": status_data.get("running_agent_runs"),
            "model": (runtime_status.get("model") or {}).get("model"),
            "cron_total": cron_data.get("total"),
            "cron_active": cron_data.get("active"),
            "cron_due": cron_data.get("due"),
            "session_present": args.session_id in session_ids,
        },
    }
    text = json.dumps(result, ensure_ascii=False, indent=2)
    if args.output:
        output_path = Path(args.output)
        if not output_path.is_absolute():
            output_path = root / output_path
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(text + "\n", encoding="utf-8")
    print(text)
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
