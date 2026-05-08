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
                + "/models 或 /model pick - 列出可选模型；/model pick <编号> 切换\n"
                + "/sessions [关键词] - 浏览或搜索最近会话；/session pick <编号> 恢复\n"
                + "/history [条数] - 预览当前终端会话的最近历史\n"
                + "/title [clear|新标题] - 查看、设置或清空当前会话标题\n"
                + "/events - 查看最近一次运行的终端事件\n"
                + "/tasks - 查看当前和最近终端后台任务\n"
                + "/attachments <文本或路径> - 预检粘贴内容中会被识别的本地附件\n"
                + "/tips - 查看终端提示\n"
                + "/queue <提示> - 将新输入排到当前任务之后执行\n"
                + "/steer <提示> - 向运行中的任务注入修正或引导\n"
                + "/skin [classic|mono|contrast] - 查看或切换 TUI 皮肤\n"
                + TerminalShortcuts.helpLine() + "\n"
                + "/exit 或 /quit - 退出当前终端会话；有后台任务时先显示退出保护\n"
                + "/exit! 或 /quit! - 停止运行中的后台任务并强制退出\n"
                + "粘贴本地文件路径 - 自动作为附件发送，凭据路径会被安全策略阻断\n"
                + "\n"
                + "对话命令仍可使用 /new、/retry、/undo、/branch、/resume、/title、/status、/model、/cron、/kanban、/approve、/deny、/busy 等。";
    }
}
