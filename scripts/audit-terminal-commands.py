#!/usr/bin/env python3
"""真实运行本地终端命令，检查命令是否可被用户按预期操作。"""

from __future__ import annotations

import argparse
import fcntl
import os
import pty
import re
import shlex
import shutil
import select
import signal
import subprocess
import sys
import termios
import tempfile
import time
import urllib.error
import urllib.request
import struct
from pathlib import Path

from guardlib import REPO_ROOT

DEFAULT_JAR = REPO_ROOT / "target" / "solonclaw-0.0.1.jar"

# 这些命令覆盖用户会直接输入的初始化、模型、渠道、会话、工具、安全和自动化入口。
DEFAULT_COMMANDS = [
    "/help",
    "/commands",
    "/commands 2",
    "/status",
    "/usage",
    "/whoami",
    "/insights",
    "/debug",
    "/version",
    "/models",
    "/model pick",
    "/model --refresh",
    "/fast",
    "/fast on",
    "/fast off",
    "/reasoning",
    "/reasoning hide",
    "/reasoning show",
    "/busy",
    "/busy queue",
    "/busy steer",
    "/security",
    "/security status",
    "/security policy",
    "/security audit",
    "/security urls",
    "/security approvals",
    "/security slash-confirm",
    "/approve list",
    "/deny list",
    "/confirm",
    "/yolo status",
    "/new",
    "/title 测试标题",
    "/sessions",
    "/sessions stats",
    "/history",
    "/recap",
    "/trajectory",
    "/compact 测试压缩",
    "/goal status",
    "/tools",
    "/toolsets",
    "/browser",
    "/plugins",
    "/skills",
    "/curator",
    "/reload-skills",
    "/reload-mcp",
    "/mcp list",
    "/cron",
    "/cron guide",
    "/cron capabilities",
    "/cron policy",
    "/cron status",
    "/cron next",
    "/pairing list",
    "/platforms",
    "/platform",
    "/gateway status",
    "/setup",
    "/setup model",
    "/setup gateway",
    "/config path",
    "/config check",
    "/doctor",
    "/attachments /tmp/example.png",
    "/tips",
    "/skin",
    "/tasks",
    "/events",
    "/transcript",
    "/copy",
    "model",
    "setup",
    "setup model",
    "setup gateway",
    "gateway",
    "gateway status",
    "config path",
    "config check",
    "doctor",
    "sessions stats",
    "hooks list",
    "dump --json",
    "backup create",
    "checkpoints list",
    "import sessions file.json",
    "bundles list",
    "memory status",
    "dashboard start",
    "logs --tail 20",
    "prompt-size hello world",
]

AUDIT_MODEL_BASE_URL = "http://127.0.0.1:9/v1"

WRITE_COMMANDS = [
    f"model set --provider local-openai --base-url {AUDIT_MODEL_BASE_URL} "
    "--api-key Sk-Audit-Model-Secret123 --model smoke-model --dialect openai",
    "setup gateway feishu --app-id audit_app --app-secret Sk-Audit-Feishu-Secret123 --enabled true",
    "config show",
    "doctor",
]

SUSPECT_PATTERNS = [
    "run started",
    "Error code:",
    "Exception",
    "NullPointerException",
    "error: timeout:",
    "gateway websocket",
    "暂时无法使用模型服务",
    "运行失败：用法",
]

EXPECTED_EMPTY_STATE_PATTERNS = {
    "/retry": ["没有可重试的上一条用户消息"],
    "/resume": ["未找到对应会话、分支或标题"],
    "/sethome": ["只有平台管理员可以执行 /sethome"],
    "/always": ["当前没有待确认的 slash 命令"],
    "/cancel": ["当前没有待确认的 slash 命令"],
}

SECRET_PATTERNS = [
    "Sk-Audit-Model-Secret123",
    "Sk-Audit-Feishu-Secret123",
    "Sk-Audit-Node-Tui-Secret123",
]

TUI_COMMANDS = [
    "/setup",
    "/setup model",
    "/setup gateway",
    "/model --refresh",
    "/config path",
    "/config check",
    "/doctor",
    "/new",
    "/skin",
]

NODE_TUI_ACTIONS = [
    {"type": "command", "value": "/setup", "expect": "模型、渠道与工作区检查", "after": "q", "close_expect": "ready"},
    {"type": "panel", "value": "/setup", "expect": "模型、渠道与工作区检查", "keys": "\r", "post_expect": "Select provider", "after": "q", "close_expect": "ready"},
    {"type": "panel", "value": "/setup", "expect": "模型、渠道与工作区检查", "keys": "\x1b[B\r", "post_expect": "Channel setup", "after": "q", "close_expect": "ready"},
    {"type": "panel", "value": "/setup", "expect": "模型、渠道与工作区检查", "keys": "\x1b[B\x1b[B\r", "post_expect": "model.provider", "after": "q", "close_expect": "ready"},
    {"type": "command", "value": "/setup model", "expect": "Select provider", "after": "q", "close_expect": "ready"},
    {"type": "command", "value": "/setup gateway", "expect": "Channel setup", "after": "q", "close_expect": "ready"},
    {"type": "command", "value": "/model --refresh", "expect": "Models", "after": "q", "close_expect": "ready"},
    {"type": "command", "value": "/config path", "expect": "config.yml"},
    {"type": "command", "value": "/config check", "expect": "has_issues=false", "after": "q", "close_expect": "ready"},
    {"type": "command", "value": "/skin", "expect": "skin:"},
    {"type": "command", "value": "/doctor", "expect": "model.provider", "after": "q", "close_expect": "ready"},
    {"type": "command", "value": "/exit", "exit": True},
]

NODE_TUI_NEW_SESSION_ACTIONS = [
    {"type": "command", "value": "/new", "expect": "forging session"},
]

ANSI_PATTERN = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")
OSC_PATTERN = re.compile(r"\x1b\][^\x07]*(?:\x07|\x1b\\)")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Audit local terminal command behavior.")
    parser.add_argument("--jar", default=str(DEFAULT_JAR), help="可执行 jar 路径。")
    parser.add_argument("--workspace-home", default="", help="临时工作区目录；默认自动创建。")
    parser.add_argument("--timeout-seconds", type=float, default=15.0, help="单条命令超时秒数。")
    parser.add_argument("--no-defaults", action="store_true", help="不运行默认命令集，只运行显式追加命令或生命周期场景。")
    parser.add_argument("--include-write-commands", action="store_true", help="追加临时配置写入烟测。")
    parser.add_argument("--cron-lifecycle", action="store_true", help="创建真实 Cron 任务并审计 inspect/pause/resume/history/remove 生命周期。")
    parser.add_argument("--tui-pty", action="store_true", help="通过伪终端真实启动 --tui 并逐行输入常用命令。")
    parser.add_argument("--node-tui-pty", action="store_true", help="真实启动 solonclaw server + Node TUI，并模拟用户逐键输入常用指令。")
    parser.add_argument("--node-tui-port", type=int, default=18081, help="Node TUI 审计使用的本地后端端口。")
    parser.add_argument("--node-tui-command", action="append", default=[], help="替换默认 Node TUI 动作为指定命令；可重复。")
    parser.add_argument("--tui-command", action="append", default=[], help="追加一条 TUI 伪终端交互命令。")
    parser.add_argument("--keep-workspace", action="store_true", help="保留自动创建的工作区目录。")
    parser.add_argument("--command", action="append", default=[], help="追加一条要审计的命令。")
    return parser.parse_args()


def build_command_list(
    no_defaults: bool,
    include_write_commands: bool,
    explicit_commands: list[str],
) -> list[str]:
    commands: list[str] = [] if no_defaults else list(DEFAULT_COMMANDS)
    if include_write_commands:
        commands.extend(WRITE_COMMANDS)
    commands.extend(explicit_commands)
    return commands


def command_to_java_args(command: str) -> list[str]:
    if command.startswith("/"):
        return ["--cli", "-p", command]
    return shlex.split(command)


def command_name(command: str) -> str:
    value = (command or "").strip()
    if not value:
        return ""
    first = value.split(maxsplit=1)[0]
    return first.lower()


def is_expected_empty_state(command: str, output: str) -> bool:
    patterns = EXPECTED_EMPTY_STATE_PATTERNS.get(command_name(command), [])
    return any(pattern in (output or "") for pattern in patterns)


def is_suspect_command_result(command: str, exit_code: int, timeout: bool, combined_output: str) -> bool:
    if timeout:
        return True
    if exit_code != 0:
        return not is_expected_empty_state(command, combined_output)
    return any(pattern in combined_output for pattern in SUSPECT_PATTERNS)


def strip_ansi(text: str) -> str:
    return ANSI_PATTERN.sub("", OSC_PATTERN.sub("", text or ""))


def normalize_terminal_text(text: str) -> str:
    return strip_ansi(text).replace("\r\n", "\n").replace("\r", "\n")


def compact_terminal_text(text: str) -> str:
    return re.sub(r"\s+", "", normalize_terminal_text(text))


def contains_terminal_text(text: str, expected: str) -> bool:
    expected = expected.strip()
    if not expected:
        return True
    normalized = normalize_terminal_text(text)
    if expected in normalized:
        return True
    return re.sub(r"\s+", "", expected) in compact_terminal_text(text)


def contains_node_tui_ready_state(text: str) -> bool:
    """判断 Node TUI 是否已经进入可输入状态，兼容未完成首次设置的阻塞页。"""
    return any(
        contains_terminal_text(text, expected)
        for expected in ["ready", "setup required", "需要先完成设置"]
    )


def contains_node_tui_banner(text: str) -> bool:
    """判断 Node TUI 启动横幅是否已经绘制，避免被大写 ASCII Logo 影响。"""
    normalized = normalize_terminal_text(text).lower()
    return "solonclaw" in normalized or "solonclawagent" in compact_terminal_text(text).lower()


def process_output_text(value: object) -> str:
    """把 subprocess 输出归一为字符串，兼容 timeout 时返回 bytes 的情况。"""
    if value is None:
        return ""
    if isinstance(value, bytes):
        return value.decode("utf-8", errors="replace")
    return str(value)


def contains_new_terminal_text(output: bytearray, expected: str, start_len: int) -> bool:
    """只在 start_len 之后的新 PTY 输出中查找文本，避免命中历史帧造成误判。"""
    expected = expected.strip()
    if not expected:
        return True
    if start_len < 0:
        start_len = 0
    if start_len > len(output):
        start_len = len(output)
    return contains_terminal_text(output[start_len:].decode("utf-8", errors="replace"), expected)


def build_tui_script(commands: list[str]) -> str:
    lines = list(commands)
    if not lines or lines[-1].strip().lower() not in {"/exit", "/quit", "/exit!", "/quit!"}:
        lines.append("/exit")
    return "".join(command + "\r" for command in lines)


def build_keypress_schedule(actions: list[dict[str, object]], start_delay: float = 5.0) -> list[tuple[float, str]]:
    schedule: list[tuple[float, str]] = []
    at = start_delay
    for action in actions:
        value = str(action.get("value", ""))
        for char in value:
            schedule.append((at, char))
            at += 0.035
        schedule.append((at, "\r"))
        at += float(action.get("wait", 2.2))
        after = str(action.get("after", ""))
        if after:
            schedule.append((at, after))
            at += float(action.get("after_wait", 0.8))
    return schedule


def build_node_tui_actions(explicit_commands: list[str]) -> list[dict[str, object]]:
    """把命令行传入的 Node TUI 命令转换成逐键审计动作。"""
    actions: list[dict[str, object]] = []
    setup_panel_aliases = {
        "/audit:direct-shell-outside-write-deny": {
            "type": "approval",
            "value": "!sh -c 'printf audit > /tmp/solonclaw-node-tui-approval-audit.txt'",
            "expect": "approval required",
            "keys": "4",
            "post_expect": "ready",
        },
        "/audit:direct-shell-outside-write-allow-once": {
            "type": "approval",
            "value": "!sh -c 'printf audit > /tmp/solonclaw-node-tui-approval-audit.txt'",
            "expect": "approval required",
            "keys": "1",
            "post_expect": "exit 0",
        },
        "/setup:enter-model": {
            "type": "panel",
            "value": "/setup",
            "expect": "模型、渠道与工作区检查",
            "keys": "\r",
            "post_expect": "Select provider",
            "after": "q",
            "close_expect": "ready",
        },
        "/setup:enter-channels": {
            "type": "panel",
            "value": "/setup",
            "expect": "模型、渠道与工作区检查",
            "keys": "\x1b[B\r",
            "post_expect": "Channel setup",
            "after": "q",
            "close_expect": "ready",
        },
        "/setup:enter-doctor": {
            "type": "panel",
            "value": "/setup",
            "expect": "模型、渠道与工作区检查",
            "keys": "\x1b[B\x1b[B\r",
            "post_expect": "model.provider",
            "after": "q",
            "close_expect": "ready",
        },
    }
    default_actions: dict[str, dict[str, object]] = {}
    for action in [*NODE_TUI_ACTIONS, *NODE_TUI_NEW_SESSION_ACTIONS]:
        value = str(action.get("value", "")).strip().lower()
        if value and value not in default_actions:
            default_actions[value] = dict(action)
    for command in explicit_commands:
        value = command.strip()
        if not value:
            continue
        alias_action = setup_panel_aliases.get(value.lower())
        action: dict[str, object] = dict(alias_action or default_actions.get(value.lower(), {}))
        if not action:
            action["type"] = "command"
            action["value"] = value
            inferred_expect = node_tui_command_expectation(value)
            if inferred_expect:
                action["expect"] = inferred_expect
                if node_tui_command_needs_input_settle_wait(value):
                    action["wait"] = 3.0
            if node_tui_command_opens_panel(value):
                action["after"] = "\x1b"
                action["after_wait"] = 1.2
                if node_tui_command_requires_close_ready(value):
                    action["close_expect"] = "ready"
        else:
            action.setdefault("type", "command")
            action.setdefault("value", value)
        if value.lower() in {"/exit", "/quit", "/exit!", "/quit!"}:
            action["exit"] = True
            action.setdefault("keys", "\r")
        actions.append(action)
    return actions


def node_tui_command_opens_panel(command: str) -> bool:
    """判断显式 Node TUI 命令是否通常打开可关闭面板。"""
    value = command.strip().lower()
    return (
        value == "/doctor"
        or value.startswith("/doctor ")
        or value == "/logs"
        or value.startswith("/logs ")
        or value == "/status"
        or value.startswith("/status ")
        or value == "/config check"
        or value.startswith("/config check ")
        or value == "/model --refresh"
        or value == "/model"
        or value.startswith("/model set ")
        or value.startswith("/model configure ")
        or value == "/sessions"
        or value == "/skills"
        or value == "/setup"
        or value.startswith("/setup ")
        or value == "/approve list"
        or value == "/approve status"
        or value == "/deny list"
        or value == "/deny status"
        or value == "/tasks"
        or value == "/help"
        or value.startswith("/help ")
        or value == "/mem"
        or value.startswith("/mem ")
    )


def node_tui_command_requires_close_ready(command: str) -> bool:
    """判断关闭显式 Node TUI 命令面板后是否应等待新的 ready 文本。"""
    value = command.strip().lower()
    return not (
        value == "/help"
        or value.startswith("/help ")
        or value == "/mem"
        or value.startswith("/mem ")
    )


def node_tui_command_needs_input_settle_wait(command: str) -> bool:
    """判断命令输出不需要关闭但需要等待输入框清空后再继续。"""
    value = command.strip().lower()
    return (
        value == "/help"
        or value.startswith("/help ")
        or value == "/mem"
        or value.startswith("/mem ")
    )


def node_tui_command_expectation(command: str) -> str:
    """为显式 Node TUI 命令补充稳定的成功文本，避免过早关闭面板。"""
    value = command.strip().lower()
    if value == "/help" or value.startswith("/help "):
        return "Hotkeys"
    if value.startswith("/model set ") or value.startswith("/model configure "):
        return "模型配置已写入"
    if value == "/mouse" or value.startswith("/mouse "):
        return "tracking"
    if value == "/density" or value.startswith("/density "):
        return "compact"
    if value.startswith("/details tools "):
        return ": expanded"
    if value == "/details" or value.startswith("/details "):
        return "details:"
    if value == "/history" or value.startswith("/history "):
        return "versation"
    if value == "/queue" or value.startswith("/queue "):
        return "message(s)"
    if value == "/voice status":
        return "Voice Mode Status"
    if value == "/skin" or value.startswith("/skin "):
        return "skin:"
    if value == "/indicator" or value.startswith("/indicator "):
        return "indicator:"
    if value == "/yolo" or value.startswith("/yolo "):
        return "is not available"
    if value == "/reasoning" or value.startswith("/reasoning "):
        return "reasoning:"
    if value == "/fast" or value == "/fast status":
        return "fast mode:"
    if value == "/busy" or value == "/busy status":
        return "busy input mode:"
    if value == "/usage":
        return "no API calls yet"
    if value == "/stop" or value.startswith("/stop "):
        return "background processes"
    if value == "/browser status":
        return "browser not connected"
    if value == "/rollback" or value == "/rollback list":
        return "checkpoints"
    if value in {"/approve list", "/approve status", "/deny list", "/deny status"}:
        return "pending=none"
    if value == "/tasks status":
        return "delegation"
    if value == "/replay list":
        return "spawn trees"
    if value == "/mem" or value.startswith("/mem "):
        return "Memory"
    if value == "/statusbar" or value.startswith("/statusbar "):
        return "status bar"
    if value == "/verbose" or value.startswith("/verbose "):
        return "verbose:"
    if value == "/save":
        return "no conversation yet"
    if value == "/undo":
        return "nothing to undo"
    if value == "/redraw":
        return "ready"
    if value == "/compress" or value == "/compact":
        return "nothing to compress"
    if value == "/steer":
        return "usage: /steer"
    if value == "/background":
        return "/background <prompt>"
    if value == "/image":
        return "/image <path>"
    if value.startswith("/paste "):
        return "/paste"
    if value == "/status" or value.startswith("/status "):
        return "model="
    return ""


def contains_node_tui_command_text(transcript: str, command: str) -> bool:
    """匹配用户输入命令；长命令允许只匹配首个命令词，避免终端换行重排误报。"""
    if contains_terminal_text(transcript, command):
        return True
    value = command.strip()
    if len(value) <= 40:
        return False
    first = value.split(" ", 1)[0]
    return bool(first) and contains_terminal_text(transcript, first)


def audit_tui_transcript(transcript: str, commands: list[str]) -> list[str]:
    text = normalize_terminal_text(transcript)
    issues: list[str] = []
    for command in commands:
        value = command.strip()
        if not value or value.lower() in {"/exit", "/quit", "/exit!", "/quit!"}:
            continue
        prompt_echo = re.search(r"(?:^|\n)[^\n>]{0,16}>\s*" + re.escape(value) + r"(?:\n|$)", text)
        if not prompt_echo:
            issues.append(f"missing_prompt_echo:{value}")
    for pattern in SUSPECT_PATTERNS:
        if pattern in text:
            issues.append(f"suspect_pattern:{pattern}")
    if "<think>" in text or "</think>" in text:
        issues.append("visible_think_tag")
    return issues


def audit_node_tui_transcript(transcript: str, actions: list[dict[str, object]], exit_code: int) -> list[str]:
    issues: list[str] = []
    if exit_code != 0:
        issues.append(f"exit_code:{exit_code}")
    for action in actions:
        command = str(action.get("value", "")).strip()
        if not command:
            continue
        if action.get("exit"):
            continue
        expected = str(action.get("expect", "")).strip()
        has_expected = not expected or contains_terminal_text(transcript, expected)
        if not has_expected and not contains_node_tui_command_text(transcript, command):
            issues.append(f"missing_command_text:{command}")
        if expected and not contains_terminal_text(transcript, expected):
            issues.append(f"missing_expected_text:{expected}")
        post_expected = str(action.get("post_expect", "")).strip()
        if post_expected and not contains_terminal_text(transcript, post_expected):
            issues.append(f"missing_post_expected_text:{post_expected}")
    for pattern in SUSPECT_PATTERNS:
        if pattern in normalize_terminal_text(transcript):
            issues.append(f"suspect_pattern:{pattern}")
    if "<think>" in normalize_terminal_text(transcript) or "</think>" in normalize_terminal_text(transcript):
        issues.append("visible_think_tag")
    return issues


def extract_cron_job_id(output: str) -> str:
    text = output or ""
    match = re.search(r"\bjob_id=([0-9a-fA-F]{16,64})\b", text)
    if match:
        return match.group(1)
    match = re.search(r"已创建定时任务：([0-9a-fA-F]{16,64})", text)
    return match.group(1) if match else ""


def wait_for_http(url: str, timeout_seconds: float) -> tuple[bool, str]:
    deadline = time.monotonic() + timeout_seconds
    last_error = ""
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=1.0) as response:
                return True, response.read().decode("utf-8", errors="replace")
        except (OSError, urllib.error.URLError) as exc:
            last_error = str(exc)
            time.sleep(0.25)
    return False, last_error


class TerminalResponseState:
    """记录 PTY 审计已处理的输出位置，避免同一条终端查询被重复回包。"""

    def __init__(self) -> None:
        self.offset = 0


def terminal_query_responses(output: bytearray, state: TerminalResponseState) -> bytes:
    """根据 TUI 输出中的终端能力查询生成真实终端会写回的响应。"""
    chunk = bytes(output[state.offset :])
    state.offset = len(output)
    responses: list[bytes] = []
    if b"\x1b[>0q" in chunk:
        responses.append(b"\x1bP>|xterm.js(5.5.0)\x1b\\")
    if b"\x1b[c" in chunk:
        responses.append(b"\x1b[?1;2c")
    if b"\x1b[?u" in chunk:
        responses.append(b"\x1b[?0u")
    if b"\x1b[>c" in chunk:
        responses.append(b"\x1b[>0;276;0c")
    if b"\x1b[?6n" in chunk:
        responses.append(b"\x1b[?1;1R")
    return b"".join(responses)


def build_node_tui_env(workspace_home: Path, port: int) -> dict[str, str]:
    """构造真实 Node TUI 审计环境，隔离前端状态并连接本次启动的后端。"""
    env = dict(os.environ)
    env.setdefault("LC_ALL", "C.UTF-8")
    env.setdefault("LANG", "C.UTF-8")
    env.setdefault("TERM", "xterm-256color")
    env.setdefault("SOLONCLAW_TERMINAL_SKIN", "mono")
    env["SOLONCLAW_HOME"] = str(workspace_home)
    env["SOLONCLAW_SERVER_URL"] = f"http://127.0.0.1:{port}"
    env["SOLONCLAW_TUI_INLINE"] = "1"
    env["SOLONCLAW_TUI_MOUSE_TRACKING"] = "0"
    env["SOLONCLAW_TUI_NO_CONFIRM"] = "1"
    return env


def read_pty(
    master_fd: int,
    output: bytearray,
    wait_seconds: float,
    response_state: TerminalResponseState | None = None,
) -> None:
    deadline = time.monotonic() + wait_seconds
    while time.monotonic() < deadline:
        remaining = max(0.0, min(0.05, deadline - time.monotonic()))
        if remaining <= 0:
            return
        readable, _, _ = select.select([master_fd], [], [], remaining)
        if master_fd in readable:
            try:
                chunk = os.read(master_fd, 8192)
            except OSError:
                return
            if chunk:
                output.extend(chunk)
                if response_state is not None:
                    response = terminal_query_responses(output, response_state)
                    if response:
                        os.write(master_fd, response)


def write_text_like_user(
    master_fd: int,
    output: bytearray,
    text: str,
    response_state: TerminalResponseState | None = None,
) -> None:
    for char in text:
        os.write(master_fd, char.encode("utf-8"))
        read_pty(master_fd, output, 0.06, response_state)


def write_command_like_user(
    master_fd: int,
    output: bytearray,
    command: str,
    response_state: TerminalResponseState | None = None,
) -> None:
    # Bracketed paste avoids losing characters while the completion menu repaints.
    os.write(master_fd, b"\x1b[200~")
    read_pty(master_fd, output, 0.05, response_state)
    os.write(master_fd, command.encode("utf-8"))
    read_pty(master_fd, output, 0.08, response_state)
    os.write(master_fd, b"\x1b[201~")
    read_pty(master_fd, output, 0.18, response_state)
    os.write(master_fd, b"\r")
    read_pty(master_fd, output, 0.18, response_state)


def wait_for_text(
    master_fd: int,
    output: bytearray,
    expected: str,
    timeout_seconds: float,
    response_state: TerminalResponseState | None = None,
) -> bool:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        if contains_terminal_text(output.decode("utf-8", errors="replace"), expected):
            return True
        read_pty(master_fd, output, 0.1, response_state)
    return contains_terminal_text(output.decode("utf-8", errors="replace"), expected)


def wait_for_node_tui_ready_state(
    master_fd: int,
    output: bytearray,
    timeout_seconds: float,
    response_state: TerminalResponseState | None = None,
) -> bool:
    """等待 Node TUI 进入 ready 或首次设置阻塞页，两者都表示用户可以继续输入命令。"""
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        if contains_node_tui_ready_state(output.decode("utf-8", errors="replace")):
            return True
        read_pty(master_fd, output, 0.1, response_state)
    return contains_node_tui_ready_state(output.decode("utf-8", errors="replace"))


def wait_for_node_tui_banner(
    master_fd: int,
    output: bytearray,
    timeout_seconds: float,
    response_state: TerminalResponseState | None = None,
) -> bool:
    """等待 Node TUI 品牌横幅出现，大小写与 ANSI 间距差异都应被接受。"""
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        if contains_node_tui_banner(output.decode("utf-8", errors="replace")):
            return True
        read_pty(master_fd, output, 0.1, response_state)
    return contains_node_tui_banner(output.decode("utf-8", errors="replace"))


def wait_for_new_text(
    master_fd: int,
    output: bytearray,
    expected: str,
    start_len: int,
    timeout_seconds: float,
    response_state: TerminalResponseState | None = None,
) -> bool:
    """等待 start_len 之后出现目标文本，用于 overlay 关闭后的新一帧确认。"""
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        if contains_new_terminal_text(output, expected, start_len):
            return True
        read_pty(master_fd, output, 0.1, response_state)
    return contains_new_terminal_text(output, expected, start_len)


def run_command(jar: Path, workspace_home: Path, command: str, index: int, timeout_seconds: float) -> dict[str, object]:
    out_path = workspace_home / f"audit-{index:03d}.out"
    err_path = workspace_home / f"audit-{index:03d}.err"
    java_args = ["java", f"-Dsolonclaw.workspace={workspace_home}", "-jar", str(jar)]
    java_args.extend(command_to_java_args(command))
    env = dict(os.environ)
    env.setdefault("LC_ALL", "C")
    env.setdefault("LANG", "C")
    env["SOLONCLAW_WORKSPACE"] = str(workspace_home)
    env["SOLONCLAW_HOME"] = str(workspace_home)
    try:
        completed = subprocess.run(
            java_args,
            cwd=str(REPO_ROOT),
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=timeout_seconds,
            check=False,
        )
        stdout = completed.stdout
        stderr = completed.stderr
        exit_code = completed.returncode
        timeout = False
    except subprocess.TimeoutExpired as exc:
        stdout = process_output_text(exc.stdout)
        stderr = process_output_text(exc.stderr)
        exit_code = 124
        timeout = True

    out_path.write_text(stdout, encoding="utf-8")
    err_path.write_text(stderr, encoding="utf-8")
    combined = stdout + "\n" + stderr
    suspect = is_suspect_command_result(command, exit_code, timeout, combined)
    secret_leak = any(pattern in combined for pattern in SECRET_PATTERNS)
    return {
        "command": command,
        "exit_code": exit_code,
        "timeout": timeout,
        "suspect": suspect,
        "secret_leak": secret_leak,
        "out_path": out_path,
        "err_path": err_path,
    }


def print_excerpt(path: Path, prefix: str) -> None:
    if not path.exists():
        return
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    for line in lines[:8]:
        print(f"  {prefix}: {line}", flush=True)


def report_result(index: int, result: dict[str, object], findings: list[dict[str, object]]) -> None:
    status = "SUSPECT" if result["suspect"] or result["secret_leak"] else "ok"
    print(f"{index:03d} {status} {result['command']}", flush=True)
    if result["suspect"] or result["secret_leak"]:
        findings.append(result)
        print(
            f"  exit={result['exit_code']} timeout={result['timeout']} secret_leak={result['secret_leak']}",
            flush=True,
        )
        print_excerpt(result["out_path"], "OUT")
        print_excerpt(result["err_path"], "ERR")


def run_tui_pty(
    jar: Path,
    workspace_home: Path,
    commands: list[str],
    timeout_seconds: float,
    findings: list[dict[str, object]],
) -> int:
    out_path = workspace_home / "audit-tui-pty.out"
    err_path = workspace_home / "audit-tui-pty.err"
    java_args = [
        "java",
        f"-Dsolonclaw.workspace={workspace_home}",
        "-jar",
        str(jar),
        "--tui",
    ]
    env = dict(os.environ)
    env.setdefault("LC_ALL", "C")
    env.setdefault("LANG", "C")
    env.setdefault("TERM", "xterm-256color")
    env.setdefault("SOLONCLAW_TERMINAL_SKIN", "mono")
    pid, master_fd = pty.fork()
    if pid == 0:
        os.chdir(str(REPO_ROOT))
        os.execvpe(java_args[0], java_args, env)

    output = bytearray()
    deadline = time.monotonic() + timeout_seconds
    exit_code = 124
    try:
        while True:
            remaining = max(0.0, min(0.2, deadline - time.monotonic()))
            if remaining <= 0:
                try:
                    os.kill(pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
                break
            readable, _, _ = select.select([master_fd], [], [], remaining)
            if master_fd in readable:
                try:
                    chunk = os.read(master_fd, 4096)
                except OSError:
                    chunk = b""
                if chunk:
                    output.extend(chunk)
            waited_pid, status = os.waitpid(pid, os.WNOHANG)
            if waited_pid == pid:
                exit_code = os.waitstatus_to_exitcode(status)
                break
    finally:
        os.close(master_fd)
        try:
            waited_pid, status = os.waitpid(pid, os.WNOHANG)
            if waited_pid == pid:
                exit_code = os.waitstatus_to_exitcode(status)
        except ChildProcessError:
            pass

    stdout = output.decode("utf-8", errors="replace")
    out_path.write_text(stdout, encoding="utf-8")
    err_path.write_text("", encoding="utf-8")
    issues: list[str] = []
    if "node_tui_entry=solonclaw" not in stdout:
        issues.append("missing_node_tui_entry_guidance")
    secret_leak = any(pattern in stdout for pattern in SECRET_PATTERNS)
    suspect = exit_code != 0 or bool(issues) or secret_leak
    result = {
        "command": "--tui guidance",
        "exit_code": exit_code,
        "timeout": exit_code == 124,
        "suspect": suspect,
        "secret_leak": secret_leak,
        "out_path": out_path,
        "err_path": err_path,
        "issues": issues,
    }
    status = "SUSPECT" if suspect else "ok"
    print(f"tui {status} --tui guidance", flush=True)
    if suspect:
        findings.append(result)
        print(f"  exit={exit_code} timeout={exit_code == 124} issues={','.join(issues)}", flush=True)
        print_excerpt(out_path, "OUT")
    return 1


def wait_child_exit(pid: int, timeout_seconds: float) -> int | None:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        try:
            waited_pid, status = os.waitpid(pid, os.WNOHANG)
        except ChildProcessError:
            return 0
        if waited_pid == pid:
            return os.waitstatus_to_exitcode(status)
        time.sleep(0.05)
    return None


def wait_child_exit_reading(pid: int, master_fd: int, output: bytearray, timeout_seconds: float) -> int | None:
    """等待子进程退出时继续读取 PTY，保留退出命令后的最后输出。"""
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        try:
            waited_pid, status = os.waitpid(pid, os.WNOHANG)
        except ChildProcessError:
            return 0
        if waited_pid == pid:
            read_pty(master_fd, output, 0.1)
            return os.waitstatus_to_exitcode(status)
        read_pty(master_fd, output, 0.1)
    return None


def normalize_node_tui_exit_code(exit_code: int | None) -> int:
    """把未退出的 Node TUI 子进程标记为超时，同时保留正常的 0 退出码。"""
    return exit_code if exit_code is not None else 124


def terminate_child(pid: int) -> None:
    try:
        os.kill(pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    exit_code = wait_child_exit(pid, 3.0)
    if exit_code is not None:
        return
    try:
        os.kill(pid, signal.SIGKILL)
    except ProcessLookupError:
        return
    wait_child_exit(pid, 1.0)


def run_node_tui_sequence(
    actions: list[dict[str, object]],
    env: dict[str, str],
    out_path: Path,
    timeout_seconds: float,
    require_exit: bool,
) -> dict[str, object]:
    pid, master_fd = pty.fork()
    if pid == 0:
        os.chdir(str(REPO_ROOT))
        os.execvpe("./bin/solonclaw", ["./bin/solonclaw"], env)

    output = bytearray()
    response_state = TerminalResponseState()
    step_issues: list[str] = []
    deadline = time.monotonic() + timeout_seconds
    exit_code = 124
    saw_exit_action = False
    try:
        fcntl.ioctl(master_fd, termios.TIOCSWINSZ, struct.pack("HHHH", 36, 132, 0, 0))
        if not wait_for_node_tui_banner(master_fd, output, min(12.0, timeout_seconds), response_state):
            step_issues.append("startup_missing_banner")
        if not step_issues and not wait_for_node_tui_ready_state(
            master_fd, output, min(20.0, timeout_seconds), response_state
        ):
            step_issues.append("startup_missing_ready")
        for action in actions:
            if step_issues:
                break
            if time.monotonic() >= deadline:
                step_issues.append("deadline_before_action")
                break
            command = str(action.get("value", ""))
            before_len = len(output)
            write_command_like_user(master_fd, output, command, response_state)
            if action.get("exit"):
                keys = str(action.get("keys", ""))
                if keys:
                    time.sleep(0.2)
                    try:
                        os.write(master_fd, keys.encode("utf-8"))
                    except OSError:
                        pass
                saw_exit_action = True
                exit_code = normalize_node_tui_exit_code(
                    wait_child_exit_reading(
                        pid,
                        master_fd,
                        output,
                        min(8.0, max(0.1, deadline - time.monotonic())),
                    )
                )
                if exit_code == 124:
                    step_issues.append(f"exit_not_completed:{command}")
                break
            expected = str(action.get("expect", "")).strip()
            if expected and not wait_for_new_text(
                master_fd,
                output,
                expected,
                before_len,
                min(15.0, max(0.1, deadline - time.monotonic())),
                response_state,
            ):
                step_issues.append(f"step_missing_expected:{command}:{expected}")
                break
            keys = str(action.get("keys", ""))
            if keys:
                before_keys_len = len(output)
                os.write(master_fd, keys.encode("utf-8"))
                post_expected = str(action.get("post_expect", "")).strip()
                if post_expected and not wait_for_new_text(
                    master_fd,
                    output,
                    post_expected,
                    before_keys_len,
                    min(15.0, max(0.1, deadline - time.monotonic())),
                    response_state,
                ):
                    step_issues.append(f"step_missing_post_expected:{command}:{post_expected}")
                    break
            after = str(action.get("after", ""))
            if after:
                close_start = len(output)
                os.write(master_fd, after.encode("utf-8"))
                close_expected = str(action.get("close_expect", "")).strip()
                if close_expected:
                    if not wait_for_new_text(
                        master_fd,
                        output,
                        close_expected,
                        close_start,
                        min(5.0, max(0.1, deadline - time.monotonic())),
                        response_state,
                    ):
                        step_issues.append(f"step_missing_close_expected:{command}:{close_expected}")
                        break
                else:
                    read_pty(master_fd, output, float(action.get("after_wait", 0.5)), response_state)
            # 读取一小段尾部输出，避免下一步命令紧贴上一步 repaint。
            read_pty(master_fd, output, 0.8, response_state)
            if len(output) == before_len:
                step_issues.append(f"step_no_output:{command}")
                break
        if not saw_exit_action:
            exit_code = 0 if not require_exit else 124
            if require_exit and not step_issues:
                step_issues.append("missing_exit_action")
    finally:
        try:
            os.close(master_fd)
        except OSError:
            pass
        if wait_child_exit(pid, 0.0) is None:
            terminate_child(pid)

    stdout = output.decode("utf-8", errors="replace")
    out_path.write_text(stdout, encoding="utf-8")
    return {
        "exit_code": exit_code,
        "issues": step_issues,
        "stdout": stdout,
    }


def run_node_tui_pty(
    jar: Path,
    workspace_home: Path,
    port: int,
    timeout_seconds: float,
    findings: list[dict[str, object]],
    explicit_commands: list[str] | None = None,
) -> int:
    out_path = workspace_home / "audit-node-tui-pty.out"
    err_path = workspace_home / "audit-node-tui-pty.err"
    server_out = workspace_home / "audit-node-tui-server.out"
    server_err = workspace_home / "audit-node-tui-server.err"
    bootstrap = run_command(
        jar,
        workspace_home,
        f"model set --provider audit-openai --base-url {AUDIT_MODEL_BASE_URL} "
        "--api-key Sk-Audit-Node-Tui-Secret123 --model audit-model --dialect openai",
        0,
        timeout_seconds,
    )
    if bootstrap["suspect"] or bootstrap["secret_leak"]:
        bootstrap["command"] = "node-tui bootstrap model set"
        findings.append(bootstrap)
        print("node-tui SUSPECT bootstrap model set", flush=True)
        print_excerpt(bootstrap["out_path"], "OUT")
        print_excerpt(bootstrap["err_path"], "ERR")
        return 1
    server_env = dict(os.environ)
    server_env.setdefault("LC_ALL", "C")
    server_env.setdefault("LANG", "C")
    server_cmd = [
        "java",
        f"-Dsolonclaw.workspace={workspace_home}",
        f"-Dserver.port={port}",
        "-jar",
        str(jar),
        "server",
    ]
    with server_out.open("w", encoding="utf-8") as stdout, server_err.open("w", encoding="utf-8") as stderr:
        server = subprocess.Popen(
            server_cmd,
            cwd=str(REPO_ROOT),
            env=server_env,
            stdout=stdout,
            stderr=stderr,
            text=True,
        )
    handshake_ok, handshake_text = wait_for_http(
        f"http://127.0.0.1:{port}/api/tui/handshake", timeout_seconds)
    if not handshake_ok:
        try:
            server.terminate()
            server.wait(timeout=5)
        except Exception:
            server.kill()
        result = {
            "command": "solonclaw server + solonclaw PTY",
            "exit_code": 124,
            "timeout": True,
            "suspect": True,
            "secret_leak": False,
            "out_path": out_path,
            "err_path": err_path,
            "issues": [f"handshake_failed:{handshake_text}"],
        }
        out_path.write_text("", encoding="utf-8")
        err_path.write_text(handshake_text, encoding="utf-8")
        findings.append(result)
        print("node-tui SUSPECT solonclaw server + solonclaw PTY", flush=True)
        print(f"  issues={','.join(result['issues'])}", flush=True)
        return 1

    env = build_node_tui_env(workspace_home, port)
    try:
        main_actions = build_node_tui_actions(explicit_commands or []) or NODE_TUI_ACTIONS
        main_run = run_node_tui_sequence(
                main_actions, env, out_path, timeout_seconds, require_exit=True)
        if explicit_commands:
            new_actions: list[dict[str, object]] = []
            new_run = {"exit_code": 0, "issues": [], "stdout": ""}
        else:
            new_actions = NODE_TUI_NEW_SESSION_ACTIONS
            new_run = run_node_tui_sequence(
                    new_actions,
                    env,
                    workspace_home / "audit-node-tui-new-session.out",
                    min(30.0, timeout_seconds),
                    require_exit=False)
    finally:
        try:
            server.terminate()
            server.wait(timeout=5)
        except Exception:
            server.kill()

    stdout = str(main_run["stdout"]) + "\n" + str(new_run["stdout"])
    err_path.write_text(handshake_text, encoding="utf-8")
    exit_code = int(main_run["exit_code"])
    issues = audit_node_tui_transcript(str(main_run["stdout"]), main_actions, exit_code)
    issues.extend(main_run["issues"])
    new_exit_code = int(new_run["exit_code"])
    issues.extend(audit_node_tui_transcript(str(new_run["stdout"]), new_actions, new_exit_code))
    issues.extend(new_run["issues"])
    secret_leak = any(pattern in stdout for pattern in SECRET_PATTERNS)
    suspect = bool(issues) or secret_leak
    result = {
        "command": "solonclaw server + solonclaw PTY",
        "exit_code": exit_code,
        "timeout": exit_code == 124,
        "suspect": suspect,
        "secret_leak": secret_leak,
        "out_path": out_path,
        "err_path": err_path,
        "issues": issues,
    }
    status = "SUSPECT" if suspect else "ok"
    print(f"node-tui {status} solonclaw server + solonclaw PTY", flush=True)
    if suspect:
        findings.append(result)
        print(f"  exit={exit_code} timeout={exit_code == 124} issues={','.join(issues)}", flush=True)
        print_excerpt(out_path, "OUT")
    return 1


def run_cron_lifecycle(
    jar: Path,
    workspace_home: Path,
    start_index: int,
    timeout_seconds: float,
    findings: list[dict[str, object]],
) -> int:
    index = start_index
    create_command = '/cron add "every 2h" "检查状态" --name audit-lifecycle --script "echo ok" --no-agent'
    result = run_command(jar, workspace_home, create_command, index, timeout_seconds)
    report_result(index, result, findings)
    if result["suspect"] or result["secret_leak"]:
        return 1
    job_id = extract_cron_job_id(result["out_path"].read_text(encoding="utf-8", errors="replace"))
    if not job_id:
        findings.append(result)
        print("  OUT: 未能从创建输出中解析 cron job id", flush=True)
        return 1
    count = 1
    for command in [
        f"/cron inspect {job_id}",
        f"/cron pause {job_id} --reason audit",
        f"/cron resume {job_id}",
        f"/cron history {job_id}",
        f"/cron remove {job_id}",
    ]:
        index += 1
        count += 1
        report_result(index, run_command(jar, workspace_home, command, index, timeout_seconds), findings)
    return count


def main() -> int:
    args = parse_args()
    jar = Path(args.jar).resolve()
    if not jar.exists():
        print(f"jar not found: {jar}", file=sys.stderr)
        return 2

    created_workspace = False
    if args.workspace_home:
        workspace_home = Path(args.workspace_home).resolve()
        workspace_home.mkdir(parents=True, exist_ok=True)
    else:
        workspace_home = Path(tempfile.mkdtemp(prefix="solonclaw-command-audit."))
        created_workspace = True

    commands = build_command_list(args.no_defaults, args.include_write_commands, args.command)

    print(f"audit.workspace={workspace_home}", flush=True)
    print(f"audit.jar={jar}", flush=True)
    findings = []
    for index, command in enumerate(commands, start=1):
        result = run_command(jar, workspace_home, command, index, args.timeout_seconds)
        report_result(index, result, findings)
    lifecycle_count = 0
    if args.cron_lifecycle:
        lifecycle_count = run_cron_lifecycle(
            jar, workspace_home, len(commands) + 1, args.timeout_seconds, findings)
    tui_count = 0
    if args.tui_pty:
        tui_commands = list(TUI_COMMANDS)
        tui_commands.extend(args.tui_command)
        tui_count = run_tui_pty(jar, workspace_home, tui_commands, args.timeout_seconds, findings)
    node_tui_count = 0
    if args.node_tui_pty:
        node_tui_count = run_node_tui_pty(
            jar,
            workspace_home,
            args.node_tui_port,
            args.timeout_seconds,
            findings,
            args.node_tui_command,
        )

    print(f"audit.total={len(commands) + lifecycle_count + tui_count + node_tui_count}", flush=True)
    print(f"audit.findings={len(findings)}", flush=True)
    if created_workspace and not args.keep_workspace and not findings:
        shutil.rmtree(workspace_home)
    else:
        print(f"audit.outputs={workspace_home}", flush=True)
    return 1 if findings else 0


if __name__ == "__main__":
    raise SystemExit(main())
