package com.jimuqu.solon.claw.context;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.agent.AgentRuntimeScope;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.repository.GlobalSettingRepository;
import com.jimuqu.solon.claw.core.service.ContextService;
import com.jimuqu.solon.claw.core.service.MemoryManager;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.AgentSettingConstants;
import com.jimuqu.solon.claw.support.constants.ContextFileConstants;

/** 基于文件系统拼装系统提示词的上下文服务。 */
public class FileContextService implements ContextService {
    /** 单个静态上下文块的默认字符上限，避免单一文件挤占系统提示词。 */
    private static final int DEFAULT_BOOTSTRAP_PROMPT_FILE_CHAR_LIMIT = 12000;

    /** 静态 bootstrap 提示词的默认总字符预算，独立于工具输出预算。 */
    private static final int DEFAULT_BOOTSTRAP_PROMPT_TOTAL_CHAR_BUDGET = 48000;

    /** 单文件截断时写入的可见标记。 */
    private static final String FILE_TRUNCATION_MARKER = "\n[TRUNCATED: per-file character limit]";

    /** 总预算耗尽时写入的可见标记。 */
    private static final String TOTAL_TRUNCATION_MARKER =
            "\n[TRUNCATED: bootstrap prompt total budget]";

    /** 应用配置。 */
    private final AppConfig appConfig;

    /** 本地技能服务。 */
    private final LocalSkillService localSkillService;

    /** 长期记忆服务。 */
    private final MemoryManager memoryManager;

    /** 全局设置仓储。 */
    private final GlobalSettingRepository globalSettingRepository;

    /** 注入persona工作区服务，用于调用对应业务能力。 */
    private final PersonaWorkspaceService personaWorkspaceService;

    /** 构造文件上下文服务。 */
    public FileContextService(
            AppConfig appConfig,
            LocalSkillService localSkillService,
            MemoryManager memoryManager,
            GlobalSettingRepository globalSettingRepository,
            PersonaWorkspaceService personaWorkspaceService) {
        this.appConfig = appConfig;
        this.localSkillService = localSkillService;
        this.memoryManager = memoryManager;
        this.globalSettingRepository = globalSettingRepository;
        this.personaWorkspaceService = personaWorkspaceService;
        FileUtil.mkdir(appConfig.getRuntime().getContextDir());
    }

    /**
     * 组合 AGENTS、MEMORY、USER 与已启用技能内容。
     *
     * @param sourceKey 来源键
     * @return 系统提示词
     */
    @Override
    public String buildSystemPrompt(String sourceKey) {
        return buildSystemPrompt(sourceKey, null);
    }

    /**
     * 构建System提示词。
     *
     * @param sourceKey 渠道来源键。
     * @param agentScope 当前运行冻结后的 Agent 范围。
     * @return 返回创建好的System提示词。
     */
    @Override
    public String buildSystemPrompt(String sourceKey, AgentRuntimeScope agentScope) {
        StringBuilder buffer = new StringBuilder();
        // AGENTS 先于可变记忆注入，确保当前工作区规则在预算不足时仍被优先保留。
        appendWorkspaceFile(buffer, ContextFileConstants.KEY_AGENTS, "Workspace Rules");
        appendProjectContextFiles(buffer, agentScope);
        appendWorkspaceFile(buffer, ContextFileConstants.KEY_SOUL, "Soul");
        appendWorkspaceFile(buffer, ContextFileConstants.KEY_TOOLS, "Tools");
        appendWorkspaceFile(buffer, ContextFileConstants.KEY_IDENTITY, "Identity");
        appendWorkspaceFile(buffer, ContextFileConstants.KEY_USER, "User");
        appendPersonality(buffer);
        appendAgentBlock(buffer, agentScope);
        appendMemoryBlock(buffer, sourceKey);

        try {
            String skillPrompt =
                    agentScope == null
                            ? localSkillService.renderSkillIndexPrompt(sourceKey)
                            : localSkillService.renderSkillIndexPrompt(sourceKey, agentScope);
            if (StrUtil.isNotBlank(skillPrompt)) {
                appendBlock(buffer, "Enabled Skills", skillPrompt);
            }
        } catch (Exception e) {
            appendBlock(buffer, "Enabled Skills", "Failed to load local skills: " + safeError(e));
        }

        return truncateToTotalBudget(buffer.toString());
    }

    /**
     * 追加Agent 块。
     *
     * @param buffer 系统提示词缓冲区。
     * @param agentScope 当前运行冻结后的 Agent 范围。
     */
    private void appendAgentBlock(StringBuilder buffer, AgentRuntimeScope agentScope) {
        if (agentScope == null || agentScope.isDefaultAgentName()) {
            return;
        }
        appendBlock(
                buffer,
                "Agent",
                "name="
                        + agentScope.getEffectiveName()
                        + "\nworkspace="
                        + StrUtil.nullToEmpty(agentScope.getWorkspaceDir()));
        appendBlock(buffer, "Agent Role", agentScope.getRolePrompt());
        appendBlock(buffer, "Agent File", readIfExists(agentScope.getAgentFilePath()));
        appendBlock(
                buffer,
                "Agent Memory",
                joinNonBlank(agentScope.getMemory(), readIfExists(agentScope.getMemoryFilePath())));
    }

    /**
     * 追加Project上下文Files。
     *
     * @param buffer 系统提示词缓冲区。
     * @param agentScope 当前运行冻结后的 Agent 范围。
     */
    private void appendProjectContextFiles(StringBuilder buffer, AgentRuntimeScope agentScope) {
        if (agentScope == null || !agentScope.isWorkspaceDirOverride()) {
            return;
        }
        String workspaceDir = agentScope.getWorkspaceDir();
        if (StrUtil.isBlank(workspaceDir)) {
            return;
        }
        appendProjectFile(buffer, workspaceDir, "AGENTS.md", "Project AGENTS.md");
        appendProjectFile(buffer, workspaceDir, "CLAUDE.md", "Project CLAUDE.md");
        appendProjectFile(buffer, workspaceDir, ".cursorrules", "Project .cursorrules");
    }

    /**
     * 追加Project文件。
     *
     * @param buffer 系统提示词缓冲区。
     * @param workspaceDir 文件或目录路径参数。
     * @param fileName 文件或目录路径参数。
     * @param label label 参数。
     */
    private void appendProjectFile(
            StringBuilder buffer, String workspaceDir, String fileName, String label) {
        java.io.File file = FileUtil.file(workspaceDir, fileName);
        if (!file.exists() || !file.isFile()) {
            return;
        }
        appendBlock(buffer, label, readIfExists(file.getAbsolutePath()));
    }

    /**
     * 读取If Exists。
     *
     * @param path 文件或目录路径。
     * @return 返回读取到的If Exists。
     */
    private String readIfExists(String path) {
        if (StrUtil.isBlank(path)) {
            return "";
        }
        try {
            java.io.File file = FileUtil.file(path);
            return file.exists() && file.isFile() ? FileUtil.readUtf8String(file) : "";
        } catch (Exception e) {
            return "Failed to load file: " + safeError(e);
        }
    }

    /**
     * 执行joinNon空白值相关逻辑。
     *
     * @param left 左侧比较对象。
     * @param right 右侧比较对象。
     * @return 返回join Non Blank结果。
     */
    private String joinNonBlank(String left, String right) {
        if (StrUtil.isBlank(left)) {
            return StrUtil.nullToEmpty(right);
        }
        if (StrUtil.isBlank(right)) {
            return StrUtil.nullToEmpty(left);
        }
        return left.trim() + "\n\n" + right.trim();
    }

    /** 计算运行时上下文文件路径。 */
    private void appendPersonality(StringBuilder buffer) {
        try {
            String active =
                    globalSettingRepository == null
                            ? null
                            : globalSettingRepository.get(AgentSettingConstants.ACTIVE_PERSONALITY);
            if (StrUtil.isBlank(active)) {
                return;
            }
            AppConfig.PersonalityConfig personality =
                    appConfig.getAgent().getPersonalities().get(active);
            if (personality == null) {
                return;
            }
            String personalityPrompt = personality.toPrompt();
            if (StrUtil.isBlank(personalityPrompt)) {
                return;
            }
            appendBlock(buffer, "Personality: " + active, personalityPrompt);
        } catch (Exception e) {
            appendBlock(
                    buffer, "Personality", "Failed to load active personality: " + safeError(e));
        }
    }

    /** 追加记忆管理器提供的系统提示块。 */
    private void appendMemoryBlock(StringBuilder buffer, String sourceKey) {
        try {
            appendBlock(
                    buffer,
                    "Memory Manager",
                    memoryManager == null ? "" : memoryManager.buildSystemPrompt(sourceKey));
        } catch (Exception e) {
            appendBlock(buffer, "Memory Manager", "Failed to load memory context: " + safeError(e));
        }
    }

    /** 按优先级追加上下文文件内容。 */
    private void appendWorkspaceFile(StringBuilder buffer, String key, String label) {
        String content = personaWorkspaceService.readPromptBody(key);
        if (StrUtil.isBlank(content)) {
            return;
        }
        appendBlock(buffer, label, content);
    }

    /** 获取单个静态上下文块的字符上限。 */
    private int bootstrapPromptFileCharLimit() {
        int value = appConfig.getTask().getBootstrapPromptFileCharLimit();
        return value > 0 ? value : DEFAULT_BOOTSTRAP_PROMPT_FILE_CHAR_LIMIT;
    }

    /** 获取静态 bootstrap 提示词的总字符预算。 */
    private int bootstrapPromptTotalCharBudget() {
        int value = appConfig.getTask().getBootstrapPromptTotalCharBudget();
        return value > 0 ? value : DEFAULT_BOOTSTRAP_PROMPT_TOTAL_CHAR_BUDGET;
    }

    /** 追加指定文本块，并在单文件字符预算内保留截断标记。 */
    private void appendBlock(StringBuilder buffer, String label, String content) {
        if (StrUtil.isBlank(content)) {
            return;
        }
        String normalized = content.trim();
        int limit = bootstrapPromptFileCharLimit();
        if (normalized.length() > limit) {
            int contentLength = Math.max(0, limit - FILE_TRUNCATION_MARKER.length());
            normalized =
                    normalized.substring(0, contentLength)
                            + FILE_TRUNCATION_MARKER.substring(
                                    0, Math.min(limit, FILE_TRUNCATION_MARKER.length()));
        }
        if (buffer.length() > 0) {
            buffer.append("\n\n");
        }
        buffer.append("[").append(label).append("]\n").append(normalized);
    }

    /** 对完整 bootstrap 提示词应用独立总字符预算，并保留截断标记。 */
    private String truncateToTotalBudget(String prompt) {
        String normalized = StrUtil.nullToEmpty(prompt).trim();
        int limit = bootstrapPromptTotalCharBudget();
        if (normalized.length() <= limit) {
            return normalized;
        }
        String retainedMarker =
                normalized.contains(FILE_TRUNCATION_MARKER) ? FILE_TRUNCATION_MARKER : "";
        int markerLength = retainedMarker.length() + TOTAL_TRUNCATION_MARKER.length();
        if (limit < markerLength) {
            return TOTAL_TRUNCATION_MARKER.substring(0, limit);
        }
        int contentLength = limit - markerLength;
        return normalized.substring(0, contentLength) + retainedMarker + TOTAL_TRUNCATION_MARKER;
    }

    /**
     * 将异常转换为可展示且不泄漏敏感信息的错误文本。
     *
     * @param e 捕获到的异常。
     * @return 返回safe Error结果。
     */
    private String safeError(Exception e) {
        return SecretRedactor.redact(
                StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()), 1000);
    }
}
