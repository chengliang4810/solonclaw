package com.jimuqu.solon.claw.command;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Central slash command registry shared by gateway, CLI, and TUI surfaces. */
public final class CommandRegistry {
    private static final String SCOPE_CLI = "cli";
    private static final String SCOPE_GATEWAY = "gateway";
    private static final String SCOPE_TUI = "tui";

    private static final Map<String, CommandDescriptor> COMMANDS = new LinkedHashMap<String, CommandDescriptor>();
    private static final Map<String, String> ALIASES = new LinkedHashMap<String, String>();

    static {
        register(core("help", "system", "显示帮助信息"));
        register(core("new", "session", "创建并切换到新会话"));
        register(core("reset", "session", "重置当前会话并重新开始"));
        register(core("retry", "session", "重新执行上一条用户消息"));
        register(core("undo", "session", "撤销上一轮对话"));
        register(core("branch", "session", "从当前会话创建分支"));
        register(core("resume", "session", "恢复指定会话或分支"));
        register(core("title", "session", "查看、设置或清空当前会话标题"));
        register(core("status", "session", "查看当前会话状态"));
        register(core("usage", "session", "查看当前会话运行信息"));
        register(core("goal", "agent", "设置跨轮长目标并由 judge 驱动自动继续"));
        register(core("busy", "runtime", "查看或切换运行中输入策略"));
        register(core("queue", "runtime", "将提示排到当前任务之后执行"));
        register(core("steer", "runtime", "向运行中任务注入修正；空闲时按普通提示执行"));
        register(core("restart", "runtime", "等待运行中任务 drain 后重启网关"));
        register(core("stop", "runtime", "停止当前任务和后台进程"));
        register(core("yolo", "security", "查询或设置当前会话的危险命令自动批准模式"));
        register(core("security", "security", "查看安全策略、审批、审计与终端安全状态"));
        register(core("personality", "agent", "查看或切换人格"));
        register(core("version", "system", "查看版本或执行更新"));
        register(core("model", "model", "查看或切换模型"));
        register(core("reasoning", "model", "查看或切换 reasoning 展示"));
        register(core("tools", "tool", "查看或管理工具开关"));
        register(core("skills", "skill", "管理本地技能与 Skills Hub"));
        register(core("reload-mcp", "mcp", "重新加载 MCP 工具并刷新工具变更基线"));
        register(core("acp", "integration", "查看 ACP 本地适配器能力快照"));
        register(core("confirm", "security", "查看当前待确认 slash 命令"));
        register(core("agent", "agent", "切换或管理当前会话 Agent"));
        register(core("cron", "automation", "管理定时任务"));
        register(core("kanban", "automation", "管理协作看板、任务抽屉、执行流水和多 Agent 派发"));
        register(core("recap", "session", "显示恢复会话用的紧凑历史摘要"));
        register(core("trajectory", "session", "导出会话 trajectory JSON"));
        register(core("compact", "session", "压缩当前会话上下文").alias("compress"));
        register(core("compress", "session", "压缩当前会话上下文"));
        register(core("rollback", "session", "回滚到指定 checkpoint"));
        register(core("sethome", "gateway", "将当前聊天设为 home channel"));
        register(core("pairing", "gateway", "管理渠道配对与管理员授权"));
        register(core("approve", "security", "批准待审批危险命令"));
        register(core("deny", "security", "拒绝待审批危险命令"));
        register(core("always", "security", "永久批准当前待确认 slash 命令"));
        register(core("cancel", "security", "取消当前待确认 slash 命令"));
        register(core("platforms", "gateway", "查看平台连接与授权状态"));

        register(terminal("background", "管理后台任务运行方式"));
        register(terminal("tasks", "查看后台任务列表"));
        register(terminal("statusbar", "管理 TUI 状态栏显示").alias("status-bar"));
        register(terminal("footer", "管理 TUI 底部栏显示"));
        register(terminal("copy", "复制终端选区或输出"));
        register(terminal("paste", "粘贴终端剪贴板内容"));
        register(terminal("image", "附加或管理图片输入"));
        register(terminal("handoff", "生成会话交接信息"));
        register(terminal("subgoal", "管理当前目标的子目标"));
    }

    private CommandRegistry() {}

    public static CommandDescriptor get(String name) {
        return COMMANDS.get(CommandDescriptor.normalize(name));
    }

    public static CommandDescriptor resolve(String command) {
        String normalized = CommandDescriptor.normalize(command);
        String canonical = ALIASES.containsKey(normalized) ? ALIASES.get(normalized) : normalized;
        return COMMANDS.get(canonical);
    }

    public static Collection<CommandDescriptor> all() {
        return Collections.unmodifiableCollection(COMMANDS.values());
    }

    public static List<String> slashCommands() {
        List<String> commands = new ArrayList<String>();
        for (CommandDescriptor descriptor : COMMANDS.values()) {
            commands.add(descriptor.slashName());
        }
        return Collections.unmodifiableList(commands);
    }

    private static CommandDescriptor.Builder core(String name, String category, String description) {
        return CommandDescriptor.builder(name)
                .category(category)
                .description(description)
                .scopes(SCOPE_CLI, SCOPE_GATEWAY, SCOPE_TUI);
    }

    private static CommandDescriptor.Builder terminal(String name, String description) {
        return core(name, "terminal", description);
    }

    private static void register(CommandDescriptor.Builder builder) {
        register(builder.build());
    }

    private static void register(CommandDescriptor descriptor) {
        COMMANDS.put(descriptor.getName(), descriptor);
        ALIASES.put(descriptor.getName(), descriptor.getName());
        for (String alias : descriptor.getAliases()) {
            ALIASES.put(alias, descriptor.getName());
        }
    }
}
