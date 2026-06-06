package com.jimuqu.solon.claw.cli;

import com.jimuqu.solon.claw.command.CommandRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 承载终端命令Catalog相关状态和辅助逻辑。 */
public final class TerminalCommandCatalog {
    /** LEGACYSUBCOMMANDS的统一常量值。 */
    private static final String[] LEGACY_SUBCOMMANDS =
            new String[] {
                "/model pick",
                "/skin classic",
                "/skin mono",
                "/skin contrast",
                "/busy status",
                "/busy queue",
                "/busy steer",
                "/busy interrupt",
                "/busy reject",
                "/approve list",
                "/approve status",
                "/approve all",
                "/approve all session",
                "/approve all always",
                "/approve session",
                "/approve always",
                "/approve clear session",
                "/approve clear always",
                "/approve clear all",
                "/deny list",
                "/deny status",
                "/deny all",
                "/security audit",
                "/security status",
                "/security policy",
                "/security audit-tool",
                "/security approvals",
                "/security urls",
                "/security private-urls",
                "/security website",
                "/security slash-confirm",
                "/security approval-card",
                "/security approval-audit",
                "/security mcp-reload",
                "/security lifecycle",
                "/security hardline",
                "/security terminal-guardrails",
                "/security tirith",
                "/security tirith-approval",
                "/security cron-approvals",
                "/security subagent-approvals",
                "/security smart-approval",
                "/security paths",
                "/security credentials",
                "/security tool-args",
                "/security skill-credentials",
                "/security mcp",
                "/security mcp-oauth",
                "/security mcp-package",
                "/security schema",
                "/security attachments",
                "/security terminal-paste",
                "/security media-cache",
                "/security tool-results",
                "/security patch",
                "/security code-execution",
                "/security subprocess-env",
                "/security terminal-output",
                "/security sudo",
                "/security process",
                "/cron list",
                "/cron status",
                "/cron next",
                "/cron upcoming",
                "/cron guide",
                "/cron tutorial",
                "/cron capabilities",
                "/cron policy",
                "/cron add",
                "/cron edit",
                "/cron pause",
                "/cron disable",
                "/cron stop",
                "/cron resume",
                "/cron enable",
                "/cron start",
                "/cron remove",
                "/cron delete",
                "/cron run",
                "/cron trigger",
                "/cron retry",
                "/cron rerun",
                "/cron history",
                "/cron inspect",
                "/cron show",
                "/cron tick",
                "/reload-mcp now",
                "/reload-mcp always",
                "/models",
                "/model set",
                "/config env-path",
                "/config edit",
                "/config migrate",
                "/doctor",
                "/logout",
                "/postinstall",
                "/login",
                "/auth",
                "/fallback",
                "/fallback list",
                "/fallback add",
                "/fallback remove",
                "/fallback clear",
                "/secrets",
                "/proxy",
                "/mcp",
                "/mcp list",
                "/mcp add",
                "/mcp test",
                "/migrate",
                "/send",
                "/hooks",
                "/hooks list",
                "/dump",
                "/backup",
                "/checkpoints",
                "/checkpoints list",
                "/import",
                "/bundles",
                "/bundles list",
                "/memory",
                "/memory status",
                "/dashboard",
                "/dashboard start",
                "/logs",
                "/prompt-size",
                "/setup --quick",
                "/setup --reset",
                "/setup --non-interactive",
                "/gateway status",
                "/gateway list",
                "/gateway run",
                "/gateway start",
                "/gateway stop",
                "/gateway restart",
                "/gateway install",
                "/gateway uninstall",
                "/gateway migrate-legacy",
                "/setup model",
                "/setup terminal",
                "/setup tools",
                "/setup agent",
                "/setup tts",
                "/setup gateway",
                "/setup gateway feishu",
                "/setup gateway dingtalk",
                "/setup gateway wecom",
                "/setup gateway weixin",
                "/setup gateway qqbot",
                "/setup gateway yuanbao",
                "/doctor --fix",
                "/doctor --ack",
                "/pairing list",
                "/pairing pending",
                "/pairing approved",
                "/pairing approve",
                "/pairing revoke",
                "/pairing clear-pending",
                "/sessions",
                "/sessions stats",
                "/sessions export",
                "/sessions delete",
                "/sessions prune",
                "/sessions rename",
                "/session",
                "/history",
                "/events",
                "/attachments",
                "/transcript",
                "/tips",
                "/skin",
                "/exit",
                "/quit",
                "/exit!",
                "/quit!"
            };

    /** 斜杠命令COMMANDS的统一常量值。 */
    public static final String[] SLASH_COMMANDS = buildSlashCommands();

    /** 斜杠命令命令列表的统一常量值。 */
    private static final List<String> SLASH_COMMAND_LIST =
            Collections.unmodifiableList(asList(SLASH_COMMANDS));

    /** 创建终端命令Catalog实例。 */
    private TerminalCommandCatalog() {}

    /**
     * 执行斜杠命令Commands相关逻辑。
     *
     * @return 返回slash Commands结果。
     */
    public static List<String> slashCommands() {
        return SLASH_COMMAND_LIST;
    }

    /**
     * 构建Slash Commands。
     *
     * @return 返回创建好的Slash Commands。
     */
    private static String[] buildSlashCommands() {
        List<String> commands = new ArrayList<String>(CommandRegistry.slashCommands());
        for (String command : LEGACY_SUBCOMMANDS) {
            if (!commands.contains(command)) {
                commands.add(command);
            }
        }
        return commands.toArray(new String[commands.size()]);
    }

    /**
     * 将输入对象归一为列表视图。
     *
     * @param commands commands 参数。
     * @return 返回as List结果。
     */
    private static List<String> asList(String[] commands) {
        List<String> list = new ArrayList<String>();
        Collections.addAll(list, commands);
        return list;
    }
}
