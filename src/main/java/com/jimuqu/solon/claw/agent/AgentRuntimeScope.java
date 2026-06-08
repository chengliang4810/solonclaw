package com.jimuqu.solon.claw.agent;

import cn.hutool.core.util.StrUtil;

/** 单轮运行开始时冻结的 Agent 运行范围。 */
public class AgentRuntimeScope {
    /** 默认Agent的统一常量值。 */
    public static final String DEFAULT_AGENT = "default";

    /** 记录Agent运行时范围中的Agent名称。 */
    private String agentName = DEFAULT_AGENT;

    /** 记录Agent运行时范围中的展示名称。 */
    private String displayName = "默认 Agent";

    /** 记录Agent运行时范围中的描述。 */
    private String description;

    /** 是否启用默认Agent。 */
    private boolean defaultAgent = true;

    /** 记录Agent运行时范围中的角色提示词。 */
    private String rolePrompt;

    /** 记录Agent运行时范围中的默认模型。 */
    private String defaultModel;

    /** 记录Agent运行时范围中的允许工具 JSON。 */
    private String allowedToolsJson = "[]";

    /** 记录Agent运行时范围中的技能 JSON。 */
    private String skillsJson = "[]";

    /** 记录Agent运行时范围中的记忆。 */
    private String memory;

    /** 记录Agent运行时范围中的Agent主渠道目录。 */
    private String agentHomeDir;

    /** 记录Agent运行时范围中的工作区目录。 */
    private String workspaceDir;

    /** 是否启用工作区目录Override。 */
    private boolean workspaceDirOverride;

    /** 记录Agent运行时范围中的技能目录。 */
    private String skillsDir;

    /** 记录Agent运行时范围中的缓存目录。 */
    private String cacheDir;

    /** 记录Agent运行时范围中的Agent文件路径。 */
    private String agentFilePath;

    /** 记录Agent运行时范围中的记忆文件路径。 */
    private String memoryFilePath;

    /** 记录Agent运行时范围中的快照 JSON。 */
    private String snapshotJson;

    /**
     * 规范化名称。
     *
     * @param name 名称参数。
     * @return 返回名称结果。
     */
    public static String normalizeName(String name) {
        String normalized = StrUtil.nullToEmpty(name).trim();
        if (StrUtil.isBlank(normalized) || DEFAULT_AGENT.equalsIgnoreCase(normalized)) {
            return DEFAULT_AGENT;
        }
        return normalized;
    }

    /**
     * 判断是否默认Agent名称。
     *
     * @return 如果默认Agent名称满足条件则返回 true，否则返回 false。
     */
    public boolean isDefaultAgentName() {
        return defaultAgent || DEFAULT_AGENT.equalsIgnoreCase(agentName);
    }

    /**
     * 读取生效名称。
     *
     * @return 返回读取到的生效名称。
     */
    public String getEffectiveName() {
        return normalizeName(agentName);
    }

    /**
     * 读取Agent名称。
     *
     * @return 返回读取到的Agent名称。
     */
    public String getAgentName() {
        return agentName;
    }

    /**
     * 写入Agent名称。
     *
     * @param agentName Agent名称参数。
     */
    public void setAgentName(String agentName) {
        this.agentName = normalizeName(agentName);
    }

    /**
     * 读取展示名称。
     *
     * @return 返回读取到的展示名称。
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * 写入展示名称。
     *
     * @param displayName 展示名称参数。
     */
    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    /**
     * 读取Description。
     *
     * @return 返回读取到的Description。
     */
    public String getDescription() {
        return description;
    }

    /**
     * 写入Description。
     *
     * @param description 描述参数。
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * 判断是否默认Agent。
     *
     * @return 如果默认Agent满足条件则返回 true，否则返回 false。
     */
    public boolean isDefaultAgent() {
        return defaultAgent;
    }

    /**
     * 写入默认Agent。
     *
     * @param defaultAgent 默认Agent参数。
     */
    public void setDefaultAgent(boolean defaultAgent) {
        this.defaultAgent = defaultAgent;
    }

    /**
     * 读取Role提示词。
     *
     * @return 返回读取到的Role提示词。
     */
    public String getRolePrompt() {
        return rolePrompt;
    }

    /**
     * 写入Role提示词。
     *
     * @param rolePrompt role提示词参数。
     */
    public void setRolePrompt(String rolePrompt) {
        this.rolePrompt = rolePrompt;
    }

    /**
     * 读取默认模型。
     *
     * @return 返回读取到的默认模型。
     */
    public String getDefaultModel() {
        return defaultModel;
    }

    /**
     * 写入默认模型。
     *
     * @param defaultModel 默认模型参数。
     */
    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    /**
     * 读取Allowed工具JSON。
     *
     * @return 返回读取到的Allowed工具JSON。
     */
    public String getAllowedToolsJson() {
        return allowedToolsJson;
    }

    /**
     * 写入Allowed工具JSON。
     *
     * @param allowedToolsJson allowedToolsJSON开关值。
     */
    public void setAllowedToolsJson(String allowedToolsJson) {
        this.allowedToolsJson = allowedToolsJson;
    }

    /**
     * 读取技能 JSON。
     *
     * @return 返回读取到的技能 JSON。
     */
    public String getSkillsJson() {
        return skillsJson;
    }

    /**
     * 写入技能 JSON。
     *
     * @param skillsJson 技能 JSON参数。
     */
    public void setSkillsJson(String skillsJson) {
        this.skillsJson = skillsJson;
    }

    /**
     * 读取记忆。
     *
     * @return 返回读取到的记忆。
     */
    public String getMemory() {
        return memory;
    }

    /**
     * 写入记忆。
     *
     * @param memory 记忆参数。
     */
    public void setMemory(String memory) {
        this.memory = memory;
    }

    /**
     * 读取Agent主渠道Dir。
     *
     * @return 返回读取到的Agent主渠道Dir。
     */
    public String getAgentHomeDir() {
        return agentHomeDir;
    }

    /**
     * 写入Agent主渠道Dir。
     *
     * @param agentHomeDir 文件或目录路径参数。
     */
    public void setAgentHomeDir(String agentHomeDir) {
        this.agentHomeDir = agentHomeDir;
    }

    /**
     * 读取工作区Dir。
     *
     * @return 返回读取到的工作区Dir。
     */
    public String getWorkspaceDir() {
        return workspaceDir;
    }

    /**
     * 写入工作区Dir。
     *
     * @param workspaceDir 文件或目录路径参数。
     */
    public void setWorkspaceDir(String workspaceDir) {
        this.workspaceDir = workspaceDir;
    }

    /**
     * 判断是否工作区Dir Override。
     *
     * @return 如果工作区Dir Override满足条件则返回 true，否则返回 false。
     */
    public boolean isWorkspaceDirOverride() {
        return workspaceDirOverride;
    }

    /**
     * 写入工作区Dir Override。
     *
     * @param workspaceDirOverride 文件或目录路径参数。
     */
    public void setWorkspaceDirOverride(boolean workspaceDirOverride) {
        this.workspaceDirOverride = workspaceDirOverride;
    }

    /**
     * 读取技能Dir。
     *
     * @return 返回读取到的技能Dir。
     */
    public String getSkillsDir() {
        return skillsDir;
    }

    /**
     * 写入技能Dir。
     *
     * @param skillsDir 文件或目录路径参数。
     */
    public void setSkillsDir(String skillsDir) {
        this.skillsDir = skillsDir;
    }

    /**
     * 读取缓存Dir。
     *
     * @return 返回读取到的缓存Dir。
     */
    public String getCacheDir() {
        return cacheDir;
    }

    /**
     * 写入缓存Dir。
     *
     * @param cacheDir 文件或目录路径参数。
     */
    public void setCacheDir(String cacheDir) {
        this.cacheDir = cacheDir;
    }

    /**
     * 读取Agent文件路径。
     *
     * @return 返回读取到的Agent文件路径。
     */
    public String getAgentFilePath() {
        return agentFilePath;
    }

    /**
     * 写入Agent文件路径。
     *
     * @param agentFilePath 文件或目录路径参数。
     */
    public void setAgentFilePath(String agentFilePath) {
        this.agentFilePath = agentFilePath;
    }

    /**
     * 读取记忆文件路径。
     *
     * @return 返回读取到的记忆文件路径。
     */
    public String getMemoryFilePath() {
        return memoryFilePath;
    }

    /**
     * 写入记忆文件路径。
     *
     * @param memoryFilePath 文件或目录路径参数。
     */
    public void setMemoryFilePath(String memoryFilePath) {
        this.memoryFilePath = memoryFilePath;
    }

    /**
     * 读取Snapshot JSON。
     *
     * @return 返回读取到的Snapshot JSON。
     */
    public String getSnapshotJson() {
        return snapshotJson;
    }

    /**
     * 写入Snapshot JSON。
     *
     * @param snapshotJson snapshotJSON参数。
     */
    public void setSnapshotJson(String snapshotJson) {
        this.snapshotJson = snapshotJson;
    }
}
