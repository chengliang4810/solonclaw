package com.jimuqu.solon.claw.agent;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import org.noear.snack4.ONode;

/** 解析会话当前激活 Agent，并冻结运行路径与角色配置。 */
public class AgentRuntimeService {
    /** VALID名称正则的统一常量值。 */
    private static final String VALID_NAME_PATTERN = "^[a-zA-Z0-9][a-zA-Z0-9._-]*$";

    /** 注入应用配置，用于Agent运行时。 */
    private final AppConfig appConfig;

    /** 保存仓储依赖，用于访问持久化数据。 */
    private final AgentProfileRepository repository;

    /**
     * 创建Agent运行时服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param repository repository依赖组件。
     */
    public AgentRuntimeService(AppConfig appConfig, AgentProfileRepository repository) {
        this.appConfig = appConfig;
        this.repository = repository;
    }

    /**
     * 解析运行时需要的目标对象。
     *
     * @param session 会话参数。
     * @return 返回resolve结果。
     */
    public AgentRuntimeScope resolve(SessionRecord session) throws Exception {
        String active = session == null ? null : session.getActiveAgentName();
        return resolveByName(active);
    }

    /**
     * 解析根据名称。
     *
     * @param rawName 原始名称参数。
     * @return 返回解析后的根据名称。
     */
    public AgentRuntimeScope resolveByName(String rawName) throws Exception {
        String name = normalizeName(rawName);
        if (AgentRuntimeScope.DEFAULT_AGENT.equals(name)) {
            return defaultScope();
        }

        AgentProfile profile = repository.findByName(name);
        if (profile == null) {
            throw new IllegalStateException("未找到 Agent：" + name);
        }
        if (!profile.isEnabled()) {
            throw new IllegalStateException("Agent 已停用：" + name);
        }
        AgentRuntimeScope scope = namedScope(profile);
        scope.setSnapshotJson(toSnapshot(scope));
        return scope;
    }

    /**
     * 执行默认范围相关逻辑。
     *
     * @return 返回默认范围结果。
     */
    public AgentRuntimeScope defaultScope() {
        AgentRuntimeScope scope = new AgentRuntimeScope();
        scope.setAgentName(AgentRuntimeScope.DEFAULT_AGENT);
        scope.setDisplayName("默认 Agent");
        scope.setDescription("映射 runtime 根目录的默认行为");
        scope.setDefaultAgent(true);
        scope.setAgentHomeDir(appConfig.getRuntime().getHome());
        scope.setWorkspaceDir(appConfig.getRuntime().getHome());
        scope.setSkillsDir(appConfig.getRuntime().getSkillsDir());
        scope.setCacheDir(appConfig.getRuntime().getCacheDir());
        scope.setAllowedToolsJson("[]");
        scope.setSkillsJson("[]");
        scope.setSnapshotJson(toSnapshot(scope));
        return scope;
    }

    /**
     * 执行create，服务于Agent运行时主流程相关逻辑。
     *
     * @param name 名称参数。
     * @param rolePrompt role提示词参数。
     * @return 返回create结果。
     */
    public AgentProfile create(String name, String rolePrompt) throws Exception {
        validateName(name);
        String normalized = normalizeName(name);
        rejectDefault(normalized);
        AgentProfile existing = repository.findByName(normalized);
        if (existing != null) {
            return existing;
        }

        long now = System.currentTimeMillis();
        AgentProfile profile = new AgentProfile();
        profile.setAgentName(normalized);
        profile.setDisplayName(normalized);
        profile.setDescription("");
        profile.setRolePrompt(StrUtil.blankToDefault(rolePrompt, "你是一个可复用的任务 Agent。"));
        profile.setDefaultModel("");
        profile.setAllowedToolsJson("[]");
        profile.setSkillsJson("[]");
        profile.setMemory("");
        profile.setEnabled(true);
        profile.setCreatedAt(now);
        profile.setUpdatedAt(now);
        AgentProfile saved = repository.save(profile);
        ensureNamedDirs(normalized);
        return saved;
    }

    /**
     * 执行save，服务于Agent运行时主流程相关逻辑。
     *
     * @param profile 文件或目录路径参数。
     * @return 返回save结果。
     */
    public AgentProfile save(AgentProfile profile) throws Exception {
        validateName(profile.getAgentName());
        rejectDefault(profile.getAgentName());
        long now = System.currentTimeMillis();
        if (profile.getCreatedAt() <= 0) {
            profile.setCreatedAt(now);
        }
        profile.setUpdatedAt(now);
        profile.setEnabled(profile.isEnabled());
        AgentProfile saved = repository.save(profile);
        ensureNamedDirs(saved.getAgentName());
        return saved;
    }

    /**
     * 执行delete，服务于Agent运行时主流程相关逻辑。
     *
     * @param name 名称参数。
     */
    public void delete(String name) throws Exception {
        String normalized = normalizeName(name);
        rejectDefault(normalized);
        repository.deleteByName(normalized);
    }

    /**
     * 标记使用。
     *
     * @param name 名称参数。
     */
    public void markUsed(String name) throws Exception {
        String normalized = normalizeName(name);
        if (AgentRuntimeScope.DEFAULT_AGENT.equals(normalized)) {
            return;
        }
        AgentProfile profile = repository.findByName(normalized);
        if (profile == null) {
            return;
        }
        profile.setLastUsedAt(System.currentTimeMillis());
        profile.setUpdatedAt(System.currentTimeMillis());
        repository.save(profile);
    }

    /**
     * 执行Agent根用户相关逻辑。
     *
     * @param name 名称参数。
     * @return 返回Agent根用户结果。
     */
    public File agentRoot(String name) {
        return FileUtil.file(appConfig.getRuntime().getHome(), "agents", normalizeName(name));
    }

    /**
     * 规范化名称。
     *
     * @param name 名称参数。
     * @return 返回名称结果。
     */
    public String normalizeName(String name) {
        return AgentRuntimeScope.normalizeName(name);
    }

    /**
     * 校验名称。
     *
     * @param name 名称参数。
     */
    public void validateName(String name) {
        String normalized = normalizeName(name);
        if (AgentRuntimeScope.DEFAULT_AGENT.equals(normalized)) {
            return;
        }
        if (!normalized.matches(VALID_NAME_PATTERN)) {
            throw new IllegalArgumentException("Agent 名称只能包含字母、数字、点、下划线和短横线，且必须以字母或数字开头。");
        }
        if (normalized.contains("/") || normalized.contains("\\") || normalized.contains("..")) {
            throw new IllegalArgumentException("Agent 名称不能包含路径片段。");
        }
    }

    /**
     * 执行reject默认相关逻辑。
     *
     * @param name 名称参数。
     */
    public void rejectDefault(String name) {
        if (AgentRuntimeScope.DEFAULT_AGENT.equalsIgnoreCase(normalizeName(name))) {
            throw new IllegalArgumentException("default 是内置 Agent，映射 runtime 根目录，不允许创建、编辑、删除或克隆。");
        }
    }

    /**
     * 执行指定名称范围相关逻辑。
     *
     * @param profile 文件或目录路径参数。
     * @return 返回指定名称范围结果。
     */
    private AgentRuntimeScope namedScope(AgentProfile profile) {
        File root = agentRoot(profile.getAgentName());
        File workspace = FileUtil.file(root, "workspace");
        File skills = FileUtil.file(root, "skills");
        File cache = FileUtil.file(root, "cache");
        File agentFile = FileUtil.file(root, "AGENT.md");
        File memoryFile = FileUtil.file(root, "MEMORY.md");
        FileUtil.mkdir(workspace);
        FileUtil.mkdir(skills);
        FileUtil.mkdir(cache);

        AgentRuntimeScope scope = new AgentRuntimeScope();
        scope.setAgentName(profile.getAgentName());
        scope.setDisplayName(
                StrUtil.blankToDefault(profile.getDisplayName(), profile.getAgentName()));
        scope.setDescription(profile.getDescription());
        scope.setDefaultAgent(false);
        scope.setRolePrompt(profile.getRolePrompt());
        scope.setDefaultModel(profile.getDefaultModel());
        scope.setAllowedToolsJson(StrUtil.blankToDefault(profile.getAllowedToolsJson(), "[]"));
        scope.setSkillsJson(StrUtil.blankToDefault(profile.getSkillsJson(), "[]"));
        scope.setMemory(profile.getMemory());
        scope.setAgentHomeDir(root.getAbsolutePath());
        scope.setWorkspaceDir(workspace.getAbsolutePath());
        scope.setSkillsDir(skills.getAbsolutePath());
        scope.setCacheDir(cache.getAbsolutePath());
        scope.setAgentFilePath(agentFile.getAbsolutePath());
        scope.setMemoryFilePath(memoryFile.getAbsolutePath());
        return scope;
    }

    /**
     * 确保指定名称Dirs。
     *
     * @param name 名称参数。
     */
    private void ensureNamedDirs(String name) {
        File root = agentRoot(name);
        FileUtil.mkdir(FileUtil.file(root, "workspace"));
        FileUtil.mkdir(FileUtil.file(root, "skills"));
        FileUtil.mkdir(FileUtil.file(root, "cache"));
    }

    /**
     * 转换为Snapshot。
     *
     * @param scope scope 参数。
     * @return 返回转换后的Snapshot。
     */
    private String toSnapshot(AgentRuntimeScope scope) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("agent_name", scope.getEffectiveName());
        map.put("display_name", scope.getDisplayName());
        map.put("description", scope.getDescription());
        map.put("default_agent", Boolean.valueOf(scope.isDefaultAgentName()));
        map.put("role_prompt", scope.getRolePrompt());
        map.put("default_model", scope.getDefaultModel());
        map.put("allowed_tools_json", scope.getAllowedToolsJson());
        map.put("skills_json", scope.getSkillsJson());
        map.put("memory", scope.getMemory());
        map.put("agent_home_dir", reference(scope, ""));
        map.put("workspace_dir", reference(scope, "workspace"));
        map.put("skills_dir", reference(scope, "skills"));
        map.put("cache_dir", reference(scope, "cache"));
        map.put("agent_file_path", reference(scope, "AGENT.md"));
        map.put("memory_file_path", reference(scope, "MEMORY.md"));
        map.put("created_at", Long.valueOf(System.currentTimeMillis()));
        return ONode.serialize(map);
    }

    /**
     * 执行引用相关逻辑。
     *
     * @param scope scope 参数。
     * @param child child 参数。
     * @return 返回reference结果。
     */
    private String reference(AgentRuntimeScope scope, String child) {
        String base =
                "agent://"
                        + (scope == null
                                ? AgentRuntimeScope.DEFAULT_AGENT
                                : scope.getEffectiveName());
        if (StrUtil.isBlank(child)) {
            return base;
        }
        return base + "/" + child;
    }
}
