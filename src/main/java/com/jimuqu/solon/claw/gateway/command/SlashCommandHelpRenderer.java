package com.jimuqu.solon.claw.gateway.command;

import com.jimuqu.solon.claw.command.CommandDescriptor;
import com.jimuqu.solon.claw.command.CommandRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 集中渲染消息网关 slash command 帮助文本，避免默认命令服务承担长文本维护职责。 */
final class SlashCommandHelpRenderer {
    /** 对需要展示参数格式的命令保留用法补充；命令存在性和描述仍以 CommandRegistry 为准。 */
    private static final Map<String, List<String>> USAGE_OVERRIDES = buildUsageOverrides();

    /** 阻止工具类被实例化。 */
    private SlashCommandHelpRenderer() {}

    /**
     * 渲染完整帮助文本。
     *
     * @return 每行一个命令说明的帮助文本。
     */
    static String render() {
        List<String> lines = new ArrayList<String>();
        appendRegistryHelpLines(lines);
        return String.join("\n", lines);
    }

    /**
     * 追加网关已注册命令的帮助行。
     *
     * @param lines 待写入的帮助行列表。
     */
    private static void appendRegistryHelpLines(List<String> lines) {
        for (CommandDescriptor descriptor : CommandRegistry.all()) {
            if (!descriptor.supportsScope(CommandRegistry.SCOPE_GATEWAY)) {
                continue;
            }
            List<String> usages = USAGE_OVERRIDES.get(descriptor.getName());
            if (usages == null || usages.isEmpty()) {
                lines.add(helpLine(descriptor.slashName(), descriptor.getDescription()));
            } else {
                for (String usage : usages) {
                    lines.add(helpLine(usage, descriptor.getDescription()));
                }
            }
        }
    }

    /**
     * 构建参数化命令的展示用法表。
     *
     * @return 以注册表规范名索引的展示用法。
     */
    private static Map<String, List<String>> buildUsageOverrides() {
        Map<String, List<String>> usages = new LinkedHashMap<String, List<String>>();
        put(usages, "branch", "/branch [name]");
        put(usages, "resume", "/resume <session-or-branch>");
        put(usages, "sessions", "/sessions [query]");
        put(usages, "commands", "/commands [page]");
        put(usages, "debug", "/debug [status]");
        put(usages, "title", "/title [clear|新标题]");
        put(
                usages,
                "goal",
                "/goal [status|show|pause|resume|clear|stop|done|wait <pid>|unwait|<目标> --max-turns"
                        + " N|--max N]");
        put(usages, "subgoal", "/subgoal [<text>|remove <n>|clear]");
        put(usages, "busy", "/busy [status|queue|steer|interrupt|reject]");
        put(usages, "queue", "/queue <prompt>");
        put(usages, "steer", "/steer <prompt>");
        put(usages, "security", "/security [status|audit|policy]");
        put(usages, "personality", "/personality [name|none]");
        put(usages, "version", "/version [check|update]");
        put(usages, "setup", "/setup [status|model|gateway]");
        put(usages, "config", "/config [get|set|set-secret|refresh]");
        put(usages, "model", "/model [--global] [provider:]<model>|clear");
        put(usages, "fast", "/fast [fast|normal|status]");
        put(usages, "reasoning", "/reasoning [level|reset|show|hide]");
        put(usages, "tools", "/tools [list|enable|disable] [name...]");
        put(usages, "browser", "/browser [status|connect|disconnect <session-id>]");
        put(usages, "voice", "/voice [status]");
        put(
                usages,
                "skills",
                "/skills"
                        + " [list|browse|search|install|inspect|check|update|audit|uninstall|tap|enable|disable|reload]");
        put(usages, "curator", "/curator [status|list|improvements|run|pause|resume]");
        put(
                usages,
                "reload-mcp",
                "/reload-mcp [now|always]；确认：/approve [确认编号]|/approve always [确认编号]|/cancel");
        put(
                usages,
                "cron",
                "/cron [list"
                        + " [--all]|inspect|show|next|upcoming|guide|tutorial|capabilities|policy|add|edit|pause|disable|stop|resume|enable|start|remove|delete|run|trigger|retry|rerun|history|status|tick]");
        put(usages, "proactive", "/proactive [status|pause|resume|why|less|more|ignore|retry]");
        put(usages, "recap", "/recap [limit]");
        put(
                usages,
                "trajectory",
                "/trajectory [user-query]",
                "/trajectory save [--failed] [user-query]");
        put(usages, "compact", "/compact [focus]");
        put(usages, "rollback", "/rollback [latest|checkpoint-id|number]");
        put(usages, "pairing", "/pairing [pending|approve|revoke|approved]");
        put(
                usages,
                "approve",
                "/approve auto [status|on|off]",
                "/approve [#序号|审批ID|all] [session|always]",
                "/approve list|status|clear session|clear always|clear all");
        put(usages, "deny", "/deny [#序号|审批ID|all]", "/deny list|status|all");
        return usages;
    }

    /**
     * 拼接单条帮助行。
     *
     * @param usage 命令用法文本。
     * @param description 命令用途说明。
     * @return 帮助行文本。
     */
    private static String helpLine(String usage, String description) {
        return usage + " - " + description;
    }

    /**
     * 写入单个命令的一个或多个用法。
     *
     * @param usages 用法表。
     * @param command 注册表规范名。
     * @param values 展示用法列表。
     */
    private static void put(Map<String, List<String>> usages, String command, String... values) {
        usages.put(command, Arrays.asList(values));
    }
}
