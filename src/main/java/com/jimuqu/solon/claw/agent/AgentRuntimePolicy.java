package com.jimuqu.solon.claw.agent;

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

/** Agent 运行时 tools / skills 选择策略。 */
public final class AgentRuntimePolicy {
    /** KNOWN工具名称列表的统一常量值。 */
    private static final List<String> KNOWN_TOOL_NAMES =
            Arrays.asList(
                    ToolNameConstants.FILE_READ,
                    ToolNameConstants.FILE_WRITE,
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
                    ToolNameConstants.DELEGATE_TASK,
                    ToolNameConstants.MEMORY,
                    ToolNameConstants.SESSION_SEARCH,
                    ToolNameConstants.SKILLS_LIST,
                    ToolNameConstants.SKILL_VIEW,
                    ToolNameConstants.SKILL_MANAGE,
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
                    ToolNameConstants.CODESEARCH,
                    ToolNameConstants.WEBSEARCH,
                    ToolNameConstants.WEBFETCH,
                    ToolNameConstants.IMAGE_GENERATE,
                    ToolNameConstants.TEXT_TO_SPEECH,
                    ToolNameConstants.SPEECH_TRANSCRIBE,
                    ToolNameConstants.BROWSER,
                    ToolNameConstants.SECURITY_AUDIT,
                    ToolNameConstants.CLARIFY);

    /** 创建Agent运行时策略实例。 */
    private AgentRuntimePolicy() {}

    /**
     * 解析Allowed工具。
     *
     * @param agentScope 当前运行冻结后的 Agent 范围。
     * @param allToolNames all工具Names参数。
     * @return 返回解析后的Allowed工具。
     */
    public static List<String> resolveAllowedTools(
            AgentRuntimeScope agentScope, List<String> allToolNames) {
        if (agentScope == null) {
            return new ArrayList<String>(allToolNames);
        }
        List<String> configured = parseStringList(agentScope.getAllowedToolsJson());
        if (configured.isEmpty() && agentScope.isDefaultAgentName()) {
            return new ArrayList<String>(allToolNames);
        }
        if (configured.isEmpty()) {
            return new ArrayList<String>(allToolNames);
        }

        LinkedHashSet<String> expanded = new LinkedHashSet<String>();
        for (String item : configured) {
            addToolOrGroup(expanded, item);
        }

        List<String> allowed = new ArrayList<String>();
        for (String toolName : allToolNames) {
            if (expanded.contains(toolName)) {
                allowed.add(toolName);
            }
        }
        return allowed;
    }

    /**
     * 执行expand工具Selectors相关逻辑。
     *
     * @param selectors selectors 参数。
     * @return 返回expand工具Selectors结果。
     */
    public static LinkedHashSet<String> expandToolSelectors(List<String> selectors) {
        LinkedHashSet<String> expanded = new LinkedHashSet<String>();
        if (selectors == null) {
            return expanded;
        }
        for (String item : selectors) {
            addToolOrGroup(expanded, item);
        }
        return expanded;
    }

    /**
     * 判断是否工具Allowed。
     *
     * @param agentScope 当前运行冻结后的 Agent 范围。
     * @param toolName 工具名称。
     * @return 如果工具Allowed满足条件则返回 true，否则返回 false。
     */
    public static boolean isToolAllowed(AgentRuntimeScope agentScope, String toolName) {
        if (agentScope == null) {
            return true;
        }
        List<String> configured = parseStringList(agentScope.getAllowedToolsJson());
        if (configured.isEmpty() && agentScope.isDefaultAgentName()) {
            return true;
        }
        if (configured.isEmpty()) {
            return true;
        }
        LinkedHashSet<String> expanded = new LinkedHashSet<String>();
        for (String item : configured) {
            addToolOrGroup(expanded, item);
        }
        return expanded.contains(toolName);
    }

    /**
     * 判断是否技能Allowed。
     *
     * @param agentScope 当前运行冻结后的 Agent 范围。
     * @param descriptor descriptor 参数。
     * @return 如果技能Allowed满足条件则返回 true，否则返回 false。
     */
    public static boolean isSkillAllowed(AgentRuntimeScope agentScope, SkillDescriptor descriptor) {
        if (descriptor == null || agentScope == null || agentScope.isDefaultAgentName()) {
            return true;
        }
        Set<String> allowed = resolveAllowedSkills(agentScope);
        if (allowed.isEmpty()) {
            return true;
        }
        return allowed.contains(descriptor.canonicalName())
                || allowed.contains(descriptor.getName());
    }

    /**
     * 解析Allowed技能。
     *
     * @param agentScope 当前运行冻结后的 Agent 范围。
     * @return 返回解析后的Allowed技能。
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
     * 解析String List。
     *
     * @param raw 原始输入值。
     * @return 返回解析后的String List。
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
        } catch (Exception ignored) {
            // 为了兼容对话内斜杠命令，这里回退解析逗号分隔输入。
        }

        addCsv(result, value);
        return result;
    }

    /**
     * 追加字符串。
     *
     * @param result 结果响应或执行结果。
     * @param item item 参数。
     */
    private static void addString(List<String> result, Object item) {
        String text = item == null ? "" : String.valueOf(item).trim();
        if (StrUtil.isNotBlank(text)) {
            result.add(text);
        }
    }

    /**
     * 追加CSV。
     *
     * @param result 结果响应或执行结果。
     * @param csv csv 参数。
     */
    private static void addCsv(List<String> result, String csv) {
        for (String item : StrUtil.nullToEmpty(csv).split("\\s*,\\s*")) {
            if (StrUtil.isNotBlank(item)) {
                result.add(item.trim());
            }
        }
    }

    /**
     * 追加工具Or群组。
     *
     * @param output 命令执行输出文本。
     * @param value 待规范化或校验的原始值。
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
}
