package com.jimuqu.solon.claw.cli;

/** Local CLI/TUI commands handled before normal slash command dispatch. */
public final class LocalTerminalHelp {
    private LocalTerminalHelp() {}

    public static boolean isHelp(String input) {
        String value = input == null ? "" : input.trim();
        return "/help".equalsIgnoreCase(value) || "/?".equalsIgnoreCase(value);
    }

    public static String text() {
        return "本地终端命令：\n"
                + "/copy - 复制上一条回复到终端剪贴板\n"
                + "/exit 或 /quit - 退出当前终端会话\n"
                + "粘贴本地文件路径 - 自动作为附件发送，凭据路径会被安全策略阻断\n"
                + "\n"
                + "对话命令仍可使用 /new、/retry、/undo、/branch、/resume、/status、/model、/cron、/kanban、/approve、/deny、/busy 等。";
    }
}
