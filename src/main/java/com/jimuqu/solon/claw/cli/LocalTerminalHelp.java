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
                + "/goal [status|pause|resume|clear|目标 --max N] - 管理跨轮长目标循环\n"
                + "/recap [条数] - 显示恢复会话用的紧凑历史摘要\n"
                + "/trajectory [save] [原始问题] - 导出或保存会话轨迹 JSON\n"
                + "/compact [重点] - 压缩当前会话上下文，/compress 同义\n"
                + "/events - 查看最近一次运行的终端事件\n"
                + "/tasks - 查看当前和最近终端后台任务\n"
                + "/transcript [条数] - 查看当前终端会话的虚拟历史\n"
                + "/attachments <文本或路径> - 预检粘贴内容中会被识别的本地附件\n"
                + "/tips - 查看终端提示\n"
                + "/queue <提示> - 将新输入排到当前任务之后执行\n"
                + "/steer <提示> - 向运行中的任务注入修正或引导\n"
                + "/busy [status|queue|steer|interrupt|reject] - 查看或切换运行中输入策略\n"
                + "/cron guide|capabilities [--json] - 查看自动化字段、别名、技能绑定和投递策略\n"
                + "/kanban guide|drawer|pipeline|retry|history|dispatch - 查看或操作看板任务抽屉、流水和派发\n"
                + "/security audit|policy|approvals|urls - 查看安全审计、审批、URL 和工具参数策略摘要\n"
                + "/reload-mcp [now|always] - 重载 MCP 工具并刷新下一轮工具 schema\n"
                + "/approve [确认编号|all] [session|always] - 批准危险命令审批\n"
                + "/deny [确认编号] - 拒绝危险命令审批\n"
                + "/approve list|status|clear session|clear always|clear all - 查看或清理审批授权\n"
                + "/deny list|status|all - 查看或批量拒绝待审批命令\n"
                + "/confirm - 查看待确认 slash 命令\n"
                + "/yolo [status|on|off] - 查看或切换当前会话危险命令自动批准模式\n"
                + "/acp status - 查看 ACP 本地适配器能力快照\n"
                + "/skin [classic|mono|contrast] - 查看或切换 TUI 皮肤\n"
                + TerminalShortcuts.helpLine() + "\n"
                + "/exit 或 /quit - 退出当前终端会话；有后台任务时先显示退出保护\n"
                + "/exit! 或 /quit! - 停止运行中的后台任务并强制退出\n"
                + "粘贴本地文件路径 - 自动作为附件发送，凭据路径会被安全策略阻断\n"
                + "\n"
                + "对话命令仍可使用 /new、/retry、/undo、/branch、/resume、/title、/status、/model、/cron、/kanban、/security、/approve、/deny、/busy 等。";
    }
}
