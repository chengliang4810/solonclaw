package com.jimuqu.solon.claw.gateway.command;

import com.jimuqu.solon.claw.command.CommandDescriptor;
import com.jimuqu.solon.claw.command.CommandRegistry;
import com.jimuqu.solon.claw.support.constants.GatewayCommandConstants;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 集中渲染消息网关 slash command 帮助文本，避免默认命令服务承担长文本维护职责。 */
final class SlashCommandHelpRenderer {
    /** 需要追加到网关帮助末尾的终端增强命令，保持原有展示顺序。 */
    private static final List<String> REGISTRY_HELP_COMMANDS =
            Arrays.asList(
                    "background",
                    "tasks",
                    "statusbar",
                    "copy",
                    "paste",
                    "image");

    /** 阻止工具类被实例化。 */
    private SlashCommandHelpRenderer() {}

    /**
     * 渲染完整帮助文本。
     *
     * @return 每行一个命令说明的帮助文本。
     */
    static String render() {
        List<String> lines = new ArrayList<String>();
        appendCoreHelpLines(lines);
        appendRegistryHelpLines(lines);
        return String.join("\n", lines);
    }

    /**
     * 追加网关已实现命令的固定帮助行。
     *
     * @param lines 待写入的帮助行列表。
     */
    private static void appendCoreHelpLines(List<String> lines) {
        lines.add(helpLine(GatewayCommandConstants.SLASH_NEW, "创建并切换到新会话"));
        lines.add(helpLine(GatewayCommandConstants.SLASH_RESET, "重置当前会话并重新开始"));
        lines.add(helpLine(GatewayCommandConstants.SLASH_RETRY, "重新执行上一条用户消息"));
        lines.add(helpLine(GatewayCommandConstants.SLASH_UNDO, "撤销上一轮对话"));
        lines.add(helpLine(GatewayCommandConstants.SLASH_BRANCH + " [name]", "从当前会话创建分支"));
        lines.add(
                helpLine(
                        GatewayCommandConstants.SLASH_RESUME + " <session-or-branch>",
                        "恢复指定会话或分支"));
        lines.add(helpLine(GatewayCommandConstants.SLASH_SESSIONS + " [query]", "浏览并搜索历史会话"));
        lines.add(helpLine(GatewayCommandConstants.SLASH_WHOAMI, "查看当前 slash 命令访问身份"));
        lines.add(helpLine(GatewayCommandConstants.SLASH_COMMANDS + " [page]", "浏览全部 slash 命令"));
        lines.add(helpLine(GatewayCommandConstants.SLASH_INSIGHTS, "查看使用洞察与运行摘要"));
        lines.add(helpLine(GatewayCommandConstants.SLASH_DEBUG + " [status]", "查看脱敏调试诊断摘要"));
        lines.add(
                helpLine(
                        GatewayCommandConstants.SLASH_TITLE + " [clear|新标题]",
                        "查看、设置或清空当前会话标题"));
        lines.add(helpLine(GatewayCommandConstants.SLASH_STATUS, "查看当前会话状态"));
        lines.add(helpLine(GatewayCommandConstants.SLASH_USAGE, "查看当前会话运行信息"));
        lines.add(
                helpLine(
                        GatewayCommandConstants.SLASH_GOAL
                                + " [status|pause|resume|clear|<目标> --max-turns N|--max N]",
                        "设置跨轮长目标并由 judge 驱动自动继续"));
        lines.add(
                helpLine(
                        GatewayCommandConstants.SLASH_BUSY
                                + " [status|queue|steer|interrupt|reject]",
                        "查看或切换运行中输入策略"));
        lines.add(helpLine(GatewayCommandConstants.SLASH_QUEUE + " <prompt>", "将提示排到当前任务之后执行"));
        lines.add(
                helpLine(
                        GatewayCommandConstants.SLASH_STEER + " <prompt>",
                        "向运行中任务注入修正；空闲时按普通提示执行"));
        lines.add(helpLine(GatewayCommandConstants.SLASH_RESTART, "等待运行中任务 drain 后重启网关"));
        lines.add(helpLine(GatewayCommandConstants.SLASH_STOP, "停止当前任务和后台进程"));
        lines.add(
                helpLine(
                        GatewayCommandConstants.SLASH_APPROVE + " auto [status|on|off]",
                        "查询或设置当前会话的危险命令自动批准模式"));
        lines.add(helpLine(GatewayCommandConstants.SLASH_PERSONALITY + " [name|none]", "查看或切换人格"));
        lines.add(helpLine(GatewayCommandConstants.SLASH_VERSION + " [check|update]", "查看版本或执行更新"));
        lines.add(helpLine(GatewayCommandConstants.SLASH_UPDATE, "执行应用更新"));
        lines.add(
                helpLine(
                        GatewayCommandConstants.SLASH_MODEL + " [--global] [provider:]<model>|clear",
                        "查看或切换模型"));
        lines.add(helpLine(GatewayCommandConstants.SLASH_FAST + " [fast|normal|status]", "查看或切换当前会话快速模式"));
        lines.add(
                helpLine(
                        GatewayCommandConstants.SLASH_REASONING + " [level|reset|show|hide]",
                        "查看或切换 reasoning 强度和展示"));
        lines.add(
                helpLine(
                        GatewayCommandConstants.SLASH_TOOLS + " [list|enable|disable] [name...]",
                        "查看或管理工具开关"));
        lines.add(helpLine(GatewayCommandConstants.SLASH_TOOLSETS, "列出可用工具集"));
        lines.add(
                helpLine(
                        GatewayCommandConstants.SLASH_BROWSER
                                + " [status|connect|disconnect <session-id>]",
                        "管理浏览器自动化运行时"));
        lines.add(
                helpLine(
                        GatewayCommandConstants.SLASH_SKILLS
                                + " [list|browse|search|install|inspect|check|update|audit|uninstall|tap|enable|disable|reload]",
                        "管理本地技能与 Skills Hub"));
        lines.add(
                helpLine(
                        GatewayCommandConstants.SLASH_CURATOR
                                + " [status|list|improvements|run|pause|resume]",
                        "管理技能后台维护状态与运行"));
        lines.add(helpLine(GatewayCommandConstants.SLASH_PLUGINS, "查看插件加载状态"));
        lines.add(helpLine(GatewayCommandConstants.SLASH_RELOAD_SKILLS, "重新扫描本地技能目录"));
        lines.add(
                helpLine(
                        GatewayCommandConstants.SLASH_RELOAD_MCP
                                + " [now|always]；确认：/approve [确认编号]|/approve always [确认编号]|/cancel",
                        "重新加载 MCP 工具并刷新工具变更基线"));
        lines.add(helpLine(GatewayCommandConstants.SLASH_CONFIRM, "查看当前待确认 slash 命令"));
        lines.add(
                helpLine(
                        GatewayCommandConstants.SLASH_AGENT
                                + " [name|list|create|show|model|tools|skills|memory]",
                        "切换或管理当前会话 Agent"));
        lines.add(
                helpLine(
                        GatewayCommandConstants.SLASH_CRON
                                + " [list [--all]|inspect|show|next|upcoming|guide|tutorial|capabilities|policy|add|edit|pause|disable|stop|resume|enable|start|remove|delete|run|trigger|retry|rerun|history|status|tick]",
                        "管理定时任务"));
        lines.add(helpLine(GatewayCommandConstants.SLASH_RECAP + " [limit]", "显示恢复会话用的紧凑历史摘要"));
        lines.add(helpLine(GatewayCommandConstants.SLASH_TRAJECTORY + " [user-query]", "导出会话 trajectory JSON"));
        lines.add(
                helpLine(
                        GatewayCommandConstants.SLASH_TRAJECTORY + " save [--failed] [user-query]",
                        "追加保存 trajectory JSONL 到 workspace/artifacts"));
        lines.add(helpLine(GatewayCommandConstants.SLASH_COMPACT + " [focus]", "压缩当前会话上下文"));
        lines.add(
                helpLine(
                        GatewayCommandConstants.SLASH_ROLLBACK + " [latest|checkpoint-id|number]",
                        "回滚到指定 checkpoint"));
        lines.add(helpLine(GatewayCommandConstants.SLASH_SETHOME, "将当前聊天设为 home channel"));
        lines.add(
                helpLine(
                        GatewayCommandConstants.SLASH_PAIRING
                                + " [claim-admin|pending|approve|revoke|approved]",
                        "管理渠道配对与管理员授权"));
        lines.add(
                helpLine(
                        GatewayCommandConstants.SLASH_APPROVE + " [#序号|审批ID|all] [session|always]",
                        "批准待审批危险命令"));
        lines.add(
                helpLine(
                        GatewayCommandConstants.SLASH_APPROVE
                                + " list|status|clear session|clear always|clear all",
                        "查看或清理审批授权"));
        lines.add(helpLine(GatewayCommandConstants.SLASH_DENY + " [#序号|审批ID|all]", "拒绝待审批危险命令"));
        lines.add(helpLine(GatewayCommandConstants.SLASH_DENY + " list|status|all", "查看或批量拒绝待审批命令"));
        lines.add(helpLine(GatewayCommandConstants.SLASH_PLATFORMS, "查看平台连接与授权状态"));
        lines.add(helpLine(GatewayCommandConstants.SLASH_PLATFORM, "查看平台连接与授权状态"));
        lines.add(helpLine(GatewayCommandConstants.SLASH_HELP, "显示帮助信息"));
    }

    /**
     * 追加已登记但由其他入口实现的命令帮助行。
     *
     * @param lines 待写入的帮助行列表。
     */
    private static void appendRegistryHelpLines(List<String> lines) {
        for (String commandName : REGISTRY_HELP_COMMANDS) {
            lines.add(registryHelpLine(commandName));
        }
    }

    /**
     * 从命令注册表渲染单条帮助行。
     *
     * @param commandName 命令规范名。
     * @return 用户可见帮助行。
     */
    private static String registryHelpLine(String commandName) {
        CommandDescriptor descriptor = CommandRegistry.get(commandName);
        return helpLine(descriptor.slashName(), descriptor.getDescription());
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
}
