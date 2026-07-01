package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.CheckpointRecord;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.CheckpointService;
import com.jimuqu.solon.claw.goal.GoalState;
import com.jimuqu.solon.claw.support.MessageSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.SessionArtifactService;
import com.jimuqu.solon.claw.support.SourceKeySupport;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.tool.ToolCall;

/** Dashboard 会话查询服务。 */
public class DashboardSessionService {
    /** 保存会话仓储依赖，用于访问持久化数据。 */
    private final SessionRepository sessionRepository;

    /** 注入检查点服务，用于调用对应业务能力。 */
    private final CheckpointService checkpointService;

    /** 注入会话Artifact服务，用于调用对应业务能力。 */
    private final SessionArtifactService sessionArtifactService;

    /**
     * 创建控制台会话服务实例，并注入运行所需依赖。
     *
     * @param sessionRepository 会话仓储依赖。
     */
    public DashboardSessionService(SessionRepository sessionRepository) {
        this(sessionRepository, null);
    }

    /**
     * 创建控制台会话服务实例，并注入运行所需依赖。
     *
     * @param sessionRepository 会话仓储依赖。
     * @param checkpointService checkpoint服务依赖。
     */
    public DashboardSessionService(
            SessionRepository sessionRepository, CheckpointService checkpointService) {
        this(sessionRepository, checkpointService, new SessionArtifactService());
    }

    /**
     * 创建控制台会话服务实例，并注入运行所需依赖。
     *
     * @param sessionRepository 会话仓储依赖。
     * @param checkpointService checkpoint服务依赖。
     * @param sessionArtifactService 会话Artifact服务依赖。
     */
    public DashboardSessionService(
            SessionRepository sessionRepository,
            CheckpointService checkpointService,
            SessionArtifactService sessionArtifactService) {
        this.sessionRepository = sessionRepository;
        this.checkpointService = checkpointService;
        this.sessionArtifactService =
                sessionArtifactService == null
                        ? new SessionArtifactService()
                        : sessionArtifactService;
    }

    /**
     * 读取Sessions。
     *
     * @param limit 最大返回数量。
     * @param offset 分页偏移量。
     * @return 返回读取到的Sessions。
     */
    public Map<String, Object> getSessions(int limit, int offset) throws Exception {
        int safeLimit = limit <= 0 ? 20 : Math.min(limit, 100);
        int safeOffset = Math.max(0, offset);
        List<SessionRecord> records = sessionRepository.listRecent(safeLimit, safeOffset);
        List<Map<String, Object>> sessions = new ArrayList<Map<String, Object>>();
        for (SessionRecord record : records) {
            sessions.add(toSessionInfo(record));
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("sessions", sessions);
        result.put("total", sessionRepository.countAll());
        result.put("limit", safeLimit);
        result.put("offset", safeOffset);
        return result;
    }

    /**
     * 读取会话Messages。
     *
     * @param sessionId 当前会话标识。
     * @return 返回读取到的会话Messages。
     */
    public Map<String, Object> getSessionMessages(String sessionId) throws Exception {
        SessionRecord record = sessionRepository.findById(sessionId);
        if (record == null) {
            return Collections.singletonMap("messages", Collections.emptyList());
        }

        List<ChatMessage> loadedMessages = MessageSupport.loadMessages(record.getNdjson());
        MessageSupport.dropCurrentSummaryArtifacts(
                loadedMessages, record.getCompressedSummary());
        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        for (ChatMessage message : loadedMessages) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("role", message.getRole().name().toLowerCase(Locale.ROOT));
            String content = message.getContent();
            if (message instanceof AssistantMessage) {
                content = ((AssistantMessage) message).getResultContent();
            }
            item.put("content", SecretRedactor.redact(content, 8000));
            item.put("timestamp", null);

            if (message instanceof AssistantMessage) {
                AssistantMessage assistant = (AssistantMessage) message;
                item.put("reasoning", SecretRedactor.redact(assistant.getReasoning(), 8000));
                if (assistant.getToolCalls() != null && !assistant.getToolCalls().isEmpty()) {
                    List<Map<String, Object>> toolCalls = new ArrayList<Map<String, Object>>();
                    for (ToolCall call : assistant.getToolCalls()) {
                        Map<String, Object> function = new LinkedHashMap<String, Object>();
                        function.put("name", call.getName());
                        function.put(
                                "arguments",
                                SecretRedactor.redact(
                                        StrUtil.blankToDefault(
                                                call.getArgumentsStr(),
                                                ONode.serialize(call.getArguments())),
                                        4000));

                        Map<String, Object> toolCall = new LinkedHashMap<String, Object>();
                        toolCall.put("id", safe(call.getId(), 400));
                        toolCall.put("function", function);
                        toolCalls.add(toolCall);
                    }
                    item.put("tool_calls", toolCalls);
                }
            }

            if (message instanceof ToolMessage) {
                ToolMessage toolMessage = (ToolMessage) message;
                item.put("tool_name", safe(toolMessage.getName(), 400));
                item.put("tool_call_id", safe(toolMessage.getToolCallId(), 400));
            }

            messages.add(item);
        }

        Map<String, Object> result = toSessionInfo(record);
        result.put("session_id", safe(sessionId, 400));
        result.put(
                "model",
                safe(
                        StrUtil.blankToDefault(
                                record.getLastResolvedModel(),
                                StrUtil.blankToDefault(record.getModelOverride(), null)),
                        400));
        result.put(
                "provider",
                safe(StrUtil.blankToDefault(record.getLastResolvedProvider(), null), 400));
        result.put(
                "active_agent_name",
                safe(StrUtil.blankToDefault(record.getActiveAgentName(), "default"), 400));
        result.put("input_tokens", record.getCumulativeInputTokens());
        result.put("output_tokens", record.getCumulativeOutputTokens());
        result.put("reasoning_tokens", record.getCumulativeReasoningTokens());
        result.put("cache_read_tokens", record.getCumulativeCacheReadTokens());
        result.put("cache_write_tokens", record.getCumulativeCacheWriteTokens());
        result.put("total_tokens", record.getCumulativeTotalTokens());
        result.put("last_input_tokens", record.getLastInputTokens());
        result.put("last_output_tokens", record.getLastOutputTokens());
        result.put("last_reasoning_tokens", record.getLastReasoningTokens());
        result.put("last_cache_read_tokens", record.getLastCacheReadTokens());
        result.put("last_cache_write_tokens", record.getLastCacheWriteTokens());
        result.put("last_total_tokens", record.getLastTotalTokens());
        result.put("last_usage_at", record.getLastUsageAt());
        result.put(
                "compressed_summary", SecretRedactor.redact(record.getCompressedSummary(), 8000));
        result.put("last_compression_at", record.getLastCompressionAt());
        result.put("last_compression_input_tokens", record.getLastCompressionInputTokens());
        result.put("compression_failure_count", record.getCompressionFailureCount());
        result.put("parent_session_id", safe(record.getParentSessionId(), 400));
        result.put("branch_name", safe(record.getBranchName(), 400));
        result.put("goal_state", goalState(record));
        result.put("messages", messages);
        return result;
    }

    /**
     * 执行recap相关逻辑。
     *
     * @param sessionId 当前会话标识。
     * @param maxExchanges maxExchanges 参数。
     * @return 返回recap结果。
     */
    public Map<String, Object> recap(String sessionId, int maxExchanges) throws Exception {
        SessionRecord record = sessionRepository.findById(sessionId);
        if (record == null) {
            Map<String, Object> empty = new LinkedHashMap<String, Object>();
            empty.put("session_id", safe(sessionId, 400));
            empty.put("entries", Collections.emptyList());
            empty.put("text", "当前会话不存在。");
            return empty;
        }
        return sessionArtifactService.recap(record, maxExchanges);
    }

    /**
     * 执行trajectory相关逻辑。
     *
     * @param sessionId 当前会话标识。
     * @param userQuery 用户查询参数。
     * @param completed completed 参数。
     * @return 返回trajectory结果。
     */
    public Map<String, Object> trajectory(String sessionId, String userQuery, boolean completed)
            throws Exception {
        SessionRecord record = sessionRepository.findById(sessionId);
        if (record == null) {
            Map<String, Object> empty = new LinkedHashMap<String, Object>();
            empty.put("session_id", safe(sessionId, 400));
            empty.put("completed", Boolean.valueOf(completed));
            empty.put("conversations", Collections.emptyList());
            return empty;
        }
        return sessionArtifactService.trajectory(record, userQuery, completed);
    }

    /**
     * 保存Trajectory。
     *
     * @param sessionId 当前会话标识。
     * @param userQuery 用户查询参数。
     * @param completed completed 参数。
     * @return 返回Trajectory结果。
     */
    public Map<String, Object> saveTrajectory(String sessionId, String userQuery, boolean completed)
            throws Exception {
        SessionRecord record = sessionRepository.findById(sessionId);
        if (record == null) {
            Map<String, Object> empty = new LinkedHashMap<String, Object>();
            empty.put("session_id", safe(sessionId, 400));
            empty.put("saved", Boolean.FALSE);
            empty.put("error", "当前会话不存在。");
            return empty;
        }
        Map<String, Object> saved =
                sessionArtifactService.saveTrajectory(record, userQuery, completed);
        saved.put("saved", Boolean.TRUE);
        return saved;
    }

    /**
     * 删除会话。
     *
     * @param sessionId 当前会话标识。
     * @return 返回会话结果。
     */
    public Map<String, Object> deleteSession(String sessionId) throws Exception {
        sessionRepository.delete(sessionId);
        return Collections.singletonMap("ok", true);
    }

    /**
     * 更新会话。
     *
     * @param sessionId 当前会话标识。
     * @param body 请求体或消息正文内容。
     * @return 返回会话结果。
     */
    public Map<String, Object> updateSession(String sessionId, Map<String, Object> body)
            throws Exception {
        SessionRecord record = sessionRepository.findById(sessionId);
        if (record == null) {
            throw new IllegalArgumentException("session not found: " + safe(sessionId, 400));
        }
        if (body == null || !body.containsKey("title")) {
            throw new IllegalArgumentException("title is required");
        }
        String title = StrUtil.nullToEmpty(String.valueOf(body.get("title"))).trim();
        if (title.length() > 120) {
            title = title.substring(0, 120);
        }
        record.setTitle(title);
        record.setUpdatedAt(System.currentTimeMillis());
        sessionRepository.save(record);
        return toSessionInfo(sessionRepository.findById(sessionId));
    }

    /**
     * 执行会话Tree相关逻辑。
     *
     * @param sessionId 当前会话标识。
     * @return 返回会话Tree结果。
     */
    public Map<String, Object> sessionTree(String sessionId) throws Exception {
        SessionRecord root = sessionRepository.findById(sessionId);
        if (root == null) {
            return Collections.singletonMap("nodes", Collections.emptyList());
        }
        List<SessionRecord> lineage = sessionRepository.listLineage(root.getSessionId());
        List<Map<String, Object>> nodes = new ArrayList<Map<String, Object>>();
        for (SessionRecord record : lineage) {
            Map<String, Object> node = toSessionInfo(record);
            node.put("parent_session_id", safe(record.getParentSessionId(), 400));
            node.put("branch_name", safe(record.getBranchName(), 400));
            nodes.add(node);
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("root_session_id", safe(sessionId, 400));
        result.put("nodes", nodes);
        return result;
    }

    /**
     * 执行latestDescendant相关逻辑。
     *
     * @param sessionId 当前会话标识。
     * @return 返回latest Descendant结果。
     */
    public Map<String, Object> latestDescendant(String sessionId) throws Exception {
        SessionRecord root = sessionRepository.findById(sessionId);
        if (root == null) {
            Map<String, Object> missing = new LinkedHashMap<String, Object>();
            missing.put("requested_session_id", safe(sessionId, 400));
            missing.put("session_id", null);
            missing.put("path", Collections.emptyList());
            missing.put("changed", Boolean.FALSE);
            return missing;
        }

        List<String> path = sessionRepository.latestDescendantPath(root.getSessionId());
        String current = path.isEmpty() ? root.getSessionId() : path.get(path.size() - 1);

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("requested_session_id", safe(root.getSessionId(), 400));
        result.put("session_id", safe(current, 400));
        result.put("path", safeList(path, 400));
        result.put("changed", Boolean.valueOf(!root.getSessionId().equals(current)));
        return result;
    }

    /**
     * 执行checkpoints相关逻辑。
     *
     * @param sessionId 当前会话标识。
     * @return 返回checkpoints结果。
     */
    public Map<String, Object> checkpoints(String sessionId) throws Exception {
        if (checkpointService == null) {
            return Collections.singletonMap("checkpoints", Collections.emptyList());
        }
        SessionRecord record = sessionRepository.findById(sessionId);
        if (record == null) {
            return Collections.singletonMap("checkpoints", Collections.emptyList());
        }
        List<Map<String, Object>> checkpoints = new ArrayList<Map<String, Object>>();
        for (CheckpointRecord checkpoint :
                checkpointService.listRecent(record.getSourceKey(), 50)) {
            checkpoints.add(toCheckpoint(checkpoint));
        }
        return Collections.singletonMap("checkpoints", checkpoints);
    }

    /**
     * 执行检查点预览相关逻辑。
     *
     * @param checkpointId checkpoint标识。
     * @return 返回检查点Preview结果。
     */
    public Map<String, Object> checkpointPreview(String checkpointId) throws Exception {
        if (checkpointService == null) {
            return Collections.emptyMap();
        }
        return checkpointService.preview(checkpointId);
    }

    /**
     * 执行回滚检查点相关逻辑。
     *
     * @param checkpointId checkpoint标识。
     * @return 返回回滚检查点结果。
     */
    public Map<String, Object> rollbackCheckpoint(String checkpointId) throws Exception {
        if (checkpointService == null) {
            throw new IllegalStateException("checkpoint service is not configured");
        }
        CheckpointRecord record = checkpointService.rollback(checkpointId);
        return toCheckpoint(record);
    }

    /**
     * 转换为会话Info。
     *
     * @param record 记录参数。
     * @return 返回转换后的会话Info。
     */
    private Map<String, Object> toSessionInfo(SessionRecord record) throws Exception {
        List<ChatMessage> messages = MessageSupport.loadMessages(record.getNdjson());
        int toolCallCount = 0;
        for (ChatMessage message : messages) {
            if (message instanceof AssistantMessage) {
                AssistantMessage assistant = (AssistantMessage) message;
                if (assistant.getToolCalls() != null) {
                    toolCallCount += assistant.getToolCalls().size();
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", safe(record.getSessionId(), 400));
        result.put("source", parseSource(record.getSourceKey()));
        result.put(
                "model",
                safe(
                        StrUtil.blankToDefault(
                                record.getLastResolvedModel(),
                                StrUtil.blankToDefault(record.getModelOverride(), null)),
                        400));
        result.put(
                "provider",
                safe(StrUtil.blankToDefault(record.getLastResolvedProvider(), null), 400));
        result.put(
                "active_agent_name",
                safe(StrUtil.blankToDefault(record.getActiveAgentName(), "default"), 400));
        result.put("title", safe(record.getTitle(), 400));
        result.put("started_at", record.getCreatedAt());
        result.put("ended_at", null);
        result.put("last_active", record.getUpdatedAt());
        result.put(
                "is_active",
                record.getUpdatedAt() >= System.currentTimeMillis() - 5L * 60L * 1000L);
        result.put("message_count", messages.size());
        result.put("tool_call_count", toolCallCount);
        result.put("input_tokens", record.getCumulativeInputTokens());
        result.put("output_tokens", record.getCumulativeOutputTokens());
        result.put("reasoning_tokens", record.getCumulativeReasoningTokens());
        result.put("cache_read_tokens", record.getCumulativeCacheReadTokens());
        result.put("cache_write_tokens", record.getCumulativeCacheWriteTokens());
        result.put("total_tokens", record.getCumulativeTotalTokens());
        result.put("last_total_tokens", record.getLastTotalTokens());
        result.put("last_usage_at", record.getLastUsageAt());
        result.put("parent_session_id", safe(record.getParentSessionId(), 400));
        result.put("branch_name", safe(record.getBranchName(), 400));
        result.put("goal_state", goalState(record));
        result.put(
                "compressed_summary", SecretRedactor.redact(record.getCompressedSummary(), 8000));
        result.put("last_compression_at", record.getLastCompressionAt());
        result.put("last_compression_input_tokens", record.getLastCompressionInputTokens());
        result.put("compression_failure_count", record.getCompressionFailureCount());
        result.put(
                "preview",
                safe(
                        trim(
                                StrUtil.blankToDefault(
                                        MessageSupport.getLastUserMessage(record.getNdjson()),
                                        record.getCompressedSummary()),
                                160),
                        160));
        return result;
    }

    /**
     * 执行目标状态相关逻辑。
     *
     * @param record 记录参数。
     * @return 返回goal状态。
     */
    private Map<String, Object> goalState(SessionRecord record) {
        GoalState state = GoalState.fromJson(record.getGoalStateJson());
        if (state == null || GoalState.STATUS_CLEARED.equals(state.getStatus())) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("goal", SecretRedactor.redact(state.getGoal(), 2000));
        result.put("status", state.getStatus());
        result.put("turns_used", state.getTurnsUsed());
        result.put("max_turns", state.getMaxTurns());
        result.put("created_at", state.getCreatedAt());
        result.put("last_turn_at", state.getLastTurnAt());
        result.put("last_verdict", state.getLastVerdict());
        result.put("last_reason", SecretRedactor.redact(state.getLastReason(), 2000));
        result.put("paused_reason", SecretRedactor.redact(state.getPausedReason(), 1000));
        return result;
    }

    /**
     * 转换为检查点。
     *
     * @param checkpoint checkpoint 参数。
     * @return 返回转换后的检查点。
     */
    private Map<String, Object> toCheckpoint(CheckpointRecord checkpoint) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("checkpoint_id", safe(checkpoint.getCheckpointId(), 400));
        item.put("source_key", safe(checkpoint.getSourceKey(), 400));
        item.put("session_id", safe(checkpoint.getSessionId(), 400));
        item.put("created_at", checkpoint.getCreatedAt());
        item.put("restored_at", checkpoint.getRestoredAt());
        return item;
    }

    /**
     * 解析来源。
     *
     * @param sourceKey 渠道来源键。
     * @return 返回解析后的来源。
     */
    private String parseSource(String sourceKey) {
        String[] parts = SourceKeySupport.split(sourceKey);
        if ("MEMORY".equalsIgnoreCase(parts[0])) {
            return "local";
        }
        return parts[0].toLowerCase(Locale.ROOT);
    }

    /**
     * 执行trim相关逻辑。
     *
     * @param text 待处理文本。
     * @param limit 最大返回数量。
     * @return 返回trim结果。
     */
    private String trim(String text, int limit) {
        String normalized = StrUtil.nullToEmpty(text).replace('\n', ' ').trim();
        if (normalized.length() <= limit) {
            return normalized;
        }
        return normalized.substring(0, limit) + "...";
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

    /**
     * 生成安全展示用的列表。
     *
     * @param values 待规范化或校验的原始值集合。
     * @param maxLength 最大保留字符数。
     * @return 返回safe List结果。
     */
    private List<String> safeList(List<String> values, int maxLength) {
        List<String> result = new ArrayList<String>();
        if (values == null) {
            return result;
        }
        for (String value : values) {
            result.add(safe(value, maxLength));
        }
        return result;
    }
}
