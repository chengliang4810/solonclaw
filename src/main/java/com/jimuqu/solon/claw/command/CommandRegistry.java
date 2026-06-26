package com.jimuqu.solon.claw.command;

import cn.hutool.core.collection.CollUtil;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 维护命令注册信息，供运行时按需查询和装配。 */
public final class CommandRegistry {
    /** 命令可在本地 CLI 入口使用。 */
    private static final String SCOPE_CLI = "cli";

    /** 命令可在消息网关入口使用。 */
    private static final String SCOPE_GATEWAY = "gateway";

    /** 命令可在独立 TUI 入口使用。 */
    private static final String SCOPE_TUI = "tui";

    /** 命令规范名到描述符的注册表，使用 LinkedHashMap 保持帮助输出顺序稳定。 */
    private static final Map<String, CommandDescriptor> COMMANDS =
            new LinkedHashMap<String, CommandDescriptor>();

    /** 命令别名到规范名的索引，用于让 CLI、TUI、消息网关共享解析结果。 */
    private static final Map<String, String> ALIASES = new LinkedHashMap<String, String>();

    static {
        register(core("help", "system", "显示帮助信息"));
        register(core("new", "session", "创建并切换到新会话"));
        register(core("reset", "session", "重置当前会话并重新开始"));
        register(core("retry", "session", "重新执行上一条用户消息"));
        register(core("undo", "session", "撤销上一轮对话"));
        register(core("branch", "session", "从当前会话创建分支").alias("fork"));
        register(core("resume", "session", "恢复指定会话或分支"));
        register(core("sessions", "session", "浏览并搜索历史会话"));
        register(core("whoami", "info", "查看当前 slash 命令访问身份"));
        register(core("commands", "info", "浏览全部 slash 命令"));
        register(core("insights", "info", "查看使用洞察与运行摘要"));
        register(core("debug", "info", "查看脱敏调试诊断摘要"));
        register(core("title", "session", "查看、设置或清空当前会话标题"));
        register(core("status", "session", "查看当前会话状态"));
        register(core("usage", "session", "查看当前会话运行信息"));
        register(core("goal", "agent", "设置跨轮长目标并由 judge 驱动自动继续"));
        register(core("busy", "runtime", "查看或切换运行中输入策略"));
        register(core("queue", "runtime", "将提示排到当前任务之后执行").alias("q"));
        register(core("steer", "runtime", "向运行中任务注入修正；空闲时按普通提示执行"));
        register(core("restart", "runtime", "等待运行中任务 drain 后重启网关"));
        register(core("stop", "runtime", "停止当前任务和后台进程"));
        register(core("security", "security", "查看安全策略、审批、审计与终端安全状态"));
        register(core("personality", "agent", "查看或切换人格"));
        register(core("version", "system", "查看版本或执行更新"));
        register(core("update", "system", "执行应用更新"));
        register(core("setup", "configuration", "配置模型、消息渠道与初始化设置"));
        register(core("config", "configuration", "查看或写入本地运行配置"));
        register(core("model", "model", "查看或切换模型"));
        register(core("fast", "model", "查看或切换当前会话快速模式"));
        register(core("reasoning", "model", "查看或切换 reasoning 展示"));
        register(core("tools", "tool", "查看或管理工具开关"));
        register(core("toolsets", "tool", "列出可用工具集"));
        register(core("browser", "tool", "管理浏览器自动化运行时"));
        register(core("skills", "skill", "管理本地技能与 Skills Hub"));
        register(core("curator", "skill", "管理技能后台维护状态与运行"));
        register(core("plugins", "tool", "查看插件加载状态"));
        register(core("reload-skills", "skill", "重新扫描本地技能目录"));
        register(core("reload-mcp", "mcp", "重新加载 MCP 工具并刷新工具变更基线"));
        register(core("confirm", "security", "查看当前待确认 slash 命令"));
        register(core("agent", "agent", "切换或管理当前会话 Agent"));
        register(core("cron", "automation", "管理定时任务"));
        register(core("proactive", "automation", "查看、暂停或调节主动协作"));
        register(core("recap", "session", "显示恢复会话用的紧凑历史摘要"));
        register(core("trajectory", "session", "导出会话 trajectory JSON"));
        register(core("compact", "session", "压缩当前会话上下文"));
        register(core("rollback", "session", "回滚到指定 checkpoint"));
        register(core("sethome", "gateway", "将当前聊天设为 home channel").alias("set-home"));
        register(core("pairing", "gateway", "管理渠道配对与管理员授权"));
        register(core("approve", "security", "批准待审批危险命令"));
        register(core("deny", "security", "拒绝待审批危险命令"));
        register(core("cancel", "security", "取消当前待确认 slash 命令"));
        register(core("platforms", "gateway", "查看平台连接与授权状态").alias("gateway"));
        register(core("platform", "gateway", "查看平台连接与授权状态"));

        register(terminal("background", "管理后台任务运行方式").alias("bg").alias("btw"));
        register(terminal("tasks", "查看后台任务列表").alias("agents"));
        register(terminal("statusbar", "管理 TUI 状态栏显示").alias("status-bar").alias("sb"));
        register(terminal("footer", "管理 TUI 底部栏显示"));
        register(terminal("skin", "查看或切换 TUI 皮肤"));
        register(terminal("copy", "复制终端选区或输出"));
        register(terminal("paste", "粘贴终端剪贴板内容"));
        register(terminal("image", "附加或管理图片输入"));
        register(terminal("history", "预览当前终端会话的最近历史"));
        register(terminal("handoff", "生成会话交接信息"));
        register(terminal("subgoal", "管理当前目标的子目标"));
        register(terminal("quit", "退出当前终端会话").alias("exit"));
    }

    /** 创建命令注册表实例。 */
    private CommandRegistry() {}

    /**
     * 按规范名读取命令描述符。
     *
     * @param name 命令规范名或带斜杠的命令文本。
     * @return 已注册描述符；未注册时返回 null。
     */
    public static CommandDescriptor get(String name) {
        return COMMANDS.get(CommandDescriptor.normalize(name));
    }

    /**
     * 按规范名或别名解析命令描述符。
     *
     * @param command 待执行或解析的命令文本。
     * @return 已注册描述符；未注册时返回 null。
     */
    public static CommandDescriptor resolve(String command) {
        String normalized = CommandDescriptor.normalize(command);
        String canonical = ALIASES.containsKey(normalized) ? ALIASES.get(normalized) : normalized;
        return COMMANDS.get(canonical);
    }

    /**
     * 返回全部命令描述符，保持注册顺序。
     *
     * @return 不可变命令集合。
     */
    public static Collection<CommandDescriptor> all() {
        return Collections.unmodifiableCollection(COMMANDS.values());
    }

    /**
     * 返回全部用户可输入的斜杠命令名。
     *
     * @return 不可变斜杠命令列表。
     */
    public static List<String> slashCommands() {
        List<String> commands = new ArrayList<String>();
        for (CommandDescriptor descriptor : COMMANDS.values()) {
            commands.add(descriptor.slashName());
        }
        return Collections.unmodifiableList(commands);
    }

    /**
     * 创建三端共享的核心命令构建器。
     *
     * @param category 分类参数。
     * @param description 描述参数。
     * @return 默认支持 CLI、消息网关和 TUI 的命令构建器。
     */
    private static CommandDescriptor.Builder core(
            String name, String category, String description) {
        return CommandDescriptor.builder(name)
                .category(category)
                .description(description)
                .scopes(SCOPE_CLI, SCOPE_GATEWAY, SCOPE_TUI);
    }

    /**
     * 创建终端增强命令构建器。
     *
     * @param name 名称参数。
     * @param description 描述参数。
     * @return 归入 terminal 分组的命令构建器。
     */
    private static CommandDescriptor.Builder terminal(String name, String description) {
        return core(name, "terminal", description);
    }

    /**
     * 注册构建器声明的命令。
     *
     * @param builder 已填充元数据的构建器。
     */
    private static void register(CommandDescriptor.Builder builder) {
        register(builder.build());
    }

    /**
     * 写入命令注册表和别名索引。
     *
     * @param descriptor 命令描述符。
     */
    private static void register(CommandDescriptor descriptor) {
        COMMANDS.put(descriptor.getName(), descriptor);
        ALIASES.put(descriptor.getName(), descriptor.getName());
        if (CollUtil.isNotEmpty(descriptor.getAliases())) {
            for (String alias : descriptor.getAliases()) {
                ALIASES.put(alias, descriptor.getName());
            }
        }
    }
}
