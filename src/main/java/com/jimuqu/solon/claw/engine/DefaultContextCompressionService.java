package com.jimuqu.solon.claw.engine;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.CompressionOutcome;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.service.ContextCompressionService;
import com.jimuqu.solon.claw.goal.GoalMigrationSupport;
import com.jimuqu.solon.claw.support.MessageAttachmentSupport;
import com.jimuqu.solon.claw.support.MessageSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.CompressionConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 默认上下文压缩服务。 */
public class DefaultContextCompressionService implements ContextCompressionService {
    /** 上下文压缩服务的低敏诊断日志。 */
    private static final Logger log =
            LoggerFactory.getLogger(DefaultContextCompressionService.class);

    /** 应用配置。 */
    private final AppConfig appConfig;

    /**
     * 目标迁移支持，仅在上下文压缩触发 session_id 轮转时调用；当前实现为原地压缩（不轮转 session_id），
     * 该字段保留为可空，供未来引入轮转分支时启用（对标仓库 migrate_goal_to_session）。
     */
    private final GoalMigrationSupport goalMigrationSupport;

    /** 创建上下文压缩服务，仅注入应用配置（目标迁移支持为 null）。 */
    public DefaultContextCompressionService(AppConfig appConfig) {
        this(appConfig, null);
    }

    /**
     * 创建上下文压缩服务，注入应用配置与可选的目标迁移支持。
     *
     * @param appConfig 应用运行配置。
     * @param goalMigrationSupport 目标迁移支持，可为 null（无 goal 场景）。
     */
    public DefaultContextCompressionService(AppConfig appConfig, GoalMigrationSupport goalMigrationSupport) {
        this.appConfig = appConfig;
        this.goalMigrationSupport = goalMigrationSupport;
    }

    /**
     * 执行压缩IfNeeded相关逻辑。
     *
     * @param session 会话参数。
     * @param systemPrompt 系统提示词参数。
     * @param userMessage 用户消息参数。
     * @return 返回压缩If Needed结果。
     */
    @Override
    public SessionRecord compressIfNeeded(
            SessionRecord session, String systemPrompt, String userMessage) throws Exception {
        return compressIfNeededWithOutcome(session, systemPrompt, userMessage).getSession();
    }

    /**
     * 执行压缩IfNeededWithOutcome相关逻辑。
     *
     * @param session 会话参数。
     * @param systemPrompt 系统提示词参数。
     * @param userMessage 用户消息参数。
     * @return 返回压缩If Needed With Outcome结果。
     */
    @Override
    public CompressionOutcome compressIfNeededWithOutcome(
            SessionRecord session, String systemPrompt, String userMessage) throws Exception {
        if (!appConfig.getCompression().isEnabled()) {
            return CompressionOutcome.skipped(session);
        }

        int contextWindow = Math.max(1024, appConfig.getLlm().getContextWindowTokens());
        int threshold = (int) (contextWindow * appConfig.getCompression().getThresholdPercent());
        int estimatedTokens = estimateRequestTokens(session, systemPrompt, userMessage);
        if (shouldSkipForFailureCooldown(session)) {
            return withBudget(CompressionOutcome.skipped(session), estimatedTokens, threshold);
        }
        if (estimatedTokens < threshold) {
            return withBudget(CompressionOutcome.skipped(session), estimatedTokens, threshold);
        }
        if (shouldSkipForThrashing(session, estimatedTokens)) {
            return withBudget(CompressionOutcome.skipped(session), estimatedTokens, threshold);
        }

        session.setLastCompressionInputTokens(estimatedTokens);
        return withBudget(
                compressNowWithOutcome(session, systemPrompt, null), estimatedTokens, threshold);
    }

    /**
     * 执行压缩Now相关逻辑。
     *
     * @param session 会话参数。
     * @param systemPrompt 系统提示词参数。
     * @return 返回压缩Now结果。
     */
    @Override
    public SessionRecord compressNow(SessionRecord session, String systemPrompt) throws Exception {
        return compressNow(session, systemPrompt, null);
    }

    /**
     * 执行压缩Now相关逻辑。
     *
     * @param session 会话参数。
     * @param systemPrompt 系统提示词参数。
     * @param focus focus 参数。
     * @return 返回压缩Now结果。
     */
    @Override
    public SessionRecord compressNow(SessionRecord session, String systemPrompt, String focus)
            throws Exception {
        return compressNowWithOutcome(session, systemPrompt, focus).getSession();
    }

    /**
     * 执行压缩NowWithOutcome相关逻辑。
     *
     * @param session 会话参数。
     * @param systemPrompt 系统提示词参数。
     * @param focus focus 参数。
     * @return 返回压缩Now With Outcome结果。
     */
    @Override
    public CompressionOutcome compressNowWithOutcome(
            SessionRecord session, String systemPrompt, String focus) throws Exception {
        String beforeNdjson = session == null ? "" : session.getNdjson();
        try {
            List<ChatMessage> history = MessageSupport.loadMessages(session.getNdjson());
            ToolCallArgumentSanitizer.sanitize(history);
            if (history.size() <= appConfig.getCompression().getProtectHeadMessages() + 1) {
                return CompressionOutcome.skipped(session);
            }

            List<ChatMessage> normalized = new ArrayList<ChatMessage>();
            String previousSummary = StrUtil.nullToEmpty(session.getCompressedSummary()).trim();
            int latestHistoryUserIndex = findLastUserIndex(history);
            for (int i = 0; i < history.size(); i++) {
                ChatMessage message = history.get(i);
                if (CompressionConstants.isSummaryContent(message.getContent())
                        && (message.getRole() != ChatRole.USER || i < latestHistoryUserIndex)) {
                    if (StrUtil.isBlank(previousSummary)) {
                        previousSummary = message.getContent().trim();
                    }
                    continue;
                }
                normalized.add(message);
            }

            if (normalized.size() <= appConfig.getCompression().getProtectHeadMessages() + 1) {
                return CompressionOutcome.skipped(session);
            }

            List<ChatMessage> pruned = pruneOldToolResults(normalized);
            int protectHead = resolveProtectHeadCount(pruned, StrUtil.isNotBlank(previousSummary));
            int protectTailStart = findTailStart(pruned);
            int lastUserIndex = findLastUserIndex(pruned);
            if (lastUserIndex >= protectHead && lastUserIndex < protectTailStart) {
                protectTailStart = lastUserIndex;
            }
            if (protectTailStart <= protectHead) {
                protectTailStart = Math.max(protectHead + 1, pruned.size() - 1);
                if (lastUserIndex >= protectHead && lastUserIndex < protectTailStart) {
                    protectTailStart = lastUserIndex;
                }
                if (protectTailStart <= protectHead) {
                    return CompressionOutcome.skipped(session);
                }
            }

            List<ChatMessage> head = new ArrayList<ChatMessage>(pruned.subList(0, protectHead));
            List<ChatMessage> middle =
                    new ArrayList<ChatMessage>(pruned.subList(protectHead, protectTailStart));
            List<ChatMessage> tail =
                    new ArrayList<ChatMessage>(pruned.subList(protectTailStart, pruned.size()));

            if (middle.isEmpty() || shouldSkipMiddleCompression(middle, pruned, normalized)) {
                return CompressionOutcome.skipped(session);
            }

            String summaryBody =
                    buildStructuredSummary(
                            session, systemPrompt, middle, tail, previousSummary, focus);
            String summaryText = CompressionConstants.SUMMARY_PREFIX + "\n" + summaryBody;

            List<ChatMessage> compacted = new ArrayList<ChatMessage>();
            compacted.addAll(head);
            compacted.add(ChatMessage.ofAssistant(summaryText));
            compacted.addAll(tail);

            session.setCompressedSummary(summaryText);
            session.setNdjson(MessageSupport.toNdjson(compacted));
            session.setLastCompressionAt(System.currentTimeMillis());
            session.setCompressionFailureCount(0);
            session.setLastCompressionFailedAt(0L);
            session.setUpdatedAt(System.currentTimeMillis());
            return CompressionOutcome.success(
                    session, !StrUtil.equals(beforeNdjson, session.getNdjson()));
        } catch (Exception e) {
            session.setCompressionFailureCount(session.getCompressionFailureCount() + 1);
            session.setLastCompressionFailedAt(System.currentTimeMillis());
            return CompressionOutcome.failed(session, e);
        }
    }

    /**
     * 执行with预算相关逻辑。
     *
     * @param outcome outcome 参数。
     * @param estimatedTokens estimatedtoken参数。
     * @param thresholdTokens thresholdtoken参数。
     * @return 返回with Budget结果。
     */
    private CompressionOutcome withBudget(
            CompressionOutcome outcome, int estimatedTokens, int thresholdTokens) {
        if (outcome != null) {
            outcome.setEstimatedTokens(estimatedTokens);
            outcome.setThresholdTokens(thresholdTokens);
        }
        return outcome;
    }

    /**
     * 上下文压缩轮转 session_id 时迁移目标：子会话继承 active 目标，父会话归档为 cleared。
     *
     * <p>实现说明：当前 {@link DefaultContextCompressionService} 为原地压缩（直接改写同一 session 的
     * ndjson/summary，不轮转 session_id），因此本方法暂不被压缩主流程调用。该方法作为集成点保留，
     * 供未来引入 session_id 轮转分支时启用（对标仓库 conversation_compression.py:820 的
     * migrate_goal_to_session）。传入的 oldSessionId 与 newSessionId 不同时才迁移；原地压缩不调用。
     *
     * @param oldSessionId 父会话 id。
     * @param newSessionId 子会话 id。
     */
    private void migrateGoalOnRotation(String oldSessionId, String newSessionId) {
        if (goalMigrationSupport == null
                || StrUtil.isBlank(oldSessionId)
                || StrUtil.isBlank(newSessionId)
                || oldSessionId.equals(newSessionId)) {
            return;
        }
        try {
            goalMigrationSupport.migrate(oldSessionId, newSessionId, "compression");
        } catch (Exception e) {
            log.warn("goal migration on compression failed: {}", e.getMessage());
        }
    }

    /** 对较早的工具结果做预裁剪。 */
    private List<ChatMessage> pruneOldToolResults(List<ChatMessage> messages) {
        List<ChatMessage> result = new ArrayList<ChatMessage>();
        int tailStart = findTailStart(messages);
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            if (i < tailStart
                    && message.getRole() == ChatRole.TOOL
                    && message.getContent() != null
                    && message.getContent().length() > 200) {
                result.add(
                        ChatMessage.ofTool(
                                CompressionConstants.PRUNED_TOOL_PLACEHOLDER,
                                "tool",
                                "compacted-" + i));
            } else {
                if (i < tailStart && message instanceof AssistantMessage) {
                    pruneAssistantToolArguments((AssistantMessage) message);
                }
                result.add(message);
            }
        }
        return result;
    }

    /** 对旧 assistant tool_calls 的 arguments 做 JSON 内部裁剪，避免截断成非法 JSON。 */
    @SuppressWarnings("unchecked")
    private void pruneAssistantToolArguments(AssistantMessage message) {
        if (message == null) {
            return;
        }
        if (message.getToolCallsRaw() != null) {
            for (Map raw : message.getToolCallsRaw()) {
                Object function = raw == null ? null : raw.get("function");
                if (function instanceof Map) {
                    Map functionMap = (Map) function;
                    Object arguments = functionMap.get("arguments");
                    if (arguments instanceof String && ((String) arguments).length() > 400) {
                        functionMap.put(
                                "arguments", shrinkToolArgumentsJson((String) arguments, 200));
                    }
                }
            }
        }
        if (message.getToolCalls() != null) {
            for (ToolCall toolCall : message.getToolCalls()) {
                Map<String, Object> arguments = toolCall == null ? null : toolCall.getArguments();
                if (arguments == null || arguments.isEmpty()) {
                    continue;
                }
                shrinkToolArgumentObject(arguments, 200);
            }
        }
    }

    /**
     * 执行shrink工具参数JSON相关逻辑。
     *
     * @param raw 原始输入值。
     * @param headChars headChars 参数。
     * @return 返回shrink工具参数JSON结果。
     */
    private String shrinkToolArgumentsJson(String raw, int headChars) {
        if (StrUtil.isBlank(raw)) {
            return raw;
        }
        try {
            Object parsed = ONode.deserialize(raw, Object.class);
            shrinkToolArgumentObject(parsed, headChars);
            return ONode.serialize(parsed);
        } catch (Exception e) {
            log.debug(
                    "工具参数 JSON 裁剪失败，按原始参数字符串兜底 length={}, error={}",
                    raw.length(),
                    e.getClass().getSimpleName());
            return raw;
        }
    }

    /**
     * 执行shrink工具参数Object相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @param headChars headChars 参数。
     */
    @SuppressWarnings("unchecked")
    private void shrinkToolArgumentObject(Object value, int headChars) {
        if (value instanceof Map) {
            Map<Object, Object> map = (Map<Object, Object>) value;
            for (Map.Entry<Object, Object> entry : map.entrySet()) {
                Object item = entry.getValue();
                if (item instanceof String && ((String) item).length() > headChars) {
                    entry.setValue(((String) item).substring(0, headChars) + "...[truncated]");
                } else {
                    shrinkToolArgumentObject(item, headChars);
                }
            }
        } else if (value instanceof List) {
            for (Object item : (List<?>) value) {
                shrinkToolArgumentObject(item, headChars);
            }
        }
    }

    /** 根据尾部 token 预算反推出应保护的 tail 起点。 */
    private int findTailStart(List<ChatMessage> messages) {
        int contextWindow = Math.max(1024, appConfig.getLlm().getContextWindowTokens());
        int tailBudget = (int) (contextWindow * appConfig.getCompression().getTailRatio());
        int accumulated = 0;
        int start = messages.size();
        for (int i = messages.size() - 1; i >= 0; i--) {
            int tokens = estimateTokens(messages.get(i).getContent()) + 10;
            if (accumulated + tokens > tailBudget) {
                break;
            }
            accumulated += tokens;
            start = i;
        }
        return start;
    }

    /**
     * 查找Last用户Index。
     *
     * @param messages messages 参数。
     * @return 返回Last用户Index结果。
     */
    private int findLastUserIndex(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message.getRole() == ChatRole.USER) {
                return i;
            }
        }
        return -1;
    }

    /** 生成结构化摘要。 */
    private String buildStructuredSummary(
            SessionRecord session,
            String systemPrompt,
            List<ChatMessage> middle,
            List<ChatMessage> tail,
            String previousSummary,
            String focus) {
        String goal = extractLatestUserMessage(middle, tail);
        String progress = collectByRole(middle, ChatRole.ASSISTANT, 3);
        String decisions = collectKeywords(middle, new String[] {"决定", "改为", "使用", "切换", "采用"});
        String files = collectFileMentions(middle);
        String lastCompactedTurns = collectRecentCompactedTurns(middle, 6);
        String remainingWork = collectByRole(tail, ChatRole.USER, 1);

        StringBuilder buffer = new StringBuilder();
        String normalizedPreviousSummary = normalizePreviousSummary(previousSummary);
        if (StrUtil.isNotBlank(normalizedPreviousSummary)) {
            buffer.append("Previous Summary\n").append(normalizedPreviousSummary).append("\n\n");
        }
        if (StrUtil.isNotBlank(focus)) {
            buffer.append("Focus\n").append(trimContent(focus, 200)).append("\n\n");
        }
        buffer.append("Goal\n")
                .append(StrUtil.blankToDefault(goal, inferGoalFromPrompt(systemPrompt)))
                .append("\n\n");
        buffer.append("Progress\n")
                .append(StrUtil.blankToDefault(progress, "已对较早轮次进行压缩，后续请基于当前文件状态继续。"))
                .append("\n\n");
        buffer.append("Decisions\n")
                .append(StrUtil.blankToDefault(decisions, "未提取到明确决策，请结合当前工程状态判断。"))
                .append("\n\n");
        buffer.append("Files\n")
                .append(StrUtil.blankToDefault(files, "未提取到明确文件列表。"))
                .append("\n\n");
        if (StrUtil.isNotBlank(lastCompactedTurns)) {
            buffer.append("Last Compacted Turns\n").append(lastCompactedTurns).append("\n\n");
        }
        buffer.append("Remaining Work\n")
                .append(StrUtil.blankToDefault(remainingWork, "记录最近用户要求，避免重复之前已完成的工作。"));
        return trimMultilineContent(
                buffer.toString().trim(), CompressionConstants.MAX_SUMMARY_LENGTH);
    }

    /** 提取最近一条用户目标。 */
    private String extractLatestUserMessage(List<ChatMessage> middle, List<ChatMessage> tail) {
        for (int i = tail.size() - 1; i >= 0; i--) {
            ChatMessage message = tail.get(i);
            if (message.getRole() == ChatRole.USER && StrUtil.isNotBlank(message.getContent())) {
                return trimContent(message.getContent(), 240);
            }
        }
        for (int i = middle.size() - 1; i >= 0; i--) {
            ChatMessage message = middle.get(i);
            if (message.getRole() == ChatRole.USER && StrUtil.isNotBlank(message.getContent())) {
                return trimContent(message.getContent(), 240);
            }
        }
        return "";
    }

    /** 按角色收集最近若干条消息。 */
    private String collectByRole(List<ChatMessage> messages, ChatRole role, int maxItems) {
        StringBuilder buffer = new StringBuilder();
        int count = 0;
        for (int i = messages.size() - 1; i >= 0 && count < maxItems; i--) {
            ChatMessage message = messages.get(i);
            if (message.getRole() != role || StrUtil.isBlank(message.getContent())) {
                continue;
            }
            if (buffer.length() > 0) {
                buffer.insert(0, '\n');
            }
            buffer.insert(0, "- " + trimContent(message.getContent(), 220));
            count++;
        }
        return buffer.toString();
    }

    /** 收集中间消息中的关键决策文本。 */
    private String collectKeywords(List<ChatMessage> messages, String[] keywords) {
        StringBuilder buffer = new StringBuilder();
        for (ChatMessage message : messages) {
            String content = StrUtil.nullToEmpty(message.getContent());
            for (String keyword : keywords) {
                if (content.contains(keyword)) {
                    if (buffer.length() > 0) {
                        buffer.append('\n');
                    }
                    buffer.append("- ").append(trimContent(content, 220));
                    break;
                }
            }
        }
        return buffer.toString();
    }

    /** 保留被压缩窗口尾部的若干可恢复锚点，避免工具错误或关键交接被摘要抹平。 */
    private String collectRecentCompactedTurns(List<ChatMessage> messages, int maxItems) {
        List<String> turns = new ArrayList<String>();
        for (int i = messages.size() - 1; i >= 0 && turns.size() < maxItems; i--) {
            ChatMessage message = messages.get(i);
            if (message == null || StrUtil.isBlank(message.getContent())) {
                continue;
            }
            if (message.getRole() == ChatRole.USER) {
                continue;
            }
            String label = message.getRole() == null ? "UNKNOWN" : message.getRole().name();
            String content = compactCompactedTurnContent(message.getContent());
            if (StrUtil.isBlank(content)) {
                continue;
            }
            turns.add(0, "- " + label + ": " + content);
        }
        return String.join("\n", turns);
    }

    /**
     * 执行紧凑CompactedTurnContent相关逻辑。
     *
     * @param content 待处理内容。
     * @return 返回compact Compacted Turn Content结果。
     */
    private String compactCompactedTurnContent(String content) {
        String safe = SecretRedactor.redact(content, 700);
        if (safe == null) {
            return "";
        }
        safe = safe.replace('\r', ' ').replace('\n', ' ').trim();
        while (safe.contains("  ")) {
            safe = safe.replace("  ", " ");
        }
        if (safe.length() > 260) {
            return safe.substring(0, 260) + "...";
        }
        return safe;
    }

    /** 归纳中间消息里出现的文件路径。 */
    private String collectFileMentions(List<ChatMessage> messages) {
        StringBuilder buffer = new StringBuilder();
        for (ChatMessage message : messages) {
            String content = StrUtil.nullToEmpty(message.getContent());
            String[] parts = content.split("\\s+");
            for (String part : parts) {
                if (part.contains("/") || part.contains("\\")) {
                    if (buffer.indexOf(part) < 0) {
                        if (buffer.length() > 0) {
                            buffer.append('\n');
                        }
                        buffer.append("- ").append(trimContent(part, 180));
                    }
                }
            }
        }
        return buffer.toString();
    }

    /** 从当前系统提示词回推目标。 */
    private String inferGoalFromPrompt(String systemPrompt) {
        if (StrUtil.isBlank(systemPrompt)) {
            return "继续当前任务。";
        }
        return trimContent(systemPrompt, 160);
    }

    /** 压缩失败冷却期内直接跳过。 */
    private boolean shouldSkipForFailureCooldown(SessionRecord session) {
        return session.getLastCompressionFailedAt() > 0
                && System.currentTimeMillis() - session.getLastCompressionFailedAt()
                        < CompressionConstants.FAILURE_COOLDOWN_MILLIS;
    }

    /** 压缩后短时间内若上下文增长不明显，则跳过重压缩。 */
    private boolean shouldSkipForThrashing(SessionRecord session, int estimatedTokens) {
        if (session.getLastCompressionAt() <= 0) {
            return false;
        }
        long elapsed = System.currentTimeMillis() - session.getLastCompressionAt();
        if (elapsed >= CompressionConstants.RECOMPRESS_COOLDOWN_MILLIS) {
            return false;
        }
        return estimatedTokens
                <= session.getLastCompressionInputTokens()
                        + CompressionConstants.MIN_RECOMPRESS_DELTA_TOKENS;
    }

    /** 如果中间区间已经只剩占位内容，则无需继续压缩。 */
    private boolean shouldSkipMiddleCompression(
            List<ChatMessage> middle, List<ChatMessage> pruned, List<ChatMessage> original) {
        if (!isSameMessages(pruned, original)) {
            return false;
        }
        for (ChatMessage message : middle) {
            String content = StrUtil.nullToEmpty(message.getContent()).trim();
            if (content.length() == 0) {
                continue;
            }
            if (CompressionConstants.PRUNED_TOOL_PLACEHOLDER.equals(content)) {
                continue;
            }
            if (CompressionConstants.isSummaryContent(content)) {
                continue;
            }
            return false;
        }
        return true;
    }

    /**
     * 判断是否Same Messages。
     *
     * @param left 左侧比较对象。
     * @param right 右侧比较对象。
     * @return 如果Same Messages满足条件则返回 true，否则返回 false。
     */
    private boolean isSameMessages(List<ChatMessage> left, List<ChatMessage> right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null || left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            ChatMessage leftMessage = left.get(i);
            ChatMessage rightMessage = right.get(i);
            if (leftMessage == rightMessage) {
                continue;
            }
            if (leftMessage == null || rightMessage == null) {
                return false;
            }
            if (leftMessage.getRole() != rightMessage.getRole()) {
                return false;
            }
            if (!StrUtil.equals(leftMessage.getContent(), rightMessage.getContent())) {
                return false;
            }
        }
        return true;
    }

    /** 粗略估算 token。 */
    private int estimateTokens(String content) {
        if (StrUtil.isBlank(content)) {
            return 0;
        }
        return ContextTokenEstimator.estimate(content);
    }

    /** 限长文本。 */
    private String trimContent(String content, int maxLength) {
        String normalized = content.replace('\r', ' ').replace('\n', ' ').trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    /** 限长多行文本，同时保留结构化换行。 */
    private String trimMultilineContent(String content, int maxLength) {
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    /** 综合当前 NDJSON 与上一轮真实 usage 做更稳妥的请求量估算。 */
    private int estimateRequestTokens(
            SessionRecord session, String systemPrompt, String userMessage) {
        long estimated =
                (long) estimateTokens(systemPrompt)
                        + estimateTokens(userMessage)
                        + estimateAttachmentTokens(userMessage)
                        + estimateTokens(session == null ? null : session.getNdjson());

        if (session != null) {
            long historicalFloor = session.getLastInputTokens();
            if (session.getLastCompressionAt() > 0
                    && session.getLastUsageAt() > 0
                    && session.getLastUsageAt() < session.getLastCompressionAt()) {
                historicalFloor = session.getLastCompressionInputTokens();
            }
            if (historicalFloor > 0) {
                estimated = Math.max(estimated, historicalFloor + estimateTokens(userMessage));
            }
        }

        return estimated > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) estimated;
    }

    /**
     * 估算附件token。
     *
     * @param userMessage 用户消息参数。
     * @return 返回附件token结果。
     */
    private int estimateAttachmentTokens(String userMessage) {
        if (StrUtil.isBlank(userMessage) || !userMessage.contains("estimatedTokens=")) {
            return 0;
        }
        return MessageAttachmentSupport.estimateAttachmentTokensFromSummary(userMessage);
    }

    /** 去掉已有摘要中再次嵌套的 “Previous Summary” 区块，避免摘要递归膨胀。 */
    private String normalizePreviousSummary(String previousSummary) {
        String normalized = CompressionConstants.stripSummaryPrefix(previousSummary);
        if (StrUtil.isBlank(normalized)) {
            return "";
        }

        if (StrUtil.startWithIgnoreCase(normalized, "Previous Summary")) {
            int firstSectionIndex = findFirstSectionHeader(normalized);
            if (firstSectionIndex > 0) {
                normalized = normalized.substring(firstSectionIndex).trim();
            }
        }

        return trimMultilineContent(normalized, CompressionConstants.MAX_PREVIOUS_SUMMARY_LENGTH);
    }

    /** 找到结构化摘要正文的首个章节标题。 */
    private int findFirstSectionHeader(String content) {
        int result = -1;
        String[] headers =
                new String[] {
                    "Focus",
                    "Goal",
                    "Progress",
                    "Decisions",
                    "Files",
                    "Remaining Work"
                };
        for (String header : headers) {
            int newlineIdx = content.indexOf(header + "\n");
            if (newlineIdx >= 0 && (result < 0 || newlineIdx < result)) {
                result = newlineIdx;
            }
            int spaceIdx = content.indexOf(header + " ");
            if (spaceIdx >= 0 && (result < 0 || spaceIdx < result)) {
                result = spaceIdx;
            }
        }
        return result;
    }

    /** 已有摘要时，不再永久保留最早的普通对话，只保留前导 system 消息。 */
    private int resolveProtectHeadCount(List<ChatMessage> messages, boolean hasPreviousSummary) {
        int configured =
                Math.min(appConfig.getCompression().getProtectHeadMessages(), messages.size());
        if (!hasPreviousSummary) {
            return configured;
        }

        int systemCount = 0;
        for (ChatMessage message : messages) {
            if (message.getRole() == ChatRole.SYSTEM) {
                systemCount++;
                continue;
            }
            break;
        }
        return Math.min(configured, systemCount);
    }
}
