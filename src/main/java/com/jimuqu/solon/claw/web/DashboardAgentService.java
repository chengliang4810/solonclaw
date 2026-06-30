package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.agent.AgentDefaultMetadata;
import com.jimuqu.solon.claw.agent.AgentProfile;
import com.jimuqu.solon.claw.agent.AgentProfileService;
import com.jimuqu.solon.claw.agent.AgentRuntimeScope;
import com.jimuqu.solon.claw.agent.AgentRuntimeService;
import com.jimuqu.solon.claw.core.model.AgentRunRecord;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.noear.snack4.ONode;

/** Dashboard Agent 管理服务。 */
public class DashboardAgentService {
    /** 注入Agent角色配置服务，用于调用对应业务能力。 */
    private final AgentProfileService agentProfileService;

    /** 注入Agent运行时服务，用于调用对应业务能力。 */
    private final AgentRuntimeService agentRuntimeService;

    /** 保存会话仓储依赖，用于访问持久化数据。 */
    private final SessionRepository sessionRepository;

    /** 保存Agent运行仓储依赖，用于访问持久化数据。 */
    private final AgentRunRepository agentRunRepository;

    /**
     * 创建控制台Agent服务实例，并注入运行所需依赖。
     *
     * @param agentProfileService 文件或目录路径参数。
     * @param agentRuntimeService Agent运行时服务依赖。
     * @param sessionRepository 会话仓储依赖。
     * @param agentRunRepository Agent运行仓储依赖。
     */
    public DashboardAgentService(
            AgentProfileService agentProfileService,
            AgentRuntimeService agentRuntimeService,
            SessionRepository sessionRepository,
            AgentRunRepository agentRunRepository) {
        this.agentProfileService = agentProfileService;
        this.agentRuntimeService = agentRuntimeService;
        this.sessionRepository = sessionRepository;
        this.agentRunRepository = agentRunRepository;
    }

    /**
     * 执行列表相关逻辑。
     *
     * @param sessionId 当前会话标识。
     * @return 返回list结果。
     */
    public Map<String, Object> list(String sessionId) throws Exception {
        List<Map<String, Object>> agents = new ArrayList<Map<String, Object>>();
        SessionRecord session =
                StrUtil.isBlank(sessionId) ? null : sessionRepository.findById(sessionId);
        String active =
                AgentRuntimeScope.normalizeName(
                        session == null ? null : session.getActiveAgentName());
        for (AgentProfile profile : agentProfileService.listAll()) {
            agents.add(toSummary(profile, active));
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("agents", agents);
        result.put("active_agent_name", safe(active, 400));
        return result;
    }

    /**
     * 获取当前注册项或配置项。
     *
     * @param name 名称参数。
     * @param sessionId 当前会话标识。
     * @return 返回get结果。
     */
    public Map<String, Object> get(String name, String sessionId) throws Exception {
        String normalized = agentRuntimeService.normalizeName(name);
        SessionRecord session =
                StrUtil.isBlank(sessionId) ? null : sessionRepository.findById(sessionId);
        String active =
                AgentRuntimeScope.normalizeName(
                        session == null ? null : session.getActiveAgentName());
        if (AgentRuntimeScope.DEFAULT_AGENT.equals(normalized)) {
            return defaultAgent(active);
        }
        AgentProfile profile = agentProfileService.findByName(normalized);
        if (profile == null) {
            throw new IllegalArgumentException("未找到 Agent：" + safe(normalized, 400));
        }
        return toDetail(profile, active);
    }

    /**
     * 执行create，服务于控制台Agent主流程相关逻辑。
     *
     * @param body 请求体或消息正文内容。
     * @return 返回create结果。
     */
    public Map<String, Object> create(Map<String, Object> body) throws Exception {
        String name = string(body, "name");
        String role = string(body, "role_prompt");
        if (StrUtil.isBlank(role)) {
            role = string(body, "role");
        }
        AgentProfile profile = agentProfileService.createAgent(name, role);
        applyMutableFields(profile, body);
        profile = agentProfileService.save(profile);
        return toDetail(profile, "");
    }

    /**
     * 执行更新相关逻辑。
     *
     * @param name 名称参数。
     * @param body 请求体或消息正文内容。
     * @return 返回更新结果。
     */
    public Map<String, Object> update(String name, Map<String, Object> body) throws Exception {
        agentRuntimeService.rejectDefault(name);
        AgentProfile profile = agentProfileService.findByName(name);
        if (profile == null) {
            throw new IllegalArgumentException("未找到 Agent：" + safe(name, 400));
        }
        applyMutableFields(profile, body);
        profile = agentProfileService.save(profile);
        return toDetail(profile, "");
    }

    /**
     * 执行delete，服务于控制台Agent主流程相关逻辑。
     *
     * @param name 名称参数。
     * @return 返回delete结果。
     */
    public Map<String, Object> delete(String name) throws Exception {
        agentRuntimeService.rejectDefault(name);
        String normalized = agentRuntimeService.normalizeName(name);
        sessionRepository.clearActiveAgentName(normalized);
        agentProfileService.deleteByName(normalized);
        return Collections.singletonMap("ok", Boolean.TRUE);
    }

    /**
     * 执行activate相关逻辑。
     *
     * @param name 名称参数。
     * @param body 请求体或消息正文内容。
     * @return 返回activate结果。
     */
    public Map<String, Object> activate(String name, Map<String, Object> body) throws Exception {
        String sessionId = string(body, "session_id");
        if (StrUtil.isBlank(sessionId)) {
            throw new IllegalArgumentException("session_id 不能为空。");
        }
        String normalized = agentRuntimeService.normalizeName(name);
        if (!AgentRuntimeScope.DEFAULT_AGENT.equals(normalized)) {
            AgentProfile profile = agentProfileService.findByName(normalized);
            if (profile == null) {
                throw new IllegalArgumentException("未找到 Agent：" + safe(normalized, 400));
            }
            if (!profile.isEnabled()) {
                throw new IllegalArgumentException("Agent 已停用：" + safe(normalized, 400));
            }
        }
        SessionRecord session = sessionRepository.findById(sessionId);
        if (session == null) {
            session = new SessionRecord();
            session.setSessionId(sessionId);
            session.setSourceKey("MEMORY:dashboard:" + sessionId);
            session.setBranchName("main");
            session.setNdjson("");
            session.setTitle("");
            session.setCreatedAt(System.currentTimeMillis());
            session.setUpdatedAt(System.currentTimeMillis());
            sessionRepository.save(session);
        }
        sessionRepository.setActiveAgentName(
                sessionId, AgentRuntimeScope.DEFAULT_AGENT.equals(normalized) ? null : normalized);
        agentRuntimeService.markUsed(normalized);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("session_id", safe(sessionId, 400));
        result.put("active_agent_name", safe(normalized, 400));
        return result;
    }

    /**
     * 执行默认Agent相关逻辑。
     *
     * @param active active 参数。
     * @return 返回默认Agent结果。
     */
    private Map<String, Object> defaultAgent(String active) {
        AgentRuntimeScope scope = agentRuntimeService.defaultScope();
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("name", AgentRuntimeScope.DEFAULT_AGENT);
        item.put("display_name", scope.getDisplayName());
        item.put(
                "description",
                StrUtil.blankToDefault(
                        scope.getDescription(), AgentDefaultMetadata.description()));
        item.put("default_agent", true);
        item.put("readonly", true);
        item.put("enabled", true);
        item.put("active", AgentRuntimeScope.DEFAULT_AGENT.equals(active));
        item.put("default_model", "");
        item.put("role_prompt", "");
        item.put("allowed_tools_json", "[]");
        item.put("skills_json", "[]");
        item.put("memory", "");
        putPathReferences(item, scope.getEffectiveName());
        item.put("running_runs", 0);
        item.put("recent_runs", Collections.emptyList());
        return item;
    }

    /**
     * 转换为Summary。
     *
     * @param profile 文件或目录路径参数。
     * @param active active 参数。
     * @return 返回转换后的Summary。
     */
    private Map<String, Object> toSummary(AgentProfile profile, String active) {
        Map<String, Object> item = baseProfile(profile, active);
        item.put("role_prompt", profile.getRolePrompt());
        item.put("memory", profile.getMemory());
        return item;
    }

    /**
     * 转换为Detail。
     *
     * @param profile 文件或目录路径参数。
     * @param active active 参数。
     * @return 返回转换后的Detail。
     */
    private Map<String, Object> toDetail(AgentProfile profile, String active) throws Exception {
        Map<String, Object> item = baseProfile(profile, active);
        item.put("role_prompt", profile.getRolePrompt());
        item.put("allowed_tools_json", StrUtil.blankToDefault(profile.getAllowedToolsJson(), "[]"));
        item.put("skills_json", StrUtil.blankToDefault(profile.getSkillsJson(), "[]"));
        item.put("memory", profile.getMemory());
        List<Map<String, Object>> runs = recentRuns(profile.getAgentName());
        item.put("recent_runs", runs);
        item.put("running_runs", countRunning(runs));
        return item;
    }

    /**
     * 执行基础角色配置相关逻辑。
     *
     * @param profile 文件或目录路径参数。
     * @param active active 参数。
     * @return 返回base角色配置。
     */
    private Map<String, Object> baseProfile(AgentProfile profile, String active) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("name", profile.getAgentName());
        item.put(
                "display_name",
                StrUtil.blankToDefault(profile.getDisplayName(), profile.getAgentName()));
        item.put("description", StrUtil.nullToEmpty(profile.getDescription()));
        item.put("default_agent", false);
        item.put("readonly", false);
        item.put("enabled", profile.isEnabled());
        item.put("active", StrUtil.equals(profile.getAgentName(), active));
        item.put("default_model", StrUtil.nullToEmpty(profile.getDefaultModel()));
        putPathReferences(item, profile.getAgentName());
        item.put("last_used_at", profile.getLastUsedAt());
        item.put("updated_at", profile.getUpdatedAt());
        return item;
    }

    /**
     * 写入路径References。
     *
     * @param item item 参数。
     * @param agentName Agent名称参数。
     */
    private void putPathReferences(Map<String, Object> item, String agentName) {
        String base = "agent://" + agentRuntimeService.normalizeName(agentName);
        item.put("workspace_path", base + "/workspace");
        item.put("skills_path", base + "/skills");
        item.put("cache_path", base + "/cache");
    }

    /**
     * 执行recent运行相关逻辑。
     *
     * @param agentName Agent名称参数。
     * @return 返回recent运行结果。
     */
    private List<Map<String, Object>> recentRuns(String agentName) throws Exception {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        int total = Math.max(sessionRepository.countAll(), 1);
        List<SessionRecord> sessions =
                sessionRepository.listRecent(Math.min(Math.max(total, 100), 1000), 0);
        for (SessionRecord session : sessions) {
            for (AgentRunRecord run :
                    agentRunRepository.listBySession(session.getSessionId(), 10)) {
                if (!StrUtil.equals(agentName, run.getAgentName())) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("run_id", safe(run.getRunId(), 400));
                item.put("session_id", safe(run.getSessionId(), 400));
                item.put("status", run.getStatus());
                item.put("model", safe(run.getModel(), 400));
                item.put("started_at", run.getStartedAt());
                item.put("finished_at", run.getFinishedAt());
                result.add(item);
                if (result.size() >= 5) {
                    return result;
                }
            }
        }
        return result;
    }

    /**
     * 执行次数Running相关逻辑。
     *
     * @param runs runs 参数。
     * @return 返回次数Running结果。
     */
    private int countRunning(List<Map<String, Object>> runs) {
        int count = 0;
        for (Map<String, Object> run : runs) {
            if ("running".equals(run.get("status"))) {
                count++;
            }
        }
        return count;
    }

    /**
     * 应用MutableFields。
     *
     * @param profile 文件或目录路径参数。
     * @param body 请求体或消息正文内容。
     */
    private void applyMutableFields(AgentProfile profile, Map<String, Object> body) {
        if (body == null) {
            return;
        }
        if (body.containsKey("display_name")) profile.setDisplayName(string(body, "display_name"));
        if (body.containsKey("description")) profile.setDescription(string(body, "description"));
        if (body.containsKey("role_prompt")) profile.setRolePrompt(string(body, "role_prompt"));
        if (body.containsKey("default_model"))
            profile.setDefaultModel(string(body, "default_model"));
        if (body.containsKey("allowed_tools_json"))
            profile.setAllowedToolsJson(jsonString(body.get("allowed_tools_json")));
        if (body.containsKey("skills_json"))
            profile.setSkillsJson(jsonString(body.get("skills_json")));
        if (body.containsKey("memory")) profile.setMemory(string(body, "memory"));
        if (body.containsKey("enabled"))
            profile.setEnabled(
                    Boolean.TRUE.equals(body.get("enabled"))
                            || "true".equalsIgnoreCase(String.valueOf(body.get("enabled"))));
    }

    /**
     * 执行JSON字符串相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回JSON String结果。
     */
    private String jsonString(Object value) {
        if (value == null) {
            return "[]";
        }
        if (value instanceof String) {
            return String.valueOf(value);
        }
        return ONode.serialize(value);
    }

    /**
     * 执行string相关逻辑。
     *
     * @param body 请求体或消息正文内容。
     * @param key 配置键或映射键。
     * @return 返回string结果。
     */
    private String string(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    /**
     * 执行安全相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @param maxLength 最大保留字符数。
     * @return 返回safe结果。
     */
    private String safe(String value, int maxLength) {
        return SecretRedactor.redact(value, maxLength);
    }
}
