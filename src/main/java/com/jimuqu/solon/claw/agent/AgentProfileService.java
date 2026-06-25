package com.jimuqu.solon.claw.agent;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import java.util.Arrays;
import java.util.List;
import org.noear.snack4.ONode;

/** 用户侧 Agent 管理服务。底层仍复用 agent_profiles 表，不暴露 Profile 概念。 */
public class AgentProfileService {
    /** 保存仓储依赖，用于访问持久化数据。 */
    private final AgentProfileRepository repository;

    /** 注入运行时服务，用于调用对应业务能力。 */
    private final AgentRuntimeService runtimeService;

    /**
     * 创建Agent角色配置服务实例，并注入运行所需依赖。
     *
     * @param repository repository依赖组件。
     * @param runtimeService 运行时服务依赖。
     */
    public AgentProfileService(
            AgentProfileRepository repository, AgentRuntimeService runtimeService) {
        this.repository = repository;
        this.runtimeService = runtimeService;
    }

    /**
     * 创建Agent。
     *
     * @param agentName Agent名称参数。
     * @param rolePrompt role提示词参数。
     * @return 返回创建好的Agent。
     */
    public AgentProfile createAgent(String agentName, String rolePrompt) throws Exception {
        if (runtimeService != null) {
            return runtimeService.create(agentName, rolePrompt);
        }
        validateName(agentName);
        rejectDefault(agentName);
        AgentProfile existing = repository.findByName(agentName);
        if (existing != null) {
            return existing;
        }
        long now = System.currentTimeMillis();
        AgentProfile profile = new AgentProfile();
        profile.setAgentName(agentName);
        profile.setDisplayName(agentName);
        profile.setRolePrompt(StrUtil.blankToDefault(rolePrompt, "你是一个可复用的任务 Agent。"));
        profile.setDefaultModel("");
        profile.setAllowedToolsJson("[]");
        profile.setSkillsJson("[]");
        profile.setMemory("");
        profile.setEnabled(true);
        profile.setCreatedAt(now);
        profile.setUpdatedAt(now);
        return repository.save(profile);
    }

    /**
     * 确保默认。
     *
     * @param agentName Agent名称参数。
     * @param rolePrompt role提示词参数。
     * @return 返回默认结果。
     */
    public AgentProfile ensureDefault(String agentName, String rolePrompt) throws Exception {
        return createAgent(agentName, rolePrompt);
    }

    /**
     * 执行命令相关逻辑。
     *
     * @param args 工具或命令参数。
     * @return 返回命令结果。
     */
    public String handleCommand(String args) throws Exception {
        return handleCommand(args, null, null);
    }

    /**
     * 执行命令相关逻辑。
     *
     * @param args 工具或命令参数。
     * @param sessionRepository 会话仓储依赖。
     * @param sourceKey 渠道来源键。
     * @return 返回命令结果。
     */
    public String handleCommand(String args, SessionRepository sessionRepository, String sourceKey)
            throws Exception {
        String raw = StrUtil.nullToEmpty(args).trim();
        if (StrUtil.isBlank(raw)) {
            return formatList(currentSession(sessionRepository, sourceKey));
        }
        String[] parts = raw.split("\\s+", 3);
        String action = parts[0].toLowerCase();

        if ("list".equals(action)) return formatList(currentSession(sessionRepository, sourceKey));
        if ("use".equals(action))
            return switchAgent(parts.length > 1 ? parts[1] : "", sessionRepository, sourceKey);
        if ("create".equals(action)) return create(parts);
        if ("show".equals(action))
            return formatShow(resolveForShow(parts.length > 1 ? parts[1] : ""));
        if ("model".equals(action)) return updateModel(parts);
        if ("tools".equals(action)) return updateTools(parts);
        if ("skills".equals(action)) return updateSkills(parts);
        if ("memory".equals(action)) return appendMemory(parts);
        if ("delete".equals(action) || "remove".equals(action)) return delete(parts);
        if ("clone".equals(action)) return "default 是内置 Agent，命名 Agent 第一版不提供克隆。";

        if (parts.length == 1) {
            return switchAgent(parts[0], sessionRepository, sourceKey);
        }
        return "用法：/agent、/agent <name>、/agent use <name>、/agent create <name> [角色]、/agent show|model|tools|skills|memory <name> ...";
    }

    /**
     * 根据名称查找对应数据。
     *
     * @param name 名称参数。
     * @return 返回按名称查找得到的结果。
     */
    public AgentProfile findByName(String name) throws Exception {
        return repository.findByName(name);
    }

    /**
     * 列出全部。
     *
     * @return 返回全部列表。
     */
    public List<AgentProfile> listAll() throws Exception {
        return repository.listAll();
    }

    /**
     * 执行save，服务于Agent角色配置主流程相关逻辑。
     *
     * @param profile 文件或目录路径参数。
     * @return 返回save结果。
     */
    public AgentProfile save(AgentProfile profile) throws Exception {
        rejectDefault(profile.getAgentName());
        if (runtimeService != null) {
            return runtimeService.save(profile);
        }
        profile.setUpdatedAt(System.currentTimeMillis());
        return repository.save(profile);
    }

    /**
     * 根据名称删除对应数据。
     *
     * @param name 名称参数。
     */
    public void deleteByName(String name) throws Exception {
        rejectDefault(name);
        repository.deleteByName(name);
    }

    /**
     * 执行switchAgent相关逻辑。
     *
     * @param name 名称参数。
     * @param sessionRepository 会话仓储依赖。
     * @param sourceKey 渠道来源键。
     * @return 返回switch Agent结果。
     */
    private String switchAgent(String name, SessionRepository sessionRepository, String sourceKey)
            throws Exception {
        if (sessionRepository == null || StrUtil.isBlank(sourceKey)) {
            return "当前入口无法切换 Agent。";
        }
        String normalized = normalizeName(name);
        if (!AgentRuntimeScope.DEFAULT_AGENT.equals(normalized)) {
            AgentProfile profile = repository.findByName(normalized);
            if (profile == null) {
                return "未找到 Agent：" + normalized + "。可使用 /agent create " + normalized + " 创建。";
            }
            if (!profile.isEnabled()) {
                return "Agent 已停用：" + normalized;
            }
        }

        SessionRecord session = sessionRepository.getBoundSession(sourceKey);
        if (session == null) {
            session = sessionRepository.bindNewSession(sourceKey);
        }
        String stored = AgentRuntimeScope.DEFAULT_AGENT.equals(normalized) ? null : normalized;
        sessionRepository.setActiveAgentName(session.getSessionId(), stored);
        if (runtimeService != null) {
            runtimeService.markUsed(normalized);
        }
        return "已切换当前会话 Agent 为：" + normalized + "。正在运行的任务不会受影响。";
    }

    /**
     * 执行create，服务于Agent角色配置主流程相关逻辑。
     *
     * @param parts parts 参数。
     * @return 返回create结果。
     */
    private String create(String[] parts) throws Exception {
        if (parts.length < 2 || StrUtil.isBlank(parts[1])) return "用法：/agent create <name> [角色说明]";
        String role = parts.length > 2 ? parts[2] : "你是一个可复用的任务 Agent。";
        AgentProfile profile = createAgent(parts[1], role);
        return "已创建 Agent：" + profile.getAgentName();
    }

    /**
     * 更新模型。
     *
     * @param parts parts 参数。
     * @return 返回模型结果。
     */
    private String updateModel(String[] parts) throws Exception {
        if (parts.length < 3 || StrUtil.hasBlank(parts[1], parts[2]))
            return "用法：/agent model <name> <model|clear>";
        if (AgentRuntimeScope.DEFAULT_AGENT.equals(normalizeName(parts[1]))) {
            return defaultMutationRejected();
        }
        AgentProfile profile = requireNamedProfile(parts[1]);
        profile.setDefaultModel(isClear(parts[2]) ? "" : parts[2].trim());
        save(profile);
        return "已更新 Agent 默认模型："
                + profile.getAgentName()
                + " -> "
                + StrUtil.blankToDefault(profile.getDefaultModel(), "全局默认");
    }

    /**
     * 更新工具。
     *
     * @param parts parts 参数。
     * @return 返回工具结果。
     */
    private String updateTools(String[] parts) throws Exception {
        if (parts.length < 3 || StrUtil.hasBlank(parts[1], parts[2]))
            return "用法：/agent tools <name> <tool1,tool2>";
        if (AgentRuntimeScope.DEFAULT_AGENT.equals(normalizeName(parts[1]))) {
            return defaultMutationRejected();
        }
        AgentProfile profile = requireNamedProfile(parts[1]);
        profile.setAllowedToolsJson(csvToJson(parts[2]));
        save(profile);
        return "已更新 Agent 工具：" + profile.getAgentName();
    }

    /**
     * 更新技能。
     *
     * @param parts parts 参数。
     * @return 返回技能结果。
     */
    private String updateSkills(String[] parts) throws Exception {
        if (parts.length < 3 || StrUtil.hasBlank(parts[1], parts[2]))
            return "用法：/agent skills <name> <skill1,skill2>";
        if (AgentRuntimeScope.DEFAULT_AGENT.equals(normalizeName(parts[1]))) {
            return defaultMutationRejected();
        }
        AgentProfile profile = requireNamedProfile(parts[1]);
        profile.setSkillsJson(csvToJson(parts[2]));
        save(profile);
        return "已更新 Agent 技能：" + profile.getAgentName();
    }

    /**
     * 追加记忆。
     *
     * @param parts parts 参数。
     * @return 返回记忆结果。
     */
    private String appendMemory(String[] parts) throws Exception {
        if (parts.length < 3 || StrUtil.hasBlank(parts[1], parts[2]))
            return "用法：/agent memory <name> <记忆内容>";
        if (AgentRuntimeScope.DEFAULT_AGENT.equals(normalizeName(parts[1]))) {
            return defaultMutationRejected();
        }
        AgentProfile profile = requireNamedProfile(parts[1]);
        profile.setMemory(
                StrUtil.nullToEmpty(profile.getMemory())
                        + (StrUtil.isBlank(profile.getMemory()) ? "" : "\n")
                        + parts[2].trim());
        save(profile);
        return "已追加 Agent 记忆：" + profile.getAgentName();
    }

    /**
     * 执行delete，服务于Agent角色配置主流程相关逻辑。
     *
     * @param parts parts 参数。
     * @return 返回delete结果。
     */
    private String delete(String[] parts) throws Exception {
        if (parts.length < 2 || StrUtil.isBlank(parts[1])) return "用法：/agent delete <name>";
        if (AgentRuntimeScope.DEFAULT_AGENT.equals(normalizeName(parts[1]))) {
            return defaultMutationRejected();
        }
        rejectDefault(parts[1]);
        repository.deleteByName(parts[1]);
        return "已删除 Agent：" + parts[1];
    }

    /**
     * 要求指定名称角色配置。
     *
     * @param name 名称参数。
     * @return 返回指定名称角色配置。
     */
    private AgentProfile requireNamedProfile(String name) throws Exception {
        rejectDefault(name);
        AgentProfile profile = repository.findByName(name);
        if (profile == null) {
            throw new IllegalStateException("未找到 Agent：" + name);
        }
        return profile;
    }

    /**
     * 解析For展示。
     *
     * @param name 名称参数。
     * @return 返回解析后的For展示。
     */
    private Object resolveForShow(String name) throws Exception {
        String normalized = normalizeName(name);
        if (AgentRuntimeScope.DEFAULT_AGENT.equals(normalized)) {
            return normalized;
        }
        return requireNamedProfile(normalized);
    }

    /**
     * 格式化List。
     *
     * @param session 会话参数。
     * @return 返回List结果。
     */
    private String formatList(SessionRecord session) throws Exception {
        List<AgentProfile> agents = repository.listAll();
        String active = normalizeName(session == null ? null : session.getActiveAgentName());
        StringBuilder builder = new StringBuilder("Agents：");
        builder.append("\n- default（内置，默认工作区）");
        if (AgentRuntimeScope.DEFAULT_AGENT.equals(active)) builder.append(" *当前*");
        if (CollUtil.isNotEmpty(agents)) {
            for (AgentProfile agent : agents) {
                builder.append("\n- ").append(agent.getAgentName());
                if (StrUtil.isNotBlank(agent.getDisplayName())
                        && !StrUtil.equals(agent.getDisplayName(), agent.getAgentName())) {
                    builder.append("（").append(agent.getDisplayName()).append("）");
                }
                if (StrUtil.isNotBlank(agent.getDefaultModel()))
                    builder.append(" model=").append(agent.getDefaultModel());
                if (!agent.isEnabled()) builder.append(" 已停用");
                if (StrUtil.equals(active, agent.getAgentName())) builder.append(" *当前*");
            }
        }
        builder.append("\n使用 /agent <name> 或 /agent default 切换当前会话。");
        return builder.toString();
    }

    /**
     * 格式化展示。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回展示结果。
     */
    private String formatShow(Object value) {
        if (value instanceof String && AgentRuntimeScope.DEFAULT_AGENT.equals(value)) {
            return "Agent: default\n类型：内置默认 Agent\n位置：默认工作区\n说明：default 不在 workspace/agents 下管理，不允许编辑、删除或克隆。";
        }
        AgentProfile profile = (AgentProfile) value;
        return "Agent: "
                + profile.getAgentName()
                + "\n显示名: "
                + StrUtil.blankToDefault(profile.getDisplayName(), profile.getAgentName())
                + "\n说明: "
                + StrUtil.blankToDefault(profile.getDescription(), "")
                + "\n角色: "
                + StrUtil.nullToDefault(profile.getRolePrompt(), "")
                + "\n默认模型: "
                + StrUtil.blankToDefault(profile.getDefaultModel(), "全局默认")
                + "\n工具: "
                + StrUtil.blankToDefault(profile.getAllowedToolsJson(), "[]")
                + "\n技能: "
                + StrUtil.blankToDefault(profile.getSkillsJson(), "[]")
                + "\n记忆: "
                + StrUtil.blankToDefault(profile.getMemory(), "无")
                + "\n启用: "
                + profile.isEnabled();
    }

    /**
     * 执行当前会话相关逻辑。
     *
     * @param sessionRepository 会话仓储依赖。
     * @param sourceKey 渠道来源键。
     * @return 返回当前会话结果。
     */
    private SessionRecord currentSession(SessionRepository sessionRepository, String sourceKey)
            throws Exception {
        if (sessionRepository == null || StrUtil.isBlank(sourceKey)) {
            return null;
        }
        return sessionRepository.getBoundSession(sourceKey);
    }

    /**
     * 判断是否Clear。
     *
     * @param value 待规范化或校验的原始值。
     * @return 如果Clear满足条件则返回 true，否则返回 false。
     */
    private boolean isClear(String value) {
        return "clear".equalsIgnoreCase(value)
                || "none".equalsIgnoreCase(value)
                || "default".equalsIgnoreCase(value);
    }

    /**
     * 执行CSVToJSON相关逻辑。
     *
     * @param csv csv 参数。
     * @return 返回CSV To JSON结果。
     */
    private String csvToJson(String csv) {
        if (StrUtil.isBlank(csv)) {
            return "[]";
        }
        return toJson(Arrays.asList(csv.split("\\s*,\\s*")));
    }

    /**
     * 转换为JSON。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回转换后的JSON。
     */
    private String toJson(Object value) {
        return ONode.serialize(value);
    }

    /**
     * 执行默认变更拒绝相关逻辑。
     *
     * @return 返回默认变更拒绝结果。
     */
    private String defaultMutationRejected() {
        return "default 是内置 Agent，映射默认工作区，不允许创建、编辑、删除或克隆。请在工作区上下文或全局设置中调整默认行为。";
    }

    /**
     * 规范化名称。
     *
     * @param name 名称参数。
     * @return 返回名称结果。
     */
    private String normalizeName(String name) {
        return runtimeService == null
                ? AgentRuntimeScope.normalizeName(name)
                : runtimeService.normalizeName(name);
    }

    /**
     * 校验名称。
     *
     * @param name 名称参数。
     */
    private void validateName(String name) {
        if (runtimeService != null) {
            runtimeService.validateName(name);
        }
    }

    /**
     * 执行reject默认相关逻辑。
     *
     * @param name 名称参数。
     */
    private void rejectDefault(String name) {
        if (runtimeService != null) {
            runtimeService.rejectDefault(name);
            return;
        }
        if (AgentRuntimeScope.DEFAULT_AGENT.equalsIgnoreCase(
                AgentRuntimeScope.normalizeName(name))) {
            throw new IllegalArgumentException("default 是内置 Agent，映射默认工作区，不允许创建、编辑、删除或克隆。");
        }
    }
}
