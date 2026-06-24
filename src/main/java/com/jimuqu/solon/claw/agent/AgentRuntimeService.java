package com.jimuqu.solon.claw.agent;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import org.noear.snack4.ONode;

/** 解析会话当前激活 Agent，并在单轮运行开始前冻结目录、技能、记忆与模型配置。 */
public class AgentRuntimeService {
    /** 自定义 Agent 名称允许的字符范围，避免把路径片段写入运行目录。 */
    private static final String VALID_NAME_PATTERN = "^[a-zA-Z0-9][a-zA-Z0-9._-]*$";

    /** 应用配置提供 runtime 根目录、技能目录和缓存目录等默认路径。 */
    private final AppConfig appConfig;

    /** Agent 角色仓储，负责读取和保存用户创建的命名 Agent 配置。 */
    private final AgentProfileRepository repository;

    /**
     * 创建 Agent 运行时服务实例。
     *
     * @param appConfig 应用运行配置，提供默认 runtime 路径。
     * @param repository Agent 角色配置仓储。
     */
    public AgentRuntimeService(AppConfig appConfig, AgentProfileRepository repository) {
        this.appConfig = appConfig;
        this.repository = repository;
    }

    /**
     * 按会话激活 Agent 名称解析本轮运行范围。
     *
     * @param session 当前会话记录；为空时使用默认 Agent。
     * @return 返回冻结后的运行范围。
     */
    public AgentRuntimeScope resolve(SessionRecord session) throws Exception {
        String active = session == null ? null : session.getActiveAgentName();
        return resolveByName(active);
    }

    /**
     * 按名称解析 Agent 运行范围，命名 Agent 会检查启用状态并初始化运行目录。
     *
     * @param rawName 用户输入或会话中保存的 Agent 名称。
     * @return 返回冻结后的运行范围。
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
     * 构建默认 Agent 运行范围，默认工作区来自 solonclaw.workspace。
     *
     * @return 返回默认 Agent 的运行范围快照。
     */
    public AgentRuntimeScope defaultScope() {
        AgentRuntimeScope scope = new AgentRuntimeScope();
        scope.setAgentName(AgentRuntimeScope.DEFAULT_AGENT);
        scope.setDisplayName("默认 Agent");
        scope.setDescription("映射默认工作区的行为");
        scope.setAgentHomeDir(appConfig.getRuntime().getHome());
        scope.setWorkspaceDir(appConfig.getWorkspace().getDir());
        scope.setSkillsDir(appConfig.getRuntime().getSkillsDir());
        scope.setCacheDir(appConfig.getRuntime().getCacheDir());
        scope.setAllowedToolsJson("[]");
        scope.setSkillsJson("[]");
        scope.setSnapshotJson(toSnapshot(scope));
        return scope;
    }

    /**
     * 创建命名 Agent 角色配置，并初始化该 Agent 的运行目录。
     *
     * @param name Agent 名称。
     * @param rolePrompt 该 Agent 的默认角色提示词，空值时使用通用任务 Agent 提示。
     * @return 返回已存在或新保存的 Agent 配置。
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
     * 保存命名 Agent 配置，并确保该 Agent 的目录结构已经存在。
     *
     * @param profile 待保存的 Agent 配置。
     * @return 返回仓储持久化后的 Agent 配置。
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
     * 删除命名 Agent 配置；目录清理交由后续维护任务处理，避免误删用户工作区文件。
     *
     * @param name 待删除的 Agent 名称。
     */
    public void delete(String name) throws Exception {
        String normalized = normalizeName(name);
        rejectDefault(normalized);
        repository.deleteByName(normalized);
    }

    /**
     * 记录命名 Agent 最近一次被会话使用的时间。
     *
     * @param name Agent 名称；默认 Agent 不写入角色仓储。
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
     * 计算命名 Agent 的根目录。
     *
     * @param name Agent 名称。
     * @return 返回 runtime/agents/{name} 对应目录。
     */
    public File agentRoot(String name) {
        return FileUtil.file(appConfig.getRuntime().getHome(), "agents", normalizeName(name));
    }

    /**
     * 规范化 Agent 名称，空值和 default 都映射为默认 Agent。
     *
     * @return 返回运行时可识别的 Agent 名称。
     */
    public String normalizeName(String name) {
        return AgentRuntimeScope.normalizeName(name);
    }

    /**
     * 校验命名 Agent 名称，防止路径穿越和不稳定文件名进入 runtime 目录。
     *
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
     * 拒绝对内置默认 Agent 执行创建、编辑、删除或克隆类操作。
     *
     * @param name 待检查的 Agent 名称。
     */
    public void rejectDefault(String name) {
        if (AgentRuntimeScope.DEFAULT_AGENT.equalsIgnoreCase(normalizeName(name))) {
            throw new IllegalArgumentException("default 是内置 Agent，映射 runtime 根目录，不允许创建、编辑、删除或克隆。");
        }
    }

    /**
     * 构建命名 Agent 的运行范围，并补齐 workspace、skills、cache 目录。
     *
     * @param profile 已启用的 Agent 配置。
     * @return 返回该 Agent 的运行范围快照。
     */
    private AgentRuntimeScope namedScope(AgentProfile profile) {
        AgentDirectories dirs = namedDirectories(profile.getAgentName(), true);

        AgentRuntimeScope scope = new AgentRuntimeScope();
        scope.setAgentName(profile.getAgentName());
        scope.setDisplayName(
                StrUtil.blankToDefault(profile.getDisplayName(), profile.getAgentName()));
        scope.setDescription(profile.getDescription());
        scope.setRolePrompt(profile.getRolePrompt());
        scope.setDefaultModel(profile.getDefaultModel());
        scope.setAllowedToolsJson(StrUtil.blankToDefault(profile.getAllowedToolsJson(), "[]"));
        scope.setSkillsJson(StrUtil.blankToDefault(profile.getSkillsJson(), "[]"));
        scope.setMemory(profile.getMemory());
        scope.setAgentHomeDir(dirs.root.getAbsolutePath());
        scope.setWorkspaceDir(dirs.workspace.getAbsolutePath());
        scope.setSkillsDir(dirs.skills.getAbsolutePath());
        scope.setCacheDir(dirs.cache.getAbsolutePath());
        scope.setAgentFilePath(dirs.agentFile.getAbsolutePath());
        scope.setMemoryFilePath(dirs.memoryFile.getAbsolutePath());
        return scope;
    }

    /**
     * 确保命名 Agent 的运行目录三件套存在。
     *
     * @param name Agent 名称。
     */
    private void ensureNamedDirs(String name) {
        namedDirectories(name, true);
    }

    /**
     * 序列化运行范围快照，供会话恢复和诊断展示使用。
     *
     * @param scope 已冻结的 Agent 运行范围。
     * @return 返回 JSON 快照文本。
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
     * 把本地绝对路径转换为稳定的 agent:// 引用，避免快照暴露机器目录结构。
     *
     * @param scope 已冻结的 Agent 运行范围。
     * @param child Agent 根目录下的相对子路径。
     * @return 返回 agent:// 形式的引用。
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

    /**
     * 计算命名 Agent 的标准目录结构，可按需创建目录。
     *
     * @param name Agent 名称。
     * @param createMissing 是否创建 workspace、skills、cache 目录。
     * @return 返回标准目录结构。
     */
    private AgentDirectories namedDirectories(String name, boolean createMissing) {
        File root = agentRoot(name);
        AgentDirectories dirs =
                new AgentDirectories(
                        root,
                        FileUtil.file(root, "workspace"),
                        FileUtil.file(root, "skills"),
                        FileUtil.file(root, "cache"),
                        FileUtil.file(root, "AGENT.md"),
                        FileUtil.file(root, "MEMORY.md"));
        if (createMissing) {
            FileUtil.mkdir(dirs.workspace);
            FileUtil.mkdir(dirs.skills);
            FileUtil.mkdir(dirs.cache);
        }
        return dirs;
    }

    /** 命名 Agent 的固定目录布局，集中描述避免不同路径拼装逻辑漂移。 */
    private static class AgentDirectories {
        /** Agent 根目录，对应 runtime/agents/{name}。 */
        private final File root;

        /** Agent 专属工作区目录，用于隔离文件读写上下文。 */
        private final File workspace;

        /** Agent 专属技能目录，用于放置该 Agent 可召回的本地技能。 */
        private final File skills;

        /** Agent 专属缓存目录，用于保存运行时临时文件。 */
        private final File cache;

        /** Agent 说明文件路径，用于后续补充角色级上下文。 */
        private final File agentFile;

        /** Agent 记忆文件路径，用于后续补充角色级长期记忆。 */
        private final File memoryFile;

        /**
         * 创建目录布局值对象。
         *
         * @param root Agent 根目录。
         * @param workspace Agent 工作区目录。
         * @param skills Agent 技能目录。
         * @param cache Agent 缓存目录。
         * @param agentFile Agent 说明文件路径。
         * @param memoryFile Agent 记忆文件路径。
         */
        private AgentDirectories(
                File root,
                File workspace,
                File skills,
                File cache,
                File agentFile,
                File memoryFile) {
            this.root = root;
            this.workspace = workspace;
            this.skills = skills;
            this.cache = cache;
            this.agentFile = agentFile;
            this.memoryFile = memoryFile;
        }
    }
}
