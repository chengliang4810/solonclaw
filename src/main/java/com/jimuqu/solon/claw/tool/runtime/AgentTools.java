package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.agent.AgentProfile;
import com.jimuqu.solon.claw.agent.AgentProfileService;
import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** Agent 管理工具。 */
public class AgentTools {
    /** 注入Agent角色配置服务，用于调用对应业务能力。 */
    private final AgentProfileService agentProfileService;

    /** 保存会话仓储依赖，用于访问持久化数据。 */
    private final SessionRepository sessionRepository;

    /** 记录Agent中的来源键。 */
    private final String sourceKey;

    /**
     * 创建Agent管理工具，保留旧文本命令路径。
     *
     * @param agentProfileService Agent角色配置服务。
     * @param sessionRepository 会话仓储。
     * @param sourceKey 当前来源键。
     */
    public AgentTools(
            AgentProfileService agentProfileService,
            SessionRepository sessionRepository,
            String sourceKey) {
        this.agentProfileService = agentProfileService;
        this.sessionRepository = sessionRepository;
        this.sourceKey = sourceKey;
    }

    /**
     * 执行AgentManage相关逻辑。
     *
     * @param args 工具或命令参数。
     * @return 返回Agent Manage结果。
     */
    public String agentManage(
            @Param(
                            name = "args",
                            description =
                                    "Agent command arguments, for example: list, create coder 你是代码助手, use coder, tools coder read_file,skills_list")
                    String args) {
        try {
            String result = agentProfileService.handleCommand(args, sessionRepository, sourceKey);
            return ToolResultEnvelope.ok("Agent 管理完成")
                    .preview(SecretRedactor.redact(result, 2000))
                    .toJson();
        } catch (Exception e) {
            return ToolResultEnvelope.error(
                            SecretRedactor.redact(
                                    e.getMessage() == null
                                            ? e.getClass().getSimpleName()
                                            : e.getMessage(),
                                    1000))
                    .toJson();
        }
    }

    /**
     * 执行结构化Agent管理动作。
     *
     * @param action 动作名称。
     * @param name Agent 名称。
     * @param sessionId 会话标识。
     * @param args 旧文本命令参数。
     * @return 返回工具结果 JSON。
     */
    @ToolMapping(
            name = "agent_manage",
            description =
                    "Manage named Agents. Structured read actions: list, get. Slash-command args still support list, use <name>, create <name> [role], show <name>, model/tools/skills/memory <name> ..., delete <name>. The built-in default Agent cannot be edited or deleted.")
    public String agentManage(
            @Param(name = "action", required = false, description = "Structured action: list, get")
                    String action,
            @Param(name = "name", required = false, description = "Agent name for get")
                    String name,
            @Param(name = "session_id", required = false, description = "Session id for active agent marker")
                    String sessionId,
            @Param(name = "args", required = false, description = "Fallback slash-command style args")
                    String args) {
        String normalized =
                action == null ? "" : action.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.length() == 0) {
            return agentManage(args);
        }
        if (!"list".equals(normalized)
                && !"get".equals(normalized)
                && !"show".equals(normalized)
                && !"detail".equals(normalized)) {
            return agentManage(args == null || args.trim().length() == 0 ? action : args);
        }
        try {
            Map<String, Object> result =
                    "list".equals(normalized) ? listAgents(sessionId) : getAgent(name, sessionId);
            return ToolResultEnvelope.ok("Agent 查询完成")
                    .preview(SecretRedactor.redact(String.valueOf(result), 2000))
                    .data("result", result)
                    .toJson();
        } catch (Exception e) {
            return ToolResultEnvelope.error(
                            SecretRedactor.redact(
                                    e.getMessage() == null
                                            ? e.getClass().getSimpleName()
                                            : e.getMessage(),
                                    1000))
                    .toJson();
        }
    }

    /**
     * 列出 Agent 配置。
     *
     * @param sessionId 会话标识。
     * @return 返回结构化 Agent 列表。
     */
    private Map<String, Object> listAgents(String sessionId) throws Exception {
        String active = activeAgent(sessionId);
        List<Map<String, Object>> agents = new ArrayList<Map<String, Object>>();
        for (AgentProfile profile : agentProfileService.listAll()) {
            agents.add(agentView(profile, active));
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("agents", agents);
        result.put("active_agent_name", active);
        return result;
    }

    /**
     * 查询单个 Agent 配置。
     *
     * @param name Agent 名称。
     * @param sessionId 会话标识。
     * @return 返回结构化 Agent 详情。
     */
    private Map<String, Object> getAgent(String name, String sessionId) throws Exception {
        AgentProfile profile = agentProfileService.findByName(name);
        if (profile == null) {
            throw new IllegalArgumentException("未找到 Agent：" + safe(name));
        }
        return agentView(profile, activeAgent(sessionId));
    }

    /**
     * 生成 Agent 结构化视图。
     *
     * @param profile Agent 配置。
     * @param active 当前会话激活 Agent。
     * @return 返回安全视图。
     */
    private Map<String, Object> agentView(AgentProfile profile, String active) {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("name", safe(profile.getAgentName()));
        view.put("display_name", safe(profile.getDisplayName()));
        view.put("description", safe(profile.getDescription()));
        view.put("role_prompt", safe(profile.getRolePrompt()));
        view.put("default_model", safe(profile.getDefaultModel()));
        view.put("allowed_tools_json", safe(profile.getAllowedToolsJson()));
        view.put("skills_json", safe(profile.getSkillsJson()));
        view.put("memory", safe(profile.getMemory()));
        view.put("enabled", Boolean.valueOf(profile.isEnabled()));
        view.put(
                "active",
                Boolean.valueOf(
                        profile.getAgentName() != null && profile.getAgentName().equals(active)));
        return view;
    }

    /**
     * 查询会话当前 Agent。
     *
     * @param sessionId 会话标识。
     * @return 返回当前 Agent 名称。
     */
    private String activeAgent(String sessionId) throws Exception {
        if (sessionRepository == null || StrUtil.isBlank(sessionId)) {
            return "";
        }
        SessionRecord session = sessionRepository.findById(sessionId);
        return safe(session == null ? "" : session.getActiveAgentName());
    }

    /**
     * 生成安全展示文本。
     *
     * @param text 原始文本。
     * @return 返回脱敏文本。
     */
    private String safe(String text) {
        return SecretRedactor.redact(StrUtil.nullToEmpty(text), 1000);
    }
}
