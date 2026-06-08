package com.jimuqu.solon.claw.cli;

/** 承载本地终端Help相关状态和辅助逻辑。 */
public final class LocalTerminalHelp {
    /** 创建本地终端Help实例。 */
    private LocalTerminalHelp() {}

    /**
     * 判断是否Help。
     *
     * @param input 输入参数。
     * @return 如果Help满足条件则返回 true，否则返回 false。
     */
    public static boolean isHelp(String input) {
        String value = input == null ? "" : input.trim();
        return "/help".equalsIgnoreCase(value) || "/?".equalsIgnoreCase(value);
    }

    /**
     * 执行文本相关逻辑。
     *
     * @return 返回text结果。
     */
    public static String text() {
        return "本地终端命令：\n"
                + "/copy - 复制上一条回复到终端剪贴板\n"
                + "/setup [model|gateway] - 查看初始化、模型和国内消息渠道配置引导\n"
                + "/setup --quick|--reset|--non-interactive - 快速检查、重置或输出脚本化初始化引导\n"
                + "/setup terminal|tools|agent|tts - 查看终端、工具、Agent 和语音服务初始化引导\n"
                + "/setup gateway <channel> --enabled true ... - 写入国内消息渠道初始化配置\n"
                + "/config path|env-path|show|edit|check|migrate|set <key> <value> - 查看或写入 runtime/config.yml\n"
                + "/doctor [--fix|--ack <id>] - 汇总模型、配置和国内消息渠道自检\n"
                + "/status - 查看本地运行、模型和渠道状态\n"
                + "/version - 查看当前版本和部署形态\n"
                + "/logout - 清理本地登录态\n"
                + "/gateway status|list|run|start|stop|restart - 查看或操作单实例消息网关入口\n"
                + "/gateway install|uninstall|migrate-legacy - 查看单实例部署下的服务安装裁剪说明\n"
                + "/pairing list|pending|approved|approve|revoke|clear-pending - 管理渠道 pairing 授权\n"
                + "/model set --provider <key> --base-url <url> --api-key <key> --model <model> --dialect <dialect> - 配置默认模型提供方\n"
                + "/fallback list|add|remove|clear - 管理主模型故障时的 fallback provider 链路\n"
                + "/mcp list|add|test - 查看 MCP 配置入口、Dashboard 引导和 reload-mcp 刷新方式\n"
                + "/postinstall、/login、/auth、/fallback、/secrets、/proxy、/mcp、/migrate、/send - 查看对应本地配置或裁剪说明\n"
                + "/hooks、/dump、/backup、/checkpoints、/import、/bundles、/memory、/dashboard、/logs、/prompt-size - 查看保留管理入口的本地说明\n"
                + "/models 或 /model pick - 列出可选模型；/model pick <编号> 切换\n"
                + "/sessions [关键词] - 浏览或搜索最近会话；/session pick <编号> 恢复\n"
                + "/sessions stats|export|delete|prune|rename - 统计、导出、删除、裁剪或重命名历史会话\n"
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
                + "/cron guide|capabilities|policy [--json] - 查看自动化字段、别名、技能绑定、执行和投递策略\n"
                + "/security audit|status|policy|audit-tool|approvals|slash-confirm|approval-card|approval-audit|mcp-reload|lifecycle|hardline|terminal-guardrails|tirith|tirith-approval|cron-approvals|subagent-approvals|smart-approval|urls|private-urls|website|paths|credentials|skill-credentials|tool-args|mcp|mcp-oauth|mcp-package|schema|attachments|terminal-paste|media-cache|tool-results|patch|code-execution|subprocess-env|terminal-output|sudo|process - 查看安全审计、策略状态、审批、URL、路径、凭据、MCP、执行和工具策略摘要\n"
                + "/insights - 查看使用洞察与运行摘要\n"
                + "/plugins - 查看插件加载状态\n"
                + "/update - 执行应用更新\n"
                + "/reload-skills - 重新扫描本地技能目录\n"
                + "/reload-mcp [now|always] - 重载 MCP 工具并刷新下一轮工具 schema\n"
                + "/approve [确认编号|all] [session|always] - 批准危险命令审批\n"
                + "/deny [确认编号] - 拒绝危险命令审批\n"
                + "/approve list|status|clear session|clear always|clear all - 查看或清理审批授权\n"
                + "/deny list|status|all - 查看或批量拒绝待审批命令\n"
                + "/confirm - 查看待确认 slash 命令\n"
                + "/yolo [status|on|off] - 查看或切换当前会话危险命令自动批准模式\n"
                + "/skin [classic|mono|contrast] - 查看或切换 TUI 皮肤\n"
                + TerminalShortcuts.helpLine()
                + "\n"
                + "/exit 或 /quit - 退出当前终端会话；有后台任务时先显示退出保护\n"
                + "/exit! 或 /quit! - 停止运行中的后台任务并强制退出\n"
                + "粘贴本地文件路径 - 自动作为附件发送，凭据路径会被安全策略阻断\n"
                + "\n"
                + "对话命令仍可使用 /new、/retry、/undo、/branch、/resume、/title、/status、/model、/cron、/security、/approve、/deny、/busy 等。";
    }
}
