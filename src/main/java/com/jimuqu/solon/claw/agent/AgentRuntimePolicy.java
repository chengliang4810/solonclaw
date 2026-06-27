package com.jimuqu.solon.claw.agent;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.SkillDescriptor;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.noear.snack4.ONode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Agent 运行时工具与技能选择策略，负责把角色配置冻结成单轮可执行的白名单。 */
public final class AgentRuntimePolicy {
    /** Agent 运行时策略的低敏诊断日志。 */
    private static final Logger log = LoggerFactory.getLogger(AgentRuntimePolicy.class);

    /** 内置工具名全集，支持 Agent 配置中的 all / * 选择器展开。 */
    private static final List<String> KNOWN_TOOL_NAMES =
            Arrays.asList(
                    ToolNameConstants.FILE_READ,
                    ToolNameConstants.FILE_WRITE,
                    ToolNameConstants.READ_FILE,
                    ToolNameConstants.WRITE_FILE,
                    ToolNameConstants.SEARCH_FILES,
                    ToolNameConstants.FILE_LIST,
                    ToolNameConstants.FILE_DELETE,
                    ToolNameConstants.PATCH,
                    ToolNameConstants.EXECUTE_SHELL,
                    ToolNameConstants.TERMINAL,
                    ToolNameConstants.PROCESS,
                    ToolNameConstants.EXECUTE_CODE,
                    ToolNameConstants.EXECUTE_PYTHON,
                    ToolNameConstants.EXECUTE_JS,
                    ToolNameConstants.GET_CURRENT_TIME,
                    ToolNameConstants.TODO,
                    ToolNameConstants.AGENT_MANAGE,
                    ToolNameConstants.RUN_MANAGE,
                    ToolNameConstants.DELEGATE_TASK,
                    ToolNameConstants.MEMORY,
                    ToolNameConstants.SESSION_SEARCH,
                    ToolNameConstants.SEARCH_MANAGE,
                    ToolNameConstants.SESSION_MANAGE,
                    ToolNameConstants.ANALYTICS_MANAGE,
                    ToolNameConstants.LOGS_MANAGE,
                    ToolNameConstants.MEDIA_MANAGE,
                    ToolNameConstants.STATUS_MANAGE,
                    ToolNameConstants.DIAGNOSTICS_MANAGE,
                    ToolNameConstants.DOCTOR_MANAGE,
                    ToolNameConstants.TUI_RUNTIME_MANAGE,
                    ToolNameConstants.INSIGHTS_MANAGE,
                    ToolNameConstants.APPROVAL_EVENTS_MANAGE,
                    ToolNameConstants.APPROVAL_QUEUE_MANAGE,
                    ToolNameConstants.WORKSPACE_MANAGE,
                    ToolNameConstants.WORKSPACE_CONFIG_MANAGE,
                    ToolNameConstants.CONFIG_MANAGE,
                    ToolNameConstants.GATEWAY_SETUP_MANAGE,
                    ToolNameConstants.SKILLS_LIST,
                    ToolNameConstants.SKILL_VIEW,
                    ToolNameConstants.SKILL_FILES,
                    ToolNameConstants.SKILL_MANAGE,
                    ToolNameConstants.TOOLSETS_MANAGE,
                    ToolNameConstants.SKILLS_HUB_SEARCH,
                    ToolNameConstants.SKILLS_HUB_INSPECT,
                    ToolNameConstants.SKILLS_HUB_INSTALL,
                    ToolNameConstants.SKILLS_HUB_LIST,
                    ToolNameConstants.SKILLS_HUB_CHECK,
                    ToolNameConstants.SKILLS_HUB_UPDATE,
                    ToolNameConstants.SKILLS_HUB_AUDIT,
                    ToolNameConstants.SKILLS_HUB_UNINSTALL,
                    ToolNameConstants.SKILLS_HUB_TAP,
                    ToolNameConstants.SEND_MESSAGE,
                    ToolNameConstants.CRONJOB,
                    ToolNameConstants.CONFIG_GET,
                    ToolNameConstants.CONFIG_SET,
                    ToolNameConstants.CONFIG_SET_SECRET,
                    ToolNameConstants.CONFIG_REFRESH,
                    ToolNameConstants.TOOL_GATEWAY,
                    ToolNameConstants.MCP,
                    ToolNameConstants.MCP_MANAGE,
                    ToolNameConstants.CURATOR_MANAGE,
                    ToolNameConstants.PLATFORM_TOOLSETS_MANAGE,
                    ToolNameConstants.PROVIDER_MANAGE,
                    ToolNameConstants.CODESEARCH,
                    ToolNameConstants.WEBSEARCH,
                    ToolNameConstants.WEBFETCH,
                    ToolNameConstants.IMAGE_GENERATE,
                    ToolNameConstants.TEXT_TO_SPEECH,
                    ToolNameConstants.SPEECH_TRANSCRIBE,
                    ToolNameConstants.BROWSER,
                    ToolNameConstants.SECURITY_AUDIT,
                    ToolNameConstants.CLARIFY);

    /** 工具类只暴露静态策略方法，不允许实例化。 */
    private AgentRuntimePolicy() {}

    /**
     * 返回当前系统已知的内置工具名清单。
     *
     * @return 返回保持声明顺序的工具名副本，调用方可按自身策略过滤。
     */
    public static List<String> knownToolNames() {
        return new ArrayList<String>(KNOWN_TOOL_NAMES);
    }

    /**
     * 根据 Agent 配置解析本轮实际允许注册给模型的工具集合。
     *
     * @param agentScope 当前运行冻结后的 Agent 范围。
     * @param allToolNames 当前运行时已注册的全部工具名，返回结果会保留该顺序。
     * @return 返回本轮可暴露给模型的工具名集合。
     */
    public static List<String> resolveAllowedTools(
            AgentRuntimeScope agentScope, List<String> allToolNames) {
        List<String> configured = configuredToolSelectors(agentScope);
        if (allowsAllTools(agentScope, configured)) {
            return new ArrayList<String>(allToolNames);
        }

        LinkedHashSet<String> expanded = expandToolSelectors(configured);
        List<String> allowed = new ArrayList<String>();
        for (String toolName : allToolNames) {
            if (expanded.contains(toolName)) {
                allowed.add(toolName);
            }
        }
        return allowed;
    }

    /**
     * 展开工具选择器，支持单个工具名、all 与 * 三种配置形态。
     *
     * @param selectors Agent 配置或命令行传入的工具选择器。
     * @return 返回去重且保持声明顺序的工具名集合。
     */
    public static LinkedHashSet<String> expandToolSelectors(List<String> selectors) {
        LinkedHashSet<String> expanded = new LinkedHashSet<String>();
        if (CollUtil.isEmpty(selectors)) {
            return expanded;
        }
        for (String item : selectors) {
            addToolOrGroup(expanded, item);
        }
        return expanded;
    }

    /**
     * 判断指定工具是否允许在当前 Agent 运行范围内执行。
     *
     * @param agentScope 当前运行冻结后的 Agent 范围。
     * @param toolName 待校验的工具名称。
     * @return 如果未配置白名单或工具命中白名单则返回 true。
     */
    public static boolean isToolAllowed(AgentRuntimeScope agentScope, String toolName) {
        List<String> configured = configuredToolSelectors(agentScope);
        if (allowsAllTools(agentScope, configured)) {
            return true;
        }
        return expandToolSelectors(configured).contains(toolName);
    }

    /**
     * 判断技能描述是否允许被当前 Agent 召回。
     *
     * @param agentScope 当前运行冻结后的 Agent 范围。
     * @param descriptor 技能元数据描述。
     * @return 默认 Agent 或未配置技能白名单时返回 true，否则要求命中标准名或短名。
     */
    public static boolean isSkillAllowed(AgentRuntimeScope agentScope, SkillDescriptor descriptor) {
        if (descriptor == null || agentScope == null || agentScope.isDefaultAgentName()) {
            return true;
        }
        Set<String> allowed = resolveAllowedSkills(agentScope);
        if (CollUtil.isEmpty(allowed)) {
            return true;
        }
        return allowed.contains(descriptor.canonicalName())
                || allowed.contains(descriptor.getName());
    }

    /**
     * 解析当前 Agent 配置的技能白名单。
     *
     * @param agentScope 当前运行冻结后的 Agent 范围。
     * @return 返回去重后的技能名集合；空集合表示不限制技能。
     */
    public static Set<String> resolveAllowedSkills(AgentRuntimeScope agentScope) {
        LinkedHashSet<String> allowed = new LinkedHashSet<String>();
        if (agentScope == null || agentScope.isDefaultAgentName()) {
            return allowed;
        }
        for (String item : parseStringList(agentScope.getSkillsJson())) {
            if (StrUtil.isNotBlank(item)) {
                allowed.add(item.trim());
            }
        }
        return allowed;
    }

    /**
     * 解析配置项中的字符串列表，支持 JSON 数组、JSON 字符串和逗号分隔文本。
     *
     * @param raw Agent 配置中的原始列表文本。
     * @return 返回去掉空白项后的字符串列表。
     */
    public static List<String> parseStringList(String raw) {
        List<String> result = new ArrayList<String>();
        String value = StrUtil.nullToEmpty(raw).trim();
        if (StrUtil.isBlank(value)) {
            return result;
        }

        try {
            Object parsed = ONode.deserialize(value, Object.class);
            if (parsed instanceof List) {
                for (Object item : (List<?>) parsed) {
                    addString(result, item);
                }
                return result;
            }
            if (parsed instanceof String) {
                addCsv(result, String.valueOf(parsed));
                return result;
            }
        } catch (Exception e) {
            log.debug(
                    "Agent 列表配置不是 JSON，按逗号分隔文本兜底 length={}, error={}",
                    value.length(),
                    e.getClass().getSimpleName());
        }

        addCsv(result, value);
        return result;
    }

    /**
     * 将非空字符串项追加到结果列表，保留调用方声明顺序。
     *
     * @param result 正在构建的列表。
     * @param item JSON 解析出的候选项。
     */
    private static void addString(List<String> result, Object item) {
        String text = item == null ? "" : String.valueOf(item).trim();
        if (StrUtil.isNotBlank(text)) {
            result.add(text);
        }
    }

    /**
     * 解析逗号分隔文本并追加非空选择器。
     *
     * @param result 正在构建的列表。
     * @param csv 逗号分隔的原始文本。
     */
    private static void addCsv(List<String> result, String csv) {
        for (String item : StrUtil.nullToEmpty(csv).split("\\s*,\\s*")) {
            if (StrUtil.isNotBlank(item)) {
                result.add(item.trim());
            }
        }
    }

    /**
     * 将工具名或工具组选择器写入展开结果。
     *
     * @param output 工具名展开结果。
     * @param value 待展开的工具选择器。
     */
    private static void addToolOrGroup(LinkedHashSet<String> output, String value) {
        String name = StrUtil.nullToEmpty(value).trim();
        if (StrUtil.isBlank(name)) {
            return;
        }
        String key = name.toLowerCase(Locale.ROOT);
        if ("*".equals(key) || "all".equals(key)) {
            output.addAll(KNOWN_TOOL_NAMES);
            return;
        }
        if ("file".equals(key) || "files".equals(key)) {
            output.add(ToolNameConstants.FILE_READ);
            output.add(ToolNameConstants.FILE_WRITE);
            output.add(ToolNameConstants.READ_FILE);
            output.add(ToolNameConstants.WRITE_FILE);
            output.add(ToolNameConstants.SEARCH_FILES);
            output.add(ToolNameConstants.FILE_LIST);
            output.add(ToolNameConstants.FILE_DELETE);
            output.add(ToolNameConstants.PATCH);
            return;
        }
        if ("command".equals(key)
                || "commands".equals(key)
                || "shell".equals(key)
                || "terminal".equals(key)) {
            output.add(ToolNameConstants.EXECUTE_SHELL);
            output.add(ToolNameConstants.TERMINAL);
            output.add(ToolNameConstants.PROCESS);
            output.add(ToolNameConstants.EXECUTE_CODE);
            output.add(ToolNameConstants.EXECUTE_PYTHON);
            output.add(ToolNameConstants.EXECUTE_JS);
            output.add(ToolNameConstants.GET_CURRENT_TIME);
            return;
        }
        if ("skill".equals(key) || "skills".equals(key) || "tools".equals(key)) {
            output.add(ToolNameConstants.SKILLS_LIST);
            output.add(ToolNameConstants.SKILL_VIEW);
            output.add(ToolNameConstants.SKILL_FILES);
            output.add(ToolNameConstants.SKILL_MANAGE);
            return;
        }
        if ("skillhub".equals(key) || "skills_hub".equals(key) || "hub".equals(key)) {
            output.add(ToolNameConstants.SKILLS_HUB_SEARCH);
            output.add(ToolNameConstants.SKILLS_HUB_INSPECT);
            output.add(ToolNameConstants.SKILLS_HUB_INSTALL);
            output.add(ToolNameConstants.SKILLS_HUB_LIST);
            output.add(ToolNameConstants.SKILLS_HUB_CHECK);
            output.add(ToolNameConstants.SKILLS_HUB_UPDATE);
            output.add(ToolNameConstants.SKILLS_HUB_AUDIT);
            output.add(ToolNameConstants.SKILLS_HUB_UNINSTALL);
            output.add(ToolNameConstants.SKILLS_HUB_TAP);
            return;
        }
        if ("memory".equals(key)) {
            output.add(ToolNameConstants.MEMORY);
            output.add(ToolNameConstants.SESSION_SEARCH);
            return;
        }
        if ("web".equals(key) || "search".equals(key)) {
            output.add(ToolNameConstants.WEBSEARCH);
            output.add(ToolNameConstants.WEBFETCH);
            output.add(ToolNameConstants.CODESEARCH);
            return;
        }
        if ("browser".equals(key) || "browsing".equals(key) || "automation".equals(key)) {
            output.add(ToolNameConstants.BROWSER);
            return;
        }
        if ("media".equals(key) || "multimodal".equals(key)) {
            output.add(ToolNameConstants.IMAGE_GENERATE);
            output.add(ToolNameConstants.TEXT_TO_SPEECH);
            output.add(ToolNameConstants.SPEECH_TRANSCRIBE);
            return;
        }
        if ("image".equals(key) || "images".equals(key) || "vision".equals(key)) {
            output.add(ToolNameConstants.IMAGE_GENERATE);
            return;
        }
        if ("speech".equals(key)
                || "voice".equals(key)
                || "audio".equals(key)
                || "tts".equals(key)
                || "transcription".equals(key)
                || "transcribe".equals(key)) {
            output.add(ToolNameConstants.TEXT_TO_SPEECH);
            output.add(ToolNameConstants.SPEECH_TRANSCRIBE);
            return;
        }
        if ("gateway".equals(key) || "tool_gateway".equals(key) || "managed_tools".equals(key)) {
            output.add(ToolNameConstants.TOOL_GATEWAY);
            return;
        }
        if ("mcp".equals(key) || "mcp_tools".equals(key)) {
            output.add(ToolNameConstants.MCP);
            return;
        }
        if ("message".equals(key) || "messaging".equals(key) || "send".equals(key)) {
            output.add(ToolNameConstants.SEND_MESSAGE);
            return;
        }
        if ("clarify".equals(key) || "clarification".equals(key)) {
            output.add(ToolNameConstants.CLARIFY);
            return;
        }
        if ("cron".equals(key) || "cronjob".equals(key)) {
            output.add(ToolNameConstants.CRONJOB);
            return;
        }
        if ("security".equals(key) || "audit".equals(key) || "security_audit".equals(key)) {
            output.add(ToolNameConstants.SECURITY_AUDIT);
            return;
        }
        if ("delegate".equals(key) || "delegation".equals(key)) {
            output.add(ToolNameConstants.DELEGATE_TASK);
            return;
        }
        if ("agent".equals(key) || "agents".equals(key)) {
            output.add(ToolNameConstants.AGENT_MANAGE);
            return;
        }
        if ("config".equals(key)) {
            output.add(ToolNameConstants.CONFIG_GET);
            output.add(ToolNameConstants.CONFIG_SET);
            output.add(ToolNameConstants.CONFIG_SET_SECRET);
            output.add(ToolNameConstants.CONFIG_REFRESH);
            return;
        }
        output.add(name);
    }

    /**
     * 读取当前 Agent 的工具选择器配置。
     *
     * @param agentScope 当前运行冻结后的 Agent 范围。
     * @return 返回配置中的选择器；scope 为空时返回 null 以保持默认放行语义。
     */
    private static List<String> configuredToolSelectors(AgentRuntimeScope agentScope) {
        return agentScope == null ? null : parseStringList(agentScope.getAllowedToolsJson());
    }

    /**
     * 判断工具配置是否等价于不限制工具。
     *
     * @param agentScope 当前运行冻结后的 Agent 范围。
     * @param configured 已解析的工具选择器。
     * @return scope 为空或选择器为空时返回 true，与历史默认放行行为保持一致。
     */
    private static boolean allowsAllTools(AgentRuntimeScope agentScope, List<String> configured) {
        return agentScope == null || CollUtil.isEmpty(configured);
    }
}
