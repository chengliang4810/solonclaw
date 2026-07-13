package com.jimuqu.solon.claw.context;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.model.SkillDescriptor;
import com.jimuqu.solon.claw.core.model.SkillView;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.CheckpointService;
import com.jimuqu.solon.claw.core.service.LlmGateway;
import com.jimuqu.solon.claw.core.service.MemoryService;
import com.jimuqu.solon.claw.core.service.SkillLearningService;
import com.jimuqu.solon.claw.profile.ProfileRuntimeScope;
import com.jimuqu.solon.claw.skillhub.support.SkillFrontmatterSupport;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.support.BoundedExecutorFactory;
import com.jimuqu.solon.claw.support.IdSupport;
import com.jimuqu.solon.claw.support.MessageSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.TimeSupport;
import com.jimuqu.solon.claw.support.constants.CompressionConstants;
import com.jimuqu.solon.claw.support.constants.MemoryConstants;
import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 主回复后的异步学习闭环服务。 */
@RequiredArgsConstructor
public class AsyncSkillLearningService implements SkillLearningService {
    /** 记录异步技能学习关键路径中的可恢复异常，便于诊断学习闭环降级原因。 */
    private static final Logger log = LoggerFactory.getLogger(AsyncSkillLearningService.class);

    /** 注入应用配置，用于异步技能Learning。 */
    private final AppConfig appConfig;

    /** 保存会话仓储依赖，用于访问持久化数据。 */
    private final SessionRepository sessionRepository;

    /** 注入记忆服务，用于调用对应业务能力。 */
    private final MemoryService memoryService;

    /** 注入本地技能服务，用于调用对应业务能力。 */
    private final LocalSkillService localSkillService;

    /** 注入检查点服务，用于调用对应业务能力。 */
    private final CheckpointService checkpointService;

    /** 记录异步技能Learning中的大模型消息网关。 */
    private final LlmGateway llmGateway;

    /** 记录异步技能Learning中的数据库。 */
    private final SqliteDatabase database;

    /** 保存执行器服务执行组件，负责调度异步或定时任务。 */
    private final ExecutorService executorService =
            BoundedExecutorFactory.fixed("async-skill-learning", 1, 64);

    /** 保存auxiliary执行器服务执行组件，负责调度异步或定时任务。 */
    private final ExecutorService auxiliaryExecutorService =
            BoundedExecutorFactory.fixed("async-skill-auxiliary", 2, 16);

    /** 记录每个会话已被技能学习消费的工具消息数量，避免同一批工具结果在后续回复中重复学习。 */
    private final ConcurrentMap<String, Integer> consumedToolMessages =
            new ConcurrentHashMap<String, Integer>();

    /**
     * 创建Async技能Learning服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param sessionRepository 会话仓储依赖。
     * @param memoryService 记忆服务依赖。
     * @param localSkillService 本地技能服务依赖。
     * @param checkpointService checkpoint服务依赖。
     * @param llmGateway LLM网关参数。
     */
    public AsyncSkillLearningService(
            AppConfig appConfig,
            SessionRepository sessionRepository,
            MemoryService memoryService,
            LocalSkillService localSkillService,
            CheckpointService checkpointService,
            LlmGateway llmGateway) {
        this(
                appConfig,
                sessionRepository,
                memoryService,
                localSkillService,
                checkpointService,
                llmGateway,
                null);
    }

    /** 关闭当前组件持有的运行资源。 */
    public void shutdown() {
        executorService.shutdownNow();
        auxiliaryExecutorService.shutdownNow();
    }

    /**
     * 执行调度Post回复Learning相关逻辑。
     *
     * @param session 会话参数。
     * @param message 平台消息或错误消息。
     * @param reply 回复参数。
     */
    @Override
    public void schedulePostReplyLearning(
            final SessionRecord session, final GatewayMessage message, final GatewayReply reply)
            throws Exception {
        if (!appConfig.getLearning().isEnabled()
                || session == null
                || reply == null
                || reply.isError()) {
            return;
        }

        executorService.submit(
                ProfileRuntimeScope.capture(
                        new Runnable() {
                            /** 执行异步任务主体。 */
                            @Override
                            public void run() {
                                try {
                                    int toolMessages = countToolMessages(session);
                                    int unconsumedToolMessages =
                                            unconsumedToolMessages(session, toolMessages);
                                    boolean hasRecentCheckpoint =
                                            checkpointService.hasRecentCheckpoint(
                                                    message.sourceKey(),
                                                    session.getLastLearningAt());

                                    if (unconsumedToolMessages
                                                    < appConfig.getLearning().getToolCallThreshold()
                                            && !hasRecentCheckpoint) {
                                        return;
                                    }
                                    runLearning(
                                            session,
                                            message,
                                            toolMessages,
                                            unconsumedToolMessages,
                                            hasRecentCheckpoint);
                                } catch (Exception e) {
                                    log.warn(
                                            "Post-reply skill learning failed: sessionId={},"
                                                    + " sourceKey={}, error={}",
                                            session == null ? null : session.getSessionId(),
                                            message == null ? null : message.sourceKey(),
                                            e.toString());
                                }
                            }
                        }));
    }

    /**
     * 运行Learning。
     *
     * @param session 会话参数。
     * @param message 平台消息或错误消息。
     * @param toolMessages 工具Messages参数。
     * @param unconsumedToolMessages 本次尚未用于学习的工具消息数量。
     * @param hasRecentCheckpoint hasRecentCheckpoint 参数。
     */
    private void runLearning(
            SessionRecord session,
            GatewayMessage message,
            int toolMessages,
            int unconsumedToolMessages,
            boolean hasRecentCheckpoint)
            throws Exception {
        if (unconsumedToolMessages >= appConfig.getLearning().getToolCallThreshold()) {
            learnSkill(session, message, hasRecentCheckpoint);
        } else if (hasRecentCheckpoint) {
            learnSkill(session, message, true);
        }

        long learnedAt = System.currentTimeMillis();
        consumedToolMessages.put(session.getSessionId(), Integer.valueOf(toolMessages));
        session.setLastLearningAt(learnedAt);
        sessionRepository.setLastLearningAt(session.getSessionId(), learnedAt);
    }

    /** 计算当前会话中还未纳入学习闭环的工具消息数量。 */
    private int unconsumedToolMessages(SessionRecord session, int toolMessages) {
        String sessionId = session.getSessionId();
        if (StrUtil.isBlank(sessionId)) {
            return toolMessages;
        }
        Integer consumed =
                consumedToolMessages.computeIfAbsent(
                        sessionId,
                        key ->
                                Integer.valueOf(
                                        session.getLastLearningAt() > 0L ? toolMessages : 0));
        return Math.max(0, toolMessages - consumed.intValue());
    }

    /**
     * 执行learn技能相关逻辑。
     *
     * @param session 会话参数。
     * @param message 平台消息或错误消息。
     * @param hasRecentCheckpoint hasRecentCheckpoint 参数。
     */
    private void learnSkill(
            SessionRecord session, GatewayMessage message, boolean hasRecentCheckpoint)
            throws Exception {
        ImprovementDecision improvement =
                classifyImprovement(session, message, hasRecentCheckpoint);
        String decision = improvement.action;
        String skillName = StrUtil.blankToDefault(improvement.skillName, inferSkillName(session));
        if (!"no_change".equals(decision)
                && !"memory_only".equals(decision)
                && StrUtil.isBlank(skillName)) {
            writeImprovementReport(
                    session,
                    null,
                    "no_change",
                    "Skipped skill write because no reusable class-level skill name was produced.",
                    Collections.<String>emptyList(),
                    true);
            return;
        }
        if ("no_change".equals(decision)) {
            writeImprovementReport(
                    session,
                    null,
                    decision,
                    "Rubric decision: " + decision,
                    Collections.<String>emptyList(),
                    false);
            return;
        }
        if ("memory_only".equals(decision)) {
            String candidate = summarizeMemoryWithModel(session, message);
            if (StrUtil.isBlank(candidate)) {
                writeImprovementReport(
                        session,
                        null,
                        decision,
                        "No stable long-term memory candidate was produced; skipped.",
                        Collections.<String>emptyList(),
                        false);
                return;
            }
            String result;
            boolean written;
            try {
                result = memoryService.add(MemoryConstants.TARGET_MEMORY, candidate);
                written = StrUtil.contains(result, "已写入");
            } catch (Exception e) {
                result = "memory write failed: " + SecretRedactor.redact(e.toString(), 200);
                written = false;
            }
            writeImprovementReport(
                    session,
                    null,
                    decision,
                    "Long-term memory "
                            + (written ? "written: " : "skipped by memory rules: ")
                            + SecretRedactor.redact(result, 200),
                    written
                            ? Collections.singletonList(MemoryConstants.MEMORY_FILE_NAME)
                            : Collections.<String>emptyList(),
                    false);
            return;
        }
        SkillDescriptor descriptor = findSkill(skillName);
        if (descriptor != null
                && ("new_skill".equals(decision) || "update_loaded_skill".equals(decision))) {
            decision = "update_existing_skill";
        }
        if (descriptor == null) {
            localSkillService.createSkill(
                    skillName,
                    null,
                    buildSkillContent(skillName, session, message, hasRecentCheckpoint));
            writeImprovementReport(
                    session,
                    skillName,
                    "new_skill",
                    "Created a new skill after rubric/class-first evaluation.",
                    Collections.singletonList("SKILL.md"),
                    false);
        } else {
            patchExistingSkill(skillName, session, message, hasRecentCheckpoint);
            writeImprovementReport(
                    session,
                    skillName,
                    "update_existing_skill",
                    "Updated existing skill after rubric/class-first evaluation.",
                    Collections.singletonList("SKILL.md"),
                    false);
        }
    }

    /**
     * 执行classifyImprovement相关逻辑。
     *
     * @param session 会话参数。
     * @param message 平台消息或错误消息。
     * @param hasRecentCheckpoint hasRecentCheckpoint 参数。
     * @return 返回classify Improvement结果。
     */
    private ImprovementDecision classifyImprovement(
            SessionRecord session, GatewayMessage message, boolean hasRecentCheckpoint) {
        if (llmGateway == null) {
            return new ImprovementDecision(
                    hasRecentCheckpoint ? "update_existing_skill" : "new_skill",
                    inferSkillName(session));
        }
        try {
            SessionRecord rubricSession = new SessionRecord();
            rubricSession.setSessionId(
                    "skill-rubric-"
                            + StrUtil.blankToDefault(session.getSessionId(), "session")
                            + "-"
                            + System.currentTimeMillis());
            rubricSession.setSourceKey(session.getSourceKey());
            String userMessage =
                    "请判断本轮是否值得沉淀，并只输出以下格式之一：\n"
                            + "no_change\n"
                            + "memory_only\n"
                            + "new_skill|class-level-skill-name\n"
                            + "update_loaded_skill|existing-skill-name\n"
                            + "update_existing_skill|existing-skill-name\n"
                            + "技能名必须是可复用任务类别的英文小写名称，不能使用一次性请求、错误文本、日期或通用占位名。\n"
                            + "现有技能名："
                            + existingSkillNamesForPrompt()
                            + "\n"
                            + "用户请求："
                            + sanitizeLearningText(message == null ? "" : message.getText())
                            + "\n工具消息数量满足阈值，checkpoint="
                            + hasRecentCheckpoint
                            + "\n会话摘要："
                            + SecretRedactor.redact(
                                    sanitizeLearningText(session.getCompressedSummary()), 2000);
            LlmResult result =
                    callAuxiliaryChat(
                            rubricSession,
                            "你是 SolonClaw 的 self-improvement rubric 分类器。只输出指定格式。",
                            userMessage);
            String text = extractAssistantText(result).trim().toLowerCase();
            String firstLine = text.split("\\R", 2)[0].trim();
            String[] parts = firstLine.split("[|｜]", 2);
            String firstToken = parts[0].split("[\\s,，。:：]+", 2)[0];
            for (String candidate :
                    new String[] {
                        "no_change",
                        "new_skill",
                        "update_loaded_skill",
                        "update_existing_skill",
                        "memory_only"
                    }) {
                if (candidate.equals(firstToken) || candidate.equals(firstLine)) {
                    String proposedName =
                            parts.length > 1 ? normalizeLearningSkillName(parts[1]) : "";
                    return new ImprovementDecision(candidate, proposedName);
                }
            }
        } catch (Exception e) {
            log.debug(
                    "Skill improvement classification failed, using fallback decision:"
                            + " sessionId={}, hasRecentCheckpoint={}, error={}",
                    session == null ? null : session.getSessionId(),
                    Boolean.valueOf(hasRecentCheckpoint),
                    e.toString());
        }
        return new ImprovementDecision(
                hasRecentCheckpoint ? "update_existing_skill" : "new_skill",
                inferSkillName(session));
    }

    /** 返回供复盘模型选择的现有技能名，避免为已有类别重复创建技能。 */
    private String existingSkillNamesForPrompt() {
        try {
            StringBuilder names = new StringBuilder();
            for (SkillDescriptor descriptor : localSkillService.listSkills(null)) {
                if (names.length() > 0) {
                    names.append(", ");
                }
                names.append(descriptor.getName());
                if (names.length() >= 2000) {
                    break;
                }
            }
            return StrUtil.blankToDefault(names.toString(), "无");
        } catch (Exception e) {
            return "无";
        }
    }

    /**
     * 执行次数工具Messages相关逻辑。
     *
     * @param session 会话参数。
     * @return 返回次数工具Messages结果。
     */
    private int countToolMessages(SessionRecord session) throws Exception {
        int count = 0;
        try {
            for (org.noear.solon.ai.chat.message.ChatMessage chatMessage :
                    MessageSupport.loadMessages(session.getNdjson())) {
                if (chatMessage.getRole() == org.noear.solon.ai.chat.ChatRole.TOOL) {
                    count++;
                }
            }
        } catch (Exception e) {
            log.debug(
                    "Failed to count tool messages for learning threshold, using zero:"
                            + " sessionId={}, error={}",
                    session == null ? null : session.getSessionId(),
                    e.toString());
            return 0;
        }
        return count;
    }

    /**
     * 查找技能。
     *
     * @param skillName 技能名称参数。
     * @return 返回技能结果。
     */
    private SkillDescriptor findSkill(String skillName) throws Exception {
        List<SkillDescriptor> skills = localSkillService.listSkills(null);
        for (SkillDescriptor descriptor : skills) {
            if (descriptor.getName().equals(skillName)) {
                return descriptor;
            }
        }
        return null;
    }

    /**
     * 执行infer技能名称相关逻辑。
     *
     * @param session 会话参数。
     * @return 返回infer技能名称结果。
     */
    private String inferSkillName(SessionRecord session) {
        String base = StrUtil.nullToEmpty(session.getTitle()).toLowerCase();
        return normalizeLearningSkillName(base);
    }

    /** 规范化模型或会话建议的类级技能名，并拒绝会造成跨任务碰撞的通用占位名。 */
    private String normalizeLearningSkillName(String value) {
        String base = StrUtil.nullToEmpty(value).toLowerCase();
        base = base.replaceAll("[^a-z0-9._-]+", "-").replaceAll("-{2,}", "-");
        base = base.replaceAll("^-+", "").replaceAll("-+$", "");
        if (base.length() > 64) {
            base = base.substring(0, 64).replaceAll("[-._]+$", "");
        }
        return "learned-workflow".equals(base) ? "" : base;
    }

    /**
     * 构建技能Content。
     *
     * @param skillName 可复用任务类别对应的技能名。
     * @param session 会话参数。
     * @param message 平台消息或错误消息。
     * @param hasRecentCheckpoint hasRecentCheckpoint 参数。
     * @return 返回创建好的技能Content。
     */
    private String buildSkillContent(
            String skillName,
            SessionRecord session,
            GatewayMessage message,
            boolean hasRecentCheckpoint) {
        String modelContent =
                summarizeSkillWithModel(skillName, session, message, hasRecentCheckpoint, null);
        if (StrUtil.isNotBlank(modelContent)) {
            return modelContent;
        }
        return buildFallbackSkillContent(skillName, session, message, hasRecentCheckpoint);
    }

    /**
     * 构建兜底技能Content。
     *
     * @param skillName 可复用任务类别对应的技能名。
     * @param session 会话参数。
     * @param message 平台消息或错误消息。
     * @param hasRecentCheckpoint hasRecentCheckpoint 参数。
     * @return 返回创建好的兜底技能Content。
     */
    private String buildFallbackSkillContent(
            String skillName,
            SessionRecord session,
            GatewayMessage message,
            boolean hasRecentCheckpoint) {
        String description =
                StrUtil.blankToDefault(
                        sanitizeLearningText(session.getTitle()), "从复杂任务中沉淀出的可复用流程。");
        String progress =
                StrUtil.blankToDefault(
                        sanitizeLearningText(session.getCompressedSummary()),
                        replySafeExcerpt(session.getNdjson()));
        String nextStep =
                StrUtil.blankToDefault(
                        sanitizeLearningText(message == null ? "" : message.getText()),
                        "参考当前任务上下文继续执行。");

        StringBuilder buffer = new StringBuilder();
        buffer.append("---\n");
        buffer.append("name: ").append(skillName).append("\n");
        buffer.append("description: ").append(description).append("\n");
        buffer.append("---\n\n");
        buffer.append("# 触发条件\n");
        buffer.append("- 当遇到与本技能相似的复杂任务时使用。\n\n");
        buffer.append("# 执行步骤\n");
        buffer.append("1. 先确认当前任务目标与上下文是否匹配。\n");
        buffer.append("2. 参考下述已验证流程执行。\n");
        buffer.append("3. 结束后根据结果继续补充技能内容。\n\n");
        buffer.append("# 已验证流程\n");
        buffer.append(progress).append("\n\n");
        buffer.append("# Pitfalls\n");
        buffer.append("- 如上下文差异较大，先重新检查输入条件再复用。\n\n");
        buffer.append("# Verification\n");
        buffer.append("- 核对输出是否满足当前用户要求：").append(nextStep).append("\n");
        if (hasSessionHints(session, hasRecentCheckpoint)) {
            buffer.append("- 当前流程涉及结构化文件修改，执行前先确认 checkpoint 策略。\n");
        }
        return buffer.toString();
    }

    /**
     * 执行补丁Existing技能相关逻辑。
     *
     * @param skillName 技能名称参数。
     * @param session 会话参数。
     * @param message 平台消息或错误消息。
     * @param hasRecentCheckpoint hasRecentCheckpoint 参数。
     */
    private void patchExistingSkill(
            String skillName,
            SessionRecord session,
            GatewayMessage message,
            boolean hasRecentCheckpoint)
            throws Exception {
        SkillView view = localSkillService.viewSkill(skillName, null);
        String modelContent =
                summarizeSkillWithModel(
                        skillName, session, message, hasRecentCheckpoint, view.getContent());
        if (StrUtil.isNotBlank(modelContent)) {
            localSkillService.editSkill(skillName, modelContent);
            return;
        }

        String content = view.getContent();
        String progressBullet =
                "- "
                        + StrUtil.blankToDefault(
                                sanitizeLearningText(session.getCompressedSummary()),
                                replySafeExcerpt(session.getNdjson()));
        String pitfallBullet = "- 当上下文与历史流程不完全一致时，先重新核对输入条件与依赖。";
        String verificationBullet =
                "- 当前任务验证点："
                        + StrUtil.blankToDefault(
                                sanitizeLearningText(message.getText()), "继续核对结果是否满足用户要求。");
        if (hasRecentCheckpoint) {
            verificationBullet = verificationBullet + " 执行前确认 checkpoint 策略。";
        }

        boolean updated = false;
        if (!content.contains(progressBullet)) {
            updated |= patchOrAppend(skillName, "# 已验证流程\n", "# 已验证流程\n" + progressBullet + "\n");
        }
        if (!content.contains(pitfallBullet)) {
            updated |=
                    patchOrAppend(skillName, "# Pitfalls\n", "# Pitfalls\n" + pitfallBullet + "\n");
        }
        if (!content.contains(verificationBullet)) {
            updated |=
                    patchOrAppend(
                            skillName,
                            "# Verification\n",
                            "# Verification\n" + verificationBullet + "\n");
        }

        SkillView refreshed = localSkillService.viewSkill(skillName, null);
        if (!updated
                || !refreshed.getContent().contains(progressBullet)
                || !refreshed.getContent().contains(pitfallBullet)
                || !refreshed.getContent().contains(verificationBullet)) {
            localSkillService.editSkill(
                    skillName,
                    appendMissingSections(
                            refreshed.getContent(),
                            progressBullet,
                            pitfallBullet,
                            verificationBullet));
        }
    }

    /**
     * 执行补丁OrAppend相关逻辑。
     *
     * @param skillName 技能名称参数。
     * @param header header 参数。
     * @param replacement replacement 参数。
     * @return 返回patch Or Append结果。
     */
    private boolean patchOrAppend(String skillName, String header, String replacement) {
        try {
            localSkillService.patchSkill(skillName, header, replacement, null);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 追加Missing Sections。
     *
     * @param content 待处理内容。
     * @param progressBullet progressBullet 参数。
     * @param pitfallBullet pitfallBullet 参数。
     * @param verificationBullet verificationBullet 参数。
     * @return 返回Missing Sections结果。
     */
    private String appendMissingSections(
            String content,
            String progressBullet,
            String pitfallBullet,
            String verificationBullet) {
        String updated = content;
        if (!updated.contains(progressBullet)) {
            updated = appendSection(updated, "# 已验证流程", progressBullet);
        }
        if (!updated.contains(pitfallBullet)) {
            updated = appendSection(updated, "# Pitfalls", pitfallBullet);
        }
        if (!updated.contains(verificationBullet)) {
            updated = appendSection(updated, "# Verification", verificationBullet);
        }
        return updated;
    }

    /**
     * 追加Section。
     *
     * @param content 待处理内容。
     * @param header header 参数。
     * @param bullet bullet 参数。
     * @return 返回Section结果。
     */
    private String appendSection(String content, String header, String bullet) {
        if (content.contains(header)) {
            return content.replace(header, header + "\n" + bullet);
        }
        return content + "\n\n" + header + "\n" + bullet + "\n";
    }

    /** 判断是否需要额外补充会话提示。 */
    private boolean hasSessionHints(SessionRecord session, boolean hasRecentCheckpoint) {
        return hasRecentCheckpoint && session != null && StrUtil.isNotBlank(session.getTitle());
    }

    /**
     * 汇总技能With模型。
     *
     * @param skillName 可复用任务类别对应的技能名。
     * @param session 会话参数。
     * @param message 平台消息或错误消息。
     * @param hasRecentCheckpoint hasRecentCheckpoint 参数。
     * @param existingContent existingContent 参数。
     * @return 返回summarize技能With模型结果。
     */
    private String summarizeSkillWithModel(
            String skillName,
            SessionRecord session,
            GatewayMessage message,
            boolean hasRecentCheckpoint,
            String existingContent) {
        if (llmGateway == null) {
            return null;
        }
        try {
            String description = StrUtil.blankToDefault(session.getTitle(), "从复杂任务中沉淀出的可复用流程。");
            SessionRecord learningSession = new SessionRecord();
            learningSession.setSessionId(
                    "skill-learning-"
                            + StrUtil.blankToDefault(session.getSessionId(), "session")
                            + "-"
                            + System.currentTimeMillis());
            learningSession.setSourceKey(session.getSourceKey());

            LlmResult result =
                    callAuxiliaryChat(
                            learningSession,
                            "你是 SolonClaw 的技能沉淀器。只输出可直接写入 SKILL.md 的 Markdown，不要寒暄。",
                            buildLearningPrompt(
                                    session,
                                    message,
                                    hasRecentCheckpoint,
                                    existingContent,
                                    skillName,
                                    description));
            String raw = extractAssistantText(result);
            return normalizeModelSkillContent(raw, skillName, description);
        } catch (Exception e) {
            log.debug(
                    "Model skill summarization failed, using fallback skill content: sessionId={},"
                            + " error={}",
                    session == null ? null : session.getSessionId(),
                    e.toString());
            return null;
        }
    }

    /** 将一次会话归纳为可长期保留的一条稳定记忆，避免直接写入原始任务文本。 */
    private String summarizeMemoryWithModel(SessionRecord session, GatewayMessage message) {
        if (llmGateway == null) {
            return null;
        }
        try {
            SessionRecord memorySession = new SessionRecord();
            memorySession.setSessionId(
                    "memory-learning-"
                            + StrUtil.blankToDefault(session.getSessionId(), "session")
                            + "-"
                            + System.currentTimeMillis());
            memorySession.setSourceKey(session.getSourceKey());
            LlmResult result =
                    callAuxiliaryChat(
                            memorySession,
                            "你是 SolonClaw 的长期记忆整理器。只输出一条可长期保留的中文记忆，"
                                    + "不要输出解释、Markdown、任务步骤、命令、密钥或用户原始请求。"
                                    + "必须以“长期偏好：”“用户偏好：”“项目约定：”“环境细节：”"
                                    + "“常见纠正：”或“工具怪癖：”开头；无法提炼时输出空内容。",
                            buildMemoryLearningPrompt(session, message));
            return normalizeMemoryCandidate(extractAssistantText(result));
        } catch (Exception e) {
            log.debug(
                    "Model memory summarization failed, skipping memory-only learning:"
                            + " sessionId={}, error={}",
                    session == null ? null : session.getSessionId(),
                    e.toString());
            return null;
        }
    }

    /** 构造记忆归纳提示，仅提供脱敏摘要并明确禁止复述原始任务。 */
    private String buildMemoryLearningPrompt(SessionRecord session, GatewayMessage message) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请判断下列会话是否存在跨会话仍有价值的稳定记忆。\n");
        prompt.append("只能提炼用户偏好、项目约定、环境细节、常见纠正或工具怪癖。\n");
        prompt.append("不得复述完整用户请求，不得保留临时 TODO、进度、checkpoint、会话标识、命令或敏感信息。\n");
        prompt.append("会话摘要：\n");
        prompt.append(
                        SecretRedactor.redact(
                                StrUtil.blankToDefault(session.getCompressedSummary(), "无"), 2000))
                .append("\n");
        prompt.append("本轮请求仅供判断，不得在输出中复述：\n");
        prompt.append(
                        SecretRedactor.redact(
                                StrUtil.blankToDefault(
                                        message == null ? "" : message.getText(), ""),
                                800))
                .append("\n");
        return prompt.toString();
    }

    /** 校验并规范化模型候选，确保只交给记忆服务一条短期外的稳定事实。 */
    private String normalizeMemoryCandidate(String raw) {
        String candidate = StrUtil.nullToEmpty(raw).replace("\r", "").trim();
        if (candidate.indexOf('\n') >= 0 || candidate.startsWith("```")) {
            return null;
        }
        if (candidate.startsWith("- ")) {
            candidate = candidate.substring(2).trim();
        }
        candidate = SecretRedactor.redact(candidate, 300);
        if (StrUtil.isBlank(candidate)
                || candidate.length() > 300
                || MemoryContextBoundary.containsFence(candidate)
                || containsTransientMemoryState(candidate)
                || !hasLongTermMemoryPrefix(candidate)) {
            return null;
        }
        return candidate;
    }

    /** 拦截不应沉淀的短期执行状态。 */
    private boolean containsTransientMemoryState(String candidate) {
        String normalized = candidate.toLowerCase();
        return normalized.contains("todo")
                || normalized.contains("checkpoint")
                || normalized.contains("sessionid")
                || normalized.contains("session_id")
                || normalized.contains("本会话")
                || normalized.contains("临时");
    }

    /** 只接受文件记忆服务认可的长期语义前缀。 */
    private boolean hasLongTermMemoryPrefix(String candidate) {
        for (String prefix :
                new String[] {"长期偏好：", "长期记忆：", "用户偏好：", "项目约定：", "环境细节：", "常见纠正：", "工具怪癖："}) {
            if (candidate.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 调用Auxiliary聊天。
     *
     * @param session 会话参数。
     * @param systemPrompt 系统提示词参数。
     * @param userMessage 用户消息参数。
     * @return 返回call Auxiliary Chat结果。
     */
    private LlmResult callAuxiliaryChat(
            final SessionRecord session, final String systemPrompt, final String userMessage)
            throws Exception {
        Future<LlmResult> future =
                auxiliaryExecutorService.submit(
                        ProfileRuntimeScope.capture(
                                new Callable<LlmResult>() {
                                    /**
                                     * 执行回调调用并返回结果。
                                     *
                                     * @return 返回call结果。
                                     */
                                    @Override
                                    public LlmResult call() throws Exception {
                                        return llmGateway.chat(
                                                session,
                                                systemPrompt,
                                                userMessage,
                                                Collections.emptyList());
                                    }
                                }));
        try {
            return future.get(auxiliaryTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        }
    }

    /**
     * 执行auxiliaryTimeoutSeconds相关逻辑。
     *
     * @return 返回auxiliary Timeout Seconds结果。
     */
    private int auxiliaryTimeoutSeconds() {
        int configured = appConfig.getLearning().getAuxiliaryTimeoutSeconds();
        return configured > 0 ? configured : 60;
    }

    /**
     * 构建Learning提示词。
     *
     * @param session 会话参数。
     * @param message 平台消息或错误消息。
     * @param hasRecentCheckpoint hasRecentCheckpoint 参数。
     * @param existingContent existingContent 参数。
     * @param skillName 技能名称参数。
     * @param description 描述参数。
     * @return 返回创建好的Learning提示词。
     */
    private String buildLearningPrompt(
            SessionRecord session,
            GatewayMessage message,
            boolean hasRecentCheckpoint,
            String existingContent,
            String skillName,
            String description) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请把这次任务沉淀成一个可复用 skill。要求：\n");
        prompt.append("- 输出完整 SKILL.md。\n");
        prompt.append("- frontmatter 必须包含 name 和 description。\n");
        prompt.append("- name 固定为：").append(skillName).append("\n");
        prompt.append("- description 使用一句中文业务描述。\n");
        prompt.append("- 正文必须包含：# 触发条件、# 执行步骤、# 已验证流程、# Pitfalls、# Verification。\n");
        prompt.append("- 内容要总结可复用流程，不要记录一次性聊天寒暄。\n");
        prompt.append("- 如涉及文件、命令、工具、checkpoint，要保留具体经验和注意事项。\n");
        prompt.append("- 不要输出代码围栏，不要输出解释文字。\n\n");
        if (StrUtil.isNotBlank(existingContent)) {
            prompt.append("现有 SKILL.md，需要基于新任务更新而不是丢失旧经验：\n");
            prompt.append(SecretRedactor.redact(sanitizeLearningText(existingContent), 6000))
                    .append("\n\n");
        }
        prompt.append("建议描述：").append(sanitizeLearningText(description)).append("\n");
        prompt.append("本轮用户请求：")
                .append(sanitizeLearningText(message == null ? "" : message.getText()))
                .append("\n");
        prompt.append("是否涉及 checkpoint：").append(hasRecentCheckpoint).append("\n\n");
        prompt.append("会话压缩摘要：\n");
        prompt.append(
                        SecretRedactor.redact(
                                StrUtil.blankToDefault(
                                        sanitizeLearningText(session.getCompressedSummary()), "无"),
                                6000))
                .append("\n\n");
        prompt.append("会话消息摘录：\n");
        prompt.append(replySafeExcerpt(session.getNdjson(), 6000));
        return prompt.toString();
    }

    /**
     * 规范化模型技能Content。
     *
     * @param raw 原始输入值。
     * @param skillName 技能名称参数。
     * @param description 描述参数。
     * @return 返回模型技能Content结果。
     */
    private String normalizeModelSkillContent(String raw, String skillName, String description) {
        String content = stripMarkdownFence(sanitizeLearningText(raw));
        if (StrUtil.isBlank(content)) {
            return null;
        }

        Map<String, Object> frontmatter = SkillFrontmatterSupport.parseFrontmatter(content);
        String modelDescription =
                SkillFrontmatterSupport.resolveDescription(frontmatter, description);
        String body = content;
        if (content.startsWith("---")) {
            int end = content.indexOf("\n---", 3);
            if (end >= 0) {
                body = content.substring(end + "\n---".length()).trim();
            }
        }
        if (StrUtil.isBlank(body)) {
            return null;
        }
        if (!isStructuredSkillBody(body)) {
            return null;
        }

        StringBuilder normalized = new StringBuilder();
        normalized.append("---\n");
        normalized.append("name: ").append(skillName).append("\n");
        normalized
                .append("description: ")
                .append(
                        StrUtil.blankToDefault(
                                        sanitizeLearningText(modelDescription), "从复杂任务中沉淀出的可复用流程。")
                                .replace('\n', ' '))
                .append("\n");
        normalized.append("---\n\n");
        normalized.append(body);
        if (!body.endsWith("\n")) {
            normalized.append('\n');
        }
        return SecretRedactor.redact(normalized.toString(), 20000);
    }

    /**
     * 判断是否Structured技能Body。
     *
     * @param body 请求体或消息正文内容。
     * @return 如果Structured技能Body满足条件则返回 true，否则返回 false。
     */
    private boolean isStructuredSkillBody(String body) {
        String normalized = "\n" + StrUtil.nullToEmpty(body).replace("\r\n", "\n").trim() + "\n";
        return normalized.contains("\n# 触发条件\n")
                && normalized.contains("\n# 执行步骤\n")
                && normalized.contains("\n# 已验证流程\n")
                && normalized.contains("\n# Pitfalls\n")
                && normalized.contains("\n# Verification\n");
    }

    /**
     * 剥离MarkdownFence。
     *
     * @param content 待处理内容。
     * @return 返回strip Markdown Fence结果。
     */
    private String stripMarkdownFence(String content) {
        String value = StrUtil.nullToEmpty(content).trim();
        if (!value.startsWith("```")) {
            return value;
        }
        int firstLineEnd = value.indexOf('\n');
        int lastFence = value.lastIndexOf("```");
        if (firstLineEnd >= 0 && lastFence > firstLineEnd) {
            return value.substring(firstLineEnd + 1, lastFence).trim();
        }
        return value;
    }

    /**
     * 提取Assistant Text。
     *
     * @param result 结果响应或执行结果。
     * @return 返回Assistant Text结果。
     */
    private String extractAssistantText(LlmResult result) {
        if (result == null) {
            return "";
        }
        AssistantMessage message = result.getAssistantMessage();
        if (message == null) {
            return MessageSupport.visibleText(result.getRawResponse());
        }
        String text = MessageSupport.assistantText(message);
        return StrUtil.isNotBlank(text)
                ? text
                : MessageSupport.visibleText(result.getRawResponse());
    }

    /**
     * 执行回复安全Excerpt相关逻辑。
     *
     * @param ndjson ndjson 参数。
     * @return 返回reply Safe Excerpt结果。
     */
    private String replySafeExcerpt(String ndjson) {
        return replySafeExcerpt(ndjson, 400);
    }

    /**
     * 执行回复安全Excerpt相关逻辑。
     *
     * @param ndjson ndjson 参数。
     * @param limit 最大返回数量。
     * @return 返回reply Safe Excerpt结果。
     */
    private String replySafeExcerpt(String ndjson, int limit) {
        String normalized;
        try {
            StringBuilder buffer = new StringBuilder();
            for (ChatMessage message : MessageSupport.loadMessages(ndjson)) {
                String text =
                        message instanceof AssistantMessage
                                ? MessageSupport.assistantText((AssistantMessage) message)
                                : sanitizeLearningText(message.getContent());
                if (StrUtil.isBlank(text)) {
                    continue;
                }
                buffer.append(message.getRole()).append(": ").append(text).append('\n');
            }
            normalized = buffer.toString().trim();
        } catch (Exception e) {
            normalized = sanitizeLearningText(ndjson).replace('\n', ' ').trim();
        }
        if (normalized.length() <= limit) {
            return SecretRedactor.redact(normalized, limit);
        }
        return SecretRedactor.redact(
                "..." + normalized.substring(normalized.length() - limit), limit);
    }

    /** 清除技能学习输入和模型输出中的内部思考、记忆边界及压缩摘要协议文本。 */
    private String sanitizeLearningText(String text) {
        String value =
                StrUtil.nullToEmpty(text).replace(CompressionConstants.SUMMARY_PREFIX, "").trim();
        value = MemoryContextBoundary.scrubVisibleText(value);
        return MessageSupport.visibleText(value);
    }

    /** 保存复盘模型给出的学习动作及其类级技能名称。 */
    private static final class ImprovementDecision {
        /** 复盘动作。 */
        private final String action;

        /** 可复用任务类别对应的技能名。 */
        private final String skillName;

        /** 创建一次技能学习决策。 */
        private ImprovementDecision(String action, String skillName) {
            this.action = action;
            this.skillName = skillName;
        }
    }

    /**
     * 写入Improvement Report。
     *
     * @param session 会话参数。
     * @param skillName 技能名称参数。
     * @param action 操作参数。
     * @param summary 摘要参数。
     * @param changedFiles 文件或目录路径参数。
     * @param needsReview needsReview 参数。
     */
    private void writeImprovementReport(
            SessionRecord session,
            String skillName,
            String action,
            String summary,
            List<String> changedFiles,
            boolean needsReview) {
        try {
            File reportDir =
                    cn.hutool.core.io.FileUtil.file(
                            appConfig.getRuntime().getLogsDir(), "skill-improvements");
            cn.hutool.core.io.FileUtil.mkdir(reportDir);
            Map<String, Object> report = new LinkedHashMap<String, Object>();
            report.put("sessionId", session == null ? null : session.getSessionId());
            report.put("runId", null);
            report.put("skillName", skillName);
            report.put("action", action);
            report.put("summary", summary);
            report.put("changedFiles", changedFiles);
            report.put("needsReview", Boolean.valueOf(needsReview));
            report.put("createdAt", Long.valueOf(System.currentTimeMillis()));
            saveImprovement(report);
            cn.hutool.core.io.FileUtil.writeUtf8String(
                    org.noear.snack4.ONode.serialize(report),
                    cn.hutool.core.io.FileUtil.file(
                            reportDir,
                            System.currentTimeMillis()
                                    + "-"
                                    + StrUtil.blankToDefault(skillName, "memory")
                                    + ".json"));
        } catch (Exception e) {
            log.warn(
                    "Failed to write skill improvement report: sessionId={}, skillName={},"
                            + " action={}, error={}",
                    session == null ? null : session.getSessionId(),
                    skillName,
                    action,
                    e.toString());
        }
    }

    /**
     * 保存Improvement。
     *
     * @param report report 参数。
     */
    private void saveImprovement(Map<String, Object> report) {
        if (database == null || report == null) {
            return;
        }
        Connection connection = null;
        try {
            connection = database.openConnection();
            PreparedStatement statement =
                    connection.prepareStatement(
                            "insert into skill_improvements (improvement_id, session_id, run_id,"
                                    + " skill_name, action, summary, changed_files_json, evidence_json,"
                                    + " needs_review, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?,"
                                    + " ?)");
            statement.setString(1, IdSupport.newId());
            statement.setString(2, asString(report.get("sessionId")));
            statement.setString(3, asString(report.get("runId")));
            statement.setString(
                    4, StrUtil.blankToDefault(asString(report.get("skillName")), "memory"));
            statement.setString(
                    5, StrUtil.blankToDefault(asString(report.get("action")), "unknown"));
            statement.setString(6, asString(report.get("summary")));
            statement.setString(7, org.noear.snack4.ONode.serialize(report.get("changedFiles")));
            statement.setString(8, org.noear.snack4.ONode.serialize(report));
            statement.setInt(9, Boolean.TRUE.equals(report.get("needsReview")) ? 1 : 0);
            statement.setLong(10, TimeSupport.millisOrNow(report.get("createdAt")));
            statement.executeUpdate();
            statement.close();
        } catch (Exception e) {
            log.warn(
                    "Failed to save skill improvement report into database: sessionId={},"
                            + " skillName={}, action={}, error={}",
                    asString(report.get("sessionId")),
                    asString(report.get("skillName")),
                    asString(report.get("action")),
                    e.toString());
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception e) {
                    log.debug(
                            "Failed to close skill improvement database connection: sessionId={},"
                                    + " error={}",
                            asString(report.get("sessionId")),
                            e.toString());
                }
            }
        }
    }

    /**
     * 执行as字符串相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回as String结果。
     */
    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
