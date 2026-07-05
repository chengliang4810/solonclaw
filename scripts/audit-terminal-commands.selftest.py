#!/usr/bin/env python3
"""audit-terminal-commands.py 的轻量自检。"""

from __future__ import annotations

import importlib.util
import contextlib
import io
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("audit-terminal-commands.py")


def load_module():
    spec = importlib.util.spec_from_file_location("audit_terminal_commands", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


class AuditTerminalCommandsSelfTest(unittest.TestCase):
    def test_top_level_command_uses_shell_quoting(self) -> None:
        mod = load_module()

        args = mod.command_to_java_args(
            'model set --provider local --model "hello world" --api-key "Sk Test"'
        )

        self.assertEqual(
            args,
            [
                "model",
                "set",
                "--provider",
                "local",
                "--model",
                "hello world",
                "--api-key",
                "Sk Test",
            ],
        )

    def test_slash_command_preserves_full_prompt(self) -> None:
        mod = load_module()

        args = mod.command_to_java_args('/cron add "every 2h" "检查 状态"')

        self.assertEqual(args, ["--cli", "-p", '/cron add "every 2h" "检查 状态"'])

    def test_extracts_cron_job_id_from_terminal_output(self) -> None:
        mod = load_module()

        job_id = mod.extract_cron_job_id(
            "\u001b[36mAssistant\u001b[0m\n已创建定时任务：31da8f5716124a88a51fe221695732af\n"
        )

        self.assertEqual(job_id, "31da8f5716124a88a51fe221695732af")

    def test_extracts_cron_job_id_from_ascii_field(self) -> None:
        mod = load_module()

        job_id = mod.extract_cron_job_id(
            "????????31da8f5716124a88a51fe221695732af\njob_id=31da8f5716124a88a51fe221695732af\n"
        )

        self.assertEqual(job_id, "31da8f5716124a88a51fe221695732af")

    def test_can_build_explicit_command_list_without_defaults(self) -> None:
        mod = load_module()

        commands = mod.build_command_list(
            no_defaults=True,
            include_write_commands=False,
            explicit_commands=["/cron list"],
        )

        self.assertEqual(commands, ["/cron list"])

    def test_process_output_text_normalizes_timeout_bytes(self) -> None:
        mod = load_module()

        self.assertEqual(mod.process_output_text(b"\xe4\xbd\xa0\xe5\xa5\xbd"), "你好")
        self.assertEqual(mod.process_output_text("hello"), "hello")
        self.assertEqual(mod.process_output_text(None), "")

    def test_run_command_uses_utf8_replacement_decoding(self) -> None:
        mod = load_module()
        calls: list[dict[str, object]] = []

        def fake_run(*args: object, **kwargs: object) -> object:
            calls.append(kwargs)
            return mod.subprocess.CompletedProcess(
                args[0] if args else [],
                0,
                stdout="你好",
                stderr="",
            )

        original_run = mod.subprocess.run
        try:
            mod.subprocess.run = fake_run
            with tempfile.TemporaryDirectory() as tmp:
                result = mod.run_command(Path("missing.jar"), Path(tmp), "/help", 1, 1)
        finally:
            mod.subprocess.run = original_run

        self.assertEqual(result["exit_code"], 0)
        self.assertEqual(calls[0]["encoding"], "utf-8")
        self.assertEqual(calls[0]["errors"], "replace")

    def test_configure_stdio_uses_utf8_replacement_errors(self) -> None:
        mod = load_module()

        class FakeStream:
            def __init__(self) -> None:
                self.calls: list[dict[str, str]] = []

            def reconfigure(self, **kwargs: str) -> None:
                self.calls.append(kwargs)

        fake_stdout = FakeStream()
        fake_stderr = FakeStream()
        original_stdout = mod.sys.stdout
        original_stderr = mod.sys.stderr
        try:
            mod.sys.stdout = fake_stdout
            mod.sys.stderr = fake_stderr
            mod.configure_stdio()
        finally:
            mod.sys.stdout = original_stdout
            mod.sys.stderr = original_stderr

        self.assertEqual(fake_stdout.calls, [{"encoding": "utf-8", "errors": "replace"}])
        self.assertEqual(fake_stderr.calls, [{"encoding": "utf-8", "errors": "replace"}])

    def test_expected_empty_state_exit_is_not_suspect(self) -> None:
        mod = load_module()

        self.assertFalse(
            mod.is_suspect_command_result(
                "/retry",
                1,
                False,
                "运行失败：没有可重试的上一条用户消息。",
            )
        )
        self.assertFalse(
            mod.is_suspect_command_result(
                "/sethome",
                1,
                False,
                "运行失败：只有平台管理员可以执行 /sethome。",
            )
        )

    def test_unexpected_nonzero_exit_remains_suspect(self) -> None:
        mod = load_module()

        self.assertTrue(mod.is_suspect_command_result("/retry", 1, False, "模型服务异常"))

    def test_tui_script_enters_commands_like_a_user(self) -> None:
        mod = load_module()

        script = mod.build_tui_script(["/setup", "/new"])

        self.assertEqual(script, "/setup\r/new\r/exit\r")

    def test_tui_transcript_requires_visible_slash_commands(self) -> None:
        mod = load_module()

        issues = mod.audit_tui_transcript(
            "Solon Claw TUI\r\n你 > setup\r\n你 > new\r\n终端会话已结束。", ["/setup", "/new"]
        )

        self.assertIn("missing_prompt_echo:/setup", issues)
        self.assertIn("missing_prompt_echo:/new", issues)

    def test_tui_transcript_accepts_real_user_echo_and_filters_ansi(self) -> None:
        mod = load_module()

        issues = mod.audit_tui_transcript(
            "\u001b[1mSolon Claw TUI\u001b[0m\r\n你 > /setup\r\n你 > /new\r\n终端会话已结束。",
            ["/setup", "/new"],
        )

        self.assertEqual(issues, [])

    def test_tui_transcript_ignores_bulk_stdin_echo_before_prompt(self) -> None:
        mod = load_module()

        issues = mod.audit_tui_transcript(
            "/setup\r\n/new\r\nSolon Claw TUI\r\n你 > setup\r\n你 > new\r\n终端会话已结束。",
            ["/setup", "/new"],
        )

        self.assertIn("missing_prompt_echo:/setup", issues)
        self.assertIn("missing_prompt_echo:/new", issues)

    def test_keypress_schedule_types_each_character_and_after_key(self) -> None:
        mod = load_module()

        schedule = mod.build_keypress_schedule(
            [{"type": "command", "value": "/setup", "after": "q"}], start_delay=1.0
        )

        self.assertEqual("".join(ch for _, ch in schedule), "/setup\rq")

    def test_direct_pty_command_entry_uses_bracketed_paste(self) -> None:
        script = SCRIPT.read_text(encoding="utf-8")

        self.assertIn('os.write(master_fd, b"\\x1b[200~")', script)
        self.assertIn('os.write(master_fd, b"\\x1b[201~")', script)

    def test_node_tui_transcript_requires_expected_overlay_text(self) -> None:
        mod = load_module()

        issues = mod.audit_node_tui_transcript(
            "ready\n/setup\n/config path\nworkspace/config.yml\n",
            [
                {"type": "command", "value": "/setup", "expect": "Select provider"},
                {"type": "command", "value": "/config path", "expect": "workspace/config.yml"},
            ],
            0,
        )

        self.assertIn("missing_expected_text:Select provider", issues)

    def test_node_tui_transcript_accepts_expected_output_when_command_echo_is_repainted(self) -> None:
        mod = load_module()

        issues = mod.audit_node_tui_transcript(
            "r\ne\na\ns\no\nn\ni\nn\ng\n· reasoning: medium · display show\n",
            [{"type": "command", "value": "/reasoning", "expect": "reasoning:"}],
            0,
        )

        self.assertEqual(issues, [])

    def test_node_tui_transcript_fails_on_timeout_exit(self) -> None:
        mod = load_module()

        issues = mod.audit_node_tui_transcript(
            "ready\n/setup\nSelect provider\n",
            [{"type": "command", "value": "/setup", "expect": "Select provider"}],
            124,
        )

        self.assertIn("exit_code:124", issues)

    def test_node_tui_transcript_tolerates_ink_frame_spacing(self) -> None:
        mod = load_module()

        issues = mod.audit_node_tui_transcript(
            "·/setupgateway\n║Channel setup║\n",
            [{"type": "command", "value": "/setup gateway", "expect": "Channel setup"}],
            0,
        )

        self.assertEqual(issues, [])

    def test_node_tui_model_refresh_uses_stable_pager_title(self) -> None:
        mod = load_module()

        action = next(
            item for item in mod.NODE_TUI_ACTIONS if item.get("value") == "/model --refresh"
        )
        issues = mod.audit_node_tui_transcript(
            "· /model --refresh\n║ Models ║\ncurrent: audit-model\nprovi\x1b[1Cers:\n",
            [action],
            0,
        )

        self.assertEqual(action.get("expect"), "Models")
        self.assertEqual(issues, [])

    def test_node_tui_indicator_set_expectation_matches_set_output(self) -> None:
        mod = load_module()

        self.assertEqual(mod.node_tui_command_expectation("/indicator"), "indicator:")
        self.assertEqual(mod.node_tui_command_expectation("/indicator emoji"), "indicator")

    def test_node_tui_extra_default_actions_cover_common_user_paths(self) -> None:
        mod = load_module()

        values = {str(action.get("value")) for action in mod.NODE_TUI_ACTIONS}

        for command in [
            "/sessions",
            "/branch audit-branch",
            "/personality default",
            "/status",
            "/usage",
            "/title",
            "/fast status",
            "/busy status",
            "/reasoning",
            "/yolo",
            "/history",
            "/background",
            "/compress",
            "/density on",
            "/details tools expanded",
            "/fortune daily",
            "/image",
            "/indicator",
            "/logs",
            "/mem",
            "/mouse off",
            "/paste hello",
            "/queue",
            "/redraw",
            "/reload",
            "/retry",
            "/save",
            "/statusbar top",
            "/steer",
            "/stop",
            "/terminal-setup",
            "/undo",
            "/verbose",
            "/tasks",
            "/tasks status",
            "/replay list",
            "/voice status",
            "/voice on",
            "/voice off",
            "/voice tts",
            "/browser status",
            "/replay-diff",
            "/approve status",
            "/deny status",
            "/confirm",
            "/security",
            "/security status",
            "/security policy",
            "/security urls",
            "/security approvals",
            "/security slash-confirm",
            "/clear",
            "/quit",
        ]:
            self.assertIn(command, values)

    def test_node_tui_actions_can_expand_confirm_key_aliases(self) -> None:
        mod = load_module()

        schedule = mod.build_keypress_schedule(
            [{"type": "confirm", "value": "/clear", "expect": "Start a new session?", "keys": "esc"}],
            start_delay=0.0,
        )

        self.assertEqual("".join(ch for _, ch in schedule), "/clear\r\x1b")

    def test_node_tui_expectations_cover_new_safe_commands(self) -> None:
        mod = load_module()

        expectations = {
            "/auth": "认证配置",
            "/auth list": "认证状态",
            "/proxy": "本地代理",
            "/gateway status": "Gateway Status",
            "/config": "Config",
            "/branch audit-branch": "branched",
            "/personality default": "personality:",
            "/replay-diff": "usage: /replay-diff",
            "/copy": "nothing to copy",
            "/paste hello": ": /paste",
            "/compress": "ready",
            "/fortune daily": "🔮",
            "/retry": "toretry",
            "/quit": "",
        }

        for command, expected in expectations.items():
            with self.subTest(command=command):
                self.assertEqual(mod.node_tui_command_expectation(command), expected)

    def test_node_tui_commands_with_confirm_overlay_open_panels(self) -> None:
        mod = load_module()

        self.assertTrue(mod.node_tui_command_opens_panel("/rollback fake path"))
        self.assertFalse(mod.node_tui_command_opens_panel("/clear"))

    def test_node_tui_startup_accepts_setup_required_screen(self) -> None:
        mod = load_module()

        self.assertTrue(mod.contains_node_tui_ready_state("需要先完成设置\nsetup required"))
        self.assertTrue(mod.contains_node_tui_ready_state("ready"))
        self.assertFalse(mod.contains_node_tui_ready_state("正在启动 SolonClaw…"))

    def test_node_tui_banner_detection_is_case_insensitive(self) -> None:
        mod = load_module()

        self.assertTrue(mod.contains_node_tui_banner("⚕ SolonClaw Agent"))
        self.assertTrue(mod.contains_node_tui_banner("\x1b[1mSOLONCLAW\x1b[0m"))
        self.assertFalse(mod.contains_node_tui_banner("forging session…"))

    def test_node_tui_audit_model_url_stays_loopback(self) -> None:
        mod = load_module()

        self.assertTrue(mod.AUDIT_MODEL_BASE_URL.startswith("https://"))
        self.assertIn(".invalid/", mod.AUDIT_MODEL_BASE_URL)

    def test_wait_for_new_text_ignores_historical_output(self) -> None:
        mod = load_module()

        output = bytearray("ready\nSelect provider\n".encode("utf-8"))

        self.assertFalse(mod.contains_new_terminal_text(output, "ready", len(output)))
        self.assertFalse(mod.contains_new_terminal_text(output, "Select provider", len(output)))

    def test_wait_child_exit_reading_is_available_for_exit_diagnostics(self) -> None:
        mod = load_module()

        self.assertTrue(callable(mod.wait_child_exit_reading))

    def test_node_tui_exit_code_preserves_clean_zero_exit(self) -> None:
        mod = load_module()

        self.assertEqual(mod.normalize_node_tui_exit_code(0), 0)
        self.assertEqual(mod.normalize_node_tui_exit_code(None), 124)

    def test_terminal_query_responses_are_generated_once(self) -> None:
        mod = load_module()

        output = bytearray(b"\x1b[>0q\x1b[c\x1b[?u")
        state = mod.TerminalResponseState()

        self.assertEqual(
            mod.terminal_query_responses(output, state),
            b"\x1bP>|xterm.js(5.5.0)\x1b\\\x1b[?1;2c\x1b[?0u",
        )
        self.assertEqual(mod.terminal_query_responses(output, state), b"")

    def test_build_node_tui_actions_marks_exit_command(self) -> None:
        mod = load_module()

        actions = mod.build_node_tui_actions(["/doctor", "/exit"])

        self.assertEqual(
            actions[0],
            {
                "type": "command",
                "value": "/doctor",
                "expect": "model.provider",
                "after": "q",
                "close_expect": "ready",
            },
        )
        self.assertEqual(actions[1], {"type": "command", "value": "/exit", "exit": True, "keys": "\r"})

    def test_build_node_tui_actions_reuses_known_overlay_close_steps(self) -> None:
        mod = load_module()

        actions = mod.build_node_tui_actions(["/setup", "/setup model", "/setup gateway", "/model --refresh"])

        self.assertEqual(
            actions,
            [
                {
                    "type": "command",
                    "value": "/setup",
                    "expect": "模型、渠道与工作区检查",
                    "after": "q",
                    "close_expect": "ready",
                },
                {
                    "type": "command",
                    "value": "/setup model",
                    "expect": "Select provider",
                    "after": "q",
                    "close_expect": "ready",
                },
                {
                    "type": "command",
                    "value": "/setup gateway",
                    "expect": "Channel setup",
                    "after": "q",
                    "close_expect": "ready",
                },
                {
                    "type": "command",
                    "value": "/model --refresh",
                    "expect": "Models",
                    "after": "q",
                    "close_expect": "ready",
                },
            ],
        )

    def test_build_node_tui_actions_adds_expectation_for_explicit_setup_panels(self) -> None:
        mod = load_module()

        actions = mod.build_node_tui_actions([
            f"/model set --provider audit-openai --base-url {mod.AUDIT_MODEL_BASE_URL} "
            "--api-key Sk-Audit-Node-Tui-Secret123 --model audit-model --dialect openai",
            "/status",
        ])

        self.assertEqual(actions[0]["expect"], "模型配置已写入")
        self.assertTrue(actions[0]["after"])
        self.assertEqual(actions[0]["close_expect"], "ready")
        self.assertEqual(actions[1]["expect"], "model=")
        self.assertTrue(actions[1]["after"])
        self.assertEqual(actions[1]["close_expect"], "ready")

    def test_node_tui_model_switch_uses_stable_success_text(self) -> None:
        mod = load_module()

        actions = mod.build_node_tui_actions(["/model openai:mimo-v2.5-pro"])

        self.assertEqual(actions[0]["expect"], "model →")

    def test_build_node_tui_actions_distinguishes_panels_from_pagers(self) -> None:
        mod = load_module()

        actions = mod.build_node_tui_actions(["/help", "/mem", "/status", "/browser status"])

        self.assertEqual(actions[0]["expect"], "Hotkeys")
        self.assertEqual(actions[0]["after"], "\x1b")
        self.assertGreaterEqual(float(actions[0]["after_wait"]), 1.0)
        self.assertNotIn("close_expect", actions[0])
        self.assertEqual(actions[1]["expect"], "Memory")
        self.assertEqual(actions[1]["after"], "\x1b")
        self.assertGreaterEqual(float(actions[1]["after_wait"]), 1.0)
        self.assertNotIn("close_expect", actions[1])
        self.assertEqual(actions[2]["expect"], "model=")
        self.assertTrue(actions[2]["after"])
        self.assertEqual(actions[2]["close_expect"], "ready")
        self.assertEqual(actions[3]["expect"], "browser not connected")

    def test_build_node_tui_actions_closes_approval_status_pages(self) -> None:
        mod = load_module()

        actions = mod.build_node_tui_actions([
            "/approve list",
            "/approve status",
            "/deny list",
            "/deny status",
        ])

        self.assertEqual(
            actions,
            [
                {
                    "type": "command",
                    "value": "/approve list",
                    "expect": "pending=none",
                    "after": "\x1b",
                    "after_wait": 1.2,
                    "close_expect": "ready",
                },
                {
                    "type": "command",
                    "value": "/approve status",
                    "expect": "pending=none",
                    "after": "\x1b",
                    "after_wait": 1.2,
                    "close_expect": "ready",
                },
                {
                    "type": "command",
                    "value": "/deny list",
                    "expect": "pending=none",
                    "after": "\x1b",
                    "after_wait": 1.2,
                    "close_expect": "ready",
                },
                {
                    "type": "command",
                    "value": "/deny status",
                    "expect": "pending=none",
                    "after": "\x1b",
                    "after_wait": 1.2,
                    "close_expect": "ready",
                },
            ],
        )

    def test_build_node_tui_actions_closes_security_pages(self) -> None:
        mod = load_module()

        actions = mod.build_node_tui_actions([
            "/security",
            "/security status",
            "/security policy",
            "/security audit",
            "/security urls",
            "/security approvals",
            "/security slash-confirm",
        ])

        self.assertEqual(
            actions,
            [
                {
                    "type": "command",
                    "value": "/security",
                    "expect": "安全审计摘要",
                    "after": "\x1b",
                    "after_wait": 1.2,
                    "close_expect": "ready",
                },
                {
                    "type": "command",
                    "value": "/security status",
                    "expect": "安全策略状态摘要",
                    "after": "\x1b",
                    "after_wait": 1.2,
                    "close_expect": "ready",
                },
                {
                    "type": "command",
                    "value": "/security policy",
                    "expect": "Tirith",
                    "after": "\x1b",
                    "after_wait": 1.2,
                    "close_expect": "ready",
                },
                {
                    "type": "command",
                    "value": "/security audit",
                    "expect": "安全审计摘要",
                    "after": "\x1b",
                    "after_wait": 1.2,
                    "close_expect": "ready",
                },
                {
                    "type": "command",
                    "value": "/security urls",
                    "expect": "URL：privateAllowed",
                    "after": "\x1b",
                    "after_wait": 1.2,
                    "close_expect": "ready",
                },
                {
                    "type": "command",
                    "value": "/security approvals",
                    "expect": "云存储规则",
                    "after": "\x1b",
                    "after_wait": 1.2,
                    "close_expect": "ready",
                },
                {
                    "type": "command",
                    "value": "/security slash-confirm",
                    "expect": "pendingQueue",
                    "after": "\x1b",
                    "after_wait": 1.2,
                    "close_expect": "ready",
                },
            ],
        )

    def test_build_node_tui_actions_covers_external_network_approval(self) -> None:
        mod = load_module()

        actions = mod.build_node_tui_actions([
            "/audit:direct-shell-external-network-allow-once",
        ])

        self.assertEqual(actions[0]["type"], "approval")
        self.assertIn("https://example.com", actions[0]["value"])
        self.assertIn("curl -fsS", actions[0]["value"])
        self.assertEqual(actions[0]["expect"], "approval required")
        self.assertEqual(actions[0]["keys"], "1")
        self.assertEqual(actions[0]["post_expect"], "Example Domain")

    def test_build_node_tui_actions_covers_chained_file_and_network_approval(self) -> None:
        mod = load_module()

        actions = mod.build_node_tui_actions([
            "/audit:direct-shell-dual-policy-allow-session-chain",
        ])

        self.assertEqual(len(actions), 1)
        self.assertEqual(actions[0]["type"], "approval")
        self.assertIn("curl -fsS https://example.com", actions[0]["value"])
        self.assertIn("/tmp/solonclaw-node-tui-dual-policy.txt", actions[0]["value"])
        self.assertEqual(actions[0]["expect"], "approval required")
        self.assertEqual(
            actions[0]["key_steps"],
            [
                {"keys": "2", "post_expect": "网络外部操作"},
                {"keys": "2", "post_expect": "exit 0"},
            ],
        )

    def test_default_node_tui_actions_cover_direct_shell_approval_success(self) -> None:
        mod = load_module()

        actions = [
            item
            for item in mod.NODE_TUI_ACTIONS
            if item.get("type") == "approval"
            and str(item.get("value", "")).startswith("!printf audit > /tmp/")
        ]

        self.assertEqual(len(actions), 1)
        self.assertEqual(actions[0]["expect"], "approval required")
        self.assertEqual(actions[0]["keys"], "1")
        self.assertEqual(actions[0]["post_expect"], "ready")

    def test_build_node_tui_actions_waits_after_plain_history_panels(self) -> None:
        mod = load_module()

        actions = mod.build_node_tui_actions(["/help", "/mem"])

        self.assertGreater(float(actions[0]["wait"]), 0)
        self.assertGreater(float(actions[1]["wait"]), 0)

    def test_build_node_tui_actions_checks_statusbar_and_verbose_output(self) -> None:
        mod = load_module()

        actions = mod.build_node_tui_actions(["/fortune", "/fortune daily", "/statusbar", "/verbose"])

        self.assertNotIn("expect", actions[0])
        self.assertEqual(actions[1]["expect"], "🔮")
        self.assertEqual(actions[2]["expect"], "status bar")
        self.assertEqual(actions[3]["expect"], "verbose:")

    def test_build_node_tui_actions_closes_terminal_setup_and_tools_panels(self) -> None:
        mod = load_module()

        actions = mod.build_node_tui_actions(["/terminal-setup", "/reload-skills", "/tools", "/tools disable web", "/tools enable web", "/model pick"])

        self.assertEqual(actions[0]["expect"], "Configure terminal keybindings?")
        self.assertEqual(actions[0]["after"], "\x1b")
        self.assertEqual(actions[0]["close_expect"], "ready")
        self.assertEqual(actions[1]["expect"], "Reload Skills")
        self.assertEqual(actions[1]["after"], "\x1b")
        self.assertEqual(actions[1]["close_expect"], "ready")
        self.assertEqual(actions[2]["expect"], "Tools")
        self.assertEqual(actions[2]["after"], "q")
        self.assertEqual(actions[2]["close_expect"], "ready")
        self.assertEqual(actions[3]["expect"], "disabled:")
        self.assertNotIn("after", actions[3])
        self.assertNotIn("close_expect", actions[3])
        self.assertEqual(actions[4]["expect"], "enabled:")
        self.assertNotIn("after", actions[4])
        self.assertNotIn("close_expect", actions[4])
        self.assertEqual(actions[5]["expect"], "Select provider")
        self.assertEqual(actions[5]["after"], "\x1b")
        self.assertEqual(actions[5]["close_expect"], "ready")

    def test_build_node_tui_actions_uses_reload_mcp_confirmation_text(self) -> None:
        mod = load_module()

        actions = mod.build_node_tui_actions(["/reload-mcp"])

        self.assertEqual(actions[0]["expect"], "确认本次执行")

    def test_build_node_tui_actions_uses_repaint_stable_details_section_suffix(self) -> None:
        mod = load_module()

        actions = mod.build_node_tui_actions(["/details tools expanded"])

        self.assertEqual(actions[0]["expect"], ": expanded")

    def test_build_node_tui_actions_checks_tasks_status_signal(self) -> None:
        mod = load_module()

        actions = mod.build_node_tui_actions(["/tasks status"])

        self.assertEqual(actions[0]["expect"], "delegation")

    def test_build_node_tui_actions_checks_save_and_undo_empty_states(self) -> None:
        mod = load_module()

        actions = mod.build_node_tui_actions(["/save", "/undo"])

        self.assertEqual(actions[0]["expect"], "no conversation yet")
        self.assertEqual(actions[1]["expect"], "nothing to undo")

    def test_build_node_tui_actions_checks_redraw_compress_and_steer_help(self) -> None:
        mod = load_module()

        actions = mod.build_node_tui_actions(["/redraw", "/compress", "/steer"])

        self.assertEqual(actions[0]["expect"], "ready")
        self.assertEqual(actions[1]["expect"], "ready")
        self.assertEqual(actions[2]["expect"], "usage: /steer")

    def test_build_node_tui_actions_checks_background_image_and_paste_usage(self) -> None:
        mod = load_module()

        actions = mod.build_node_tui_actions(["/background", "/image", "/paste hello"])

        self.assertEqual(actions[0]["expect"], "/background")
        self.assertEqual(actions[1]["expect"], "/image")
        self.assertEqual(actions[2]["expect"], ": /paste")

    def test_build_node_tui_actions_expands_setup_panel_interaction_aliases(self) -> None:
        mod = load_module()

        actions = mod.build_node_tui_actions([
            "/setup:enter-model",
            "/setup:enter-channels",
            "/setup:enter-doctor",
        ])

        self.assertEqual(
            actions,
            [
                {
                    "type": "panel",
                    "value": "/setup",
                    "expect": "模型、渠道与工作区检查",
                    "keys": "\r",
                    "post_expect": "Select provider",
                    "after": "q",
                    "close_expect": "ready",
                },
                {
                    "type": "panel",
                    "value": "/setup",
                    "expect": "模型、渠道与工作区检查",
                    "keys": "\x1b[B\r",
                    "post_expect": "Channel setup",
                    "after": "q",
                    "close_expect": "ready",
                },
                {
                    "type": "panel",
                    "value": "/setup",
                    "expect": "模型、渠道与工作区检查",
                    "keys": "\x1b[B\x1b[B\r",
                    "post_expect": "model.provider",
                    "after": "q",
                    "close_expect": "ready",
                },
            ],
        )

    def test_node_tui_env_uses_workspace_home_for_frontend_state(self) -> None:
        mod = load_module()
        workspace_home = Path("/tmp/solonclaw-audit-home")

        env = mod.build_node_tui_env(workspace_home, 18123)

        self.assertEqual(env["SOLONCLAW_HOME"], str(workspace_home))
        self.assertEqual(env["SOLONCLAW_SERVER_URL"], "http://127.0.0.1:18123")

    def test_pty_support_detection_reports_missing_unix_modules(self) -> None:
        mod = load_module()
        original = (mod.fcntl, mod.pty, mod.select, mod.termios)
        try:
            mod.fcntl = None

            self.assertFalse(mod.pty_support_available())
        finally:
            mod.fcntl, mod.pty, mod.select, mod.termios = original

    def test_node_tui_pty_skips_bootstrap_when_pty_is_unavailable(self) -> None:
        mod = load_module()
        calls: list[str] = []

        def fail_run_command(*args: object, **kwargs: object) -> object:
            calls.append("run_command")
            raise AssertionError("run_command should not run without PTY support")

        def fail_popen(*args: object, **kwargs: object) -> object:
            calls.append("Popen")
            raise AssertionError("server should not start without PTY support")

        original_support = mod.pty_support_available
        original_run_command = mod.run_command
        original_popen = mod.subprocess.Popen
        try:
            mod.pty_support_available = lambda: False
            mod.run_command = fail_run_command
            mod.subprocess.Popen = fail_popen
            with tempfile.TemporaryDirectory() as tmp:
                findings: list[dict[str, object]] = []

                output = io.StringIO()
                with contextlib.redirect_stdout(output):
                    exit_code = mod.run_node_tui_pty(Path("missing.jar"), Path(tmp), 18123, 1, findings)

            self.assertEqual(exit_code, 1)
            self.assertEqual(calls, [])
            self.assertEqual(findings[0]["issues"], ["pty_not_supported_on_this_platform"])
            self.assertIn("node-tui SUSPECT solonclaw server + solonclaw PTY", output.getvalue())
            self.assertIn("issues=pty_not_supported_on_this_platform", output.getvalue())
        finally:
            mod.pty_support_available = original_support
            mod.run_command = original_run_command
            mod.subprocess.Popen = original_popen


if __name__ == "__main__":
    unittest.main()
