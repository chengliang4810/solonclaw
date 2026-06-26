package com.jimuqu.solon.claw.storage.session;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.support.MessageSupport;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.FlowContextInternal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 基于现有 SessionRecord / SQLite 的 AgentSession 适配层。 */
public class SqliteAgentSession implements AgentSession {
    /** 记录会话快照恢复降级的低敏诊断日志，不输出会话内容或快照 JSON。 */
    private static final Logger log = LoggerFactory.getLogger(SqliteAgentSession.class);

    /** META待恢复的统一常量值。 */
    private static final String META_PENDING = "_agent_pending_";

    /** META待恢复原因的统一常量值。 */
    private static final String META_PENDING_REASON = "_pending_reason_";

    /** META待恢复MARKED时间的统一常量值。 */
    private static final String META_PENDING_MARKED_AT = "_pending_marked_at_";

    /** META待恢复CLEARED时间的统一常量值。 */
    private static final String META_PENDING_CLEARED_AT = "_pending_cleared_at_";

    /** META待恢复LAST原因的统一常量值。 */
    private static final String META_PENDING_LAST_REASON = "_pending_last_reason_";

    /** STOP循环历史的统一常量值。 */
    private static final String STOP_LOOP_HISTORY = "stoploop_history";

    /** 记录SQLiteAgent会话中的会话记录。 */
    private final SessionRecord sessionRecord;

    /** 保存会话仓储依赖，用于访问持久化数据。 */
    private final SessionRepository sessionRepository;

    /** 记录SQLiteAgent会话中的缓存。 */
    private final InMemoryAgentSession cache;

    /**
     * 创建SQLite Agent会话实例，并注入运行所需依赖。
     *
     * @param sessionRecord 会话记录参数。
     */
    public SqliteAgentSession(SessionRecord sessionRecord) {
        this(sessionRecord, null);
    }

    /**
     * 创建SQLite Agent会话实例，并注入运行所需依赖。
     *
     * @param sessionRecord 会话记录参数。
     * @param sessionRepository 会话仓储依赖。
     */
    public SqliteAgentSession(SessionRecord sessionRecord, SessionRepository sessionRepository) {
        if (sessionRecord == null || StrUtil.isBlank(sessionRecord.getSessionId())) {
            throw new IllegalArgumentException("SessionRecord with sessionId is required");
        }
        this.sessionRecord = sessionRecord;
        this.sessionRepository = sessionRepository;
        this.cache = new InMemoryAgentSession(loadContext(sessionRecord));
        this.cache.getContext().put(Agent.KEY_SESSION, this);
        this.cache.getContext().put("source_key", sessionRecord.getSourceKey());
        this.cache.getContext().put("parent_session_id", sessionRecord.getParentSessionId());
        loadMessages(sessionRecord);
    }

    /**
     * 读取会话标识。
     *
     * @return 返回读取到的会话标识。
     */
    @Override
    public String getSessionId() {
        return cache.getSessionId();
    }

    /**
     * 读取Messages。
     *
     * @return 返回读取到的Messages。
     */
    @Override
    public List<ChatMessage> getMessages() {
        return cache.getMessages();
    }

    /**
     * 读取Latest Messages。
     *
     * @param windowSize 窗口Size参数。
     * @return 返回读取到的Latest Messages。
     */
    @Override
    public List<ChatMessage> getLatestMessages(int windowSize) {
        return cache.getLatestMessages(windowSize);
    }

    /**
     * 删除最近的若干条消息，供 Solon AI 4 在 retry/回滚模型输出时同步收缩 SQLite 会话历史。
     *
     * @param count 需要从尾部移除的消息数量。
     */
    @Override
    public void removeLatestMessage(int count) {
        if (count <= 0) {
            return;
        }
        List<ChatMessage> messages = cache.getMessages();
        if (messages == null || messages.isEmpty()) {
            return;
        }
        int removed = 0;
        while (removed < count && !messages.isEmpty()) {
            messages.remove(messages.size() - 1);
            removed++;
        }
        if (removed > 0) {
            syncRecord(false);
        }
    }

    /**
     * 追加消息。
     *
     * @param messages messages 参数。
     */
    @Override
    public void addMessage(Collection<? extends ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        boolean changed = false;
        for (ChatMessage message : messages) {
            changed = addMessageIfChanged(message) || changed;
        }
        if (changed) {
            syncRecord(false);
        }
    }

    /**
     * 判断是否Empty。
     *
     * @return 如果Empty满足条件则返回 true，否则返回 false。
     */
    @Override
    public boolean isEmpty() {
        return cache.isEmpty();
    }

    /** 执行clear相关逻辑。 */
    @Override
    public void clear() {
        cache.clear();
        syncRecord(true);
    }

    /**
     * 执行attrs相关逻辑。
     *
     * @return 返回attrs结果。
     */
    @Override
    public Map<String, Object> attrs() {
        return cache.attrs();
    }

    /** 更新Snapshot。 */
    @Override
    public void updateSnapshot() {
        syncRecord(true);
    }

    /**
     * 读取上下文。
     *
     * @return 返回读取到的上下文。
     */
    @Override
    public FlowContext getContext() {
        return cache.getContext();
    }

    /**
     * 将最近一条用户消息恢复为原始输入，避免模型请求期注入的内部上下文进入持久化会话。
     *
     * @param expectedInjectedContent 预期的注入后内容，用于避免误改历史用户消息。
     * @param originalUserMessage 用户真实输入内容。
     * @return 如果替换成功返回 true，否则返回 false。
     */
    public boolean replaceLastUserMessage(
            String expectedInjectedContent, String originalUserMessage) {
        if (StrUtil.isBlank(expectedInjectedContent)) {
            return false;
        }
        List<ChatMessage> messages = cache.getMessages();
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message == null || message.getRole() != ChatRole.USER) {
                continue;
            }
            if (!StrUtil.equals(message.getContent(), expectedInjectedContent)) {
                return false;
            }
            if (message instanceof UserMessage && !((UserMessage) message).getBlocks().isEmpty()) {
                messages.set(
                        i,
                        ChatMessage.ofUser(
                                StrUtil.nullToEmpty(originalUserMessage),
                                ((UserMessage) message).getBlocks()));
            } else {
                messages.set(i, ChatMessage.ofUser(StrUtil.nullToEmpty(originalUserMessage)));
            }
            syncRecord(false);
            return true;
        }
        return false;
    }

    /**
     * 执行待恢复相关逻辑。
     *
     * @param pending 待恢复参数。
     * @param reason 原因参数。
     */
    @Override
    public void pending(boolean pending, String reason) {
        String previousReason = getPendingReason();
        AgentSession.super.pending(pending, reason);
        long now = System.currentTimeMillis();
        cache.getContext().put(META_PENDING, pending);
        if (pending) {
            cache.getContext().put(META_PENDING_MARKED_AT, Long.valueOf(now));
            cache.getContext().remove(META_PENDING_CLEARED_AT);
            if (reason == null) {
                cache.getContext().remove(META_PENDING_REASON);
                cache.getContext().remove(META_PENDING_LAST_REASON);
            } else {
                cache.getContext().put(META_PENDING_REASON, reason);
                cache.getContext().put(META_PENDING_LAST_REASON, reason);
            }
        } else {
            String lastReason = StrUtil.blankToDefault(reason, previousReason);
            cache.getContext().remove(META_PENDING_REASON);
            cache.getContext().put(META_PENDING_CLEARED_AT, Long.valueOf(now));
            if (StrUtil.isNotBlank(lastReason)) {
                cache.getContext().put(META_PENDING_LAST_REASON, lastReason);
            }
        }
    }

    /**
     * 判断是否Pending。
     *
     * @return 如果Pending满足条件则返回 true，否则返回 false。
     */
    @Override
    public boolean isPending() {
        return isTruthy(cache.getContext().get(META_PENDING)) || AgentSession.super.isPending();
    }

    /**
     * 读取Pending Reason。
     *
     * @return 返回读取到的Pending Reason。
     */
    @Override
    public String getPendingReason() {
        Object reason = cache.getContext().get(META_PENDING_REASON);
        return reason == null ? AgentSession.super.getPendingReason() : String.valueOf(reason);
    }

    /**
     * 读取Pending Marked时间。
     *
     * @return 返回读取到的Pending Marked时间。
     */
    public long getPendingMarkedAt() {
        return longValue(cache.getContext().get(META_PENDING_MARKED_AT));
    }

    /**
     * 读取Pending Cleared时间。
     *
     * @return 返回读取到的Pending Cleared时间。
     */
    public long getPendingClearedAt() {
        return longValue(cache.getContext().get(META_PENDING_CLEARED_AT));
    }

    /**
     * 读取Pending Last Reason。
     *
     * @return 返回读取到的Pending Last Reason。
     */
    public String getPendingLastReason() {
        Object reason = cache.getContext().get(META_PENDING_LAST_REASON);
        return reason == null ? null : String.valueOf(reason);
    }

    /**
     * 加载Messages。
     *
     * @param sessionRecord 会话记录参数。
     */
    private void loadMessages(SessionRecord sessionRecord) {
        try {
            if (StrUtil.isNotBlank(sessionRecord.getNdjson())) {
                List<ChatMessage> messages = MessageSupport.loadMessages(sessionRecord.getNdjson());
                int repairs =
                        MessageSupport.dropCurrentSummaryArtifacts(
                                messages, sessionRecord.getCompressedSummary());
                repairs += MessageSupport.repairMessageSequence(messages, isPending());
                cache.addMessage(messages);
                if (repairs > 0) {
                    syncRecord(sessionRepository != null);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to load session ndjson: " + sessionRecord.getSessionId(), e);
        }
    }

    /**
     * 加载上下文。
     *
     * @param sessionRecord 会话记录参数。
     * @return 返回上下文结果。
     */
    private FlowContext loadContext(SessionRecord sessionRecord) {
        try {
            if (StrUtil.isNotBlank(sessionRecord.getAgentSnapshotJson())) {
                FlowContext context = FlowContext.fromJson(sessionRecord.getAgentSnapshotJson());
                normalizeSnapshotTypes(context);
                if (isTruthy(context.get(META_PENDING))) {
                    if (context instanceof FlowContextInternal) {
                        ((FlowContextInternal) context).stopped(true);
                    } else {
                        context.stop();
                    }
                }
                return context;
            }
        } catch (Throwable e) {
            log.warn("Agent会话快照恢复失败，重建空上下文 sessionId={} error={}",
                    safeSessionId(sessionRecord),
                    e.getClass().getSimpleName());
        }
        return FlowContext.of(sessionRecord.getSessionId());
    }

    /**
     * 追加单条消息，并过滤 Solon AI 流式工具调用在聚合结束时可能重复写入的 assistant tool_call。
     *
     * @param message 待追加的消息。
     * @return 如果会话消息发生变化则返回 true。
     */
    private boolean addMessageIfChanged(ChatMessage message) {
        if (message == null) {
            return false;
        }
        List<ChatMessage> messages = cache.getMessages();
        if (messages != null && !messages.isEmpty()) {
            int lastIndex = messages.size() - 1;
            ChatMessage previous = messages.get(lastIndex);
            if (sameAssistantToolCalls(previous, message)) {
                if (MessageSupport.assistantInformationScore((AssistantMessage) message)
                        > MessageSupport.assistantInformationScore((AssistantMessage) previous)) {
                    messages.set(lastIndex, message);
                    return true;
                }
                return false;
            }
        }

        int before = messages == null ? 0 : messages.size();
        cache.addMessage(Collections.singletonList(message));
        return cache.getMessages().size() != before;
    }

    /**
     * 判断两条 assistant 消息是否承载同一组工具调用；相同工具调用只能在历史中保留一次。
     *
     * @param previous 已存在的消息。
     * @param incoming 待追加的消息。
     * @return 如果两条消息代表同一批工具调用则返回 true。
     */
    private boolean sameAssistantToolCalls(ChatMessage previous, ChatMessage incoming) {
        if (!(previous instanceof AssistantMessage) || !(incoming instanceof AssistantMessage)) {
            return false;
        }
        List<ToolCall> previousCalls = ((AssistantMessage) previous).getToolCalls();
        List<ToolCall> incomingCalls = ((AssistantMessage) incoming).getToolCalls();
        if (previousCalls == null
                || incomingCalls == null
                || previousCalls.isEmpty()
                || previousCalls.size() != incomingCalls.size()) {
            return false;
        }
        for (int i = 0; i < previousCalls.size(); i++) {
            if (!sameToolCall(previousCalls.get(i), incomingCalls.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 比较工具调用签名，避免同一个 tool_call_id 在相邻 assistant 消息中重复落盘。
     *
     * @param previous 已存在的工具调用。
     * @param incoming 待追加的工具调用。
     * @return 如果工具调用签名一致则返回 true。
     */
    private boolean sameToolCall(ToolCall previous, ToolCall incoming) {
        if (previous == null || incoming == null) {
            return false;
        }
        return StrUtil.equals(previous.getIndex(), incoming.getIndex())
                && StrUtil.equals(previous.getId(), incoming.getId())
                && StrUtil.equals(previous.getName(), incoming.getName())
                && StrUtil.equals(previous.getArgumentsStr(), incoming.getArgumentsStr())
                && StrUtil.equals(
                        String.valueOf(previous.getArguments()),
                        String.valueOf(incoming.getArguments()));
    }

    /**
     * 规范化Snapshot Types。
     *
     * @param context 当前请求或运行上下文。
     */
    private void normalizeSnapshotTypes(FlowContext context) {
        if (context == null) {
            return;
        }
        for (Object value : context.vars().values()) {
            if (value instanceof ReActTrace) {
                normalizeTraceExtras((ReActTrace) value);
            }
        }
    }

    /**
     * 规范化Trace Extras。
     *
     * @param trace trace 参数。
     */
    private void normalizeTraceExtras(ReActTrace trace) {
        Object history = trace.getExtra(STOP_LOOP_HISTORY);
        if (history instanceof Collection && !(history instanceof LinkedList)) {
            LinkedList<String> normalized = new LinkedList<String>();
            for (Object item : (Collection<?>) history) {
                if (item != null) {
                    normalized.add(String.valueOf(item));
                }
            }
            trace.setExtra(STOP_LOOP_HISTORY, normalized);
        }
    }

    /**
     * 执行同步记录相关逻辑。
     *
     * @param persist persist 参数。
     */
    private void syncRecord(boolean persist) {
        try {
            repairMessagesBeforePersist();
            sessionRecord.setNdjson(ChatMessage.toNdjson(cache.getMessages()));
            cache.getContext().put(META_PENDING, isPending());
            sessionRecord.setAgentSnapshotJson(cache.getContext().toJson());
            sessionRecord.setUpdatedAt(System.currentTimeMillis());
            if (persist && sessionRepository != null) {
                sessionRepository.save(sessionRecord);
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to sync sqlite agent session: " + sessionRecord.getSessionId(), e);
        }
    }

    /** 写回会话快照前先清理协议层重复消息，但保留尚未落盘工具结果的待回答 tool_call。 */
    private void repairMessagesBeforePersist() {
        MessageSupport.repairMessageSequence(cache.getMessages(), true);
    }

    /**
     * 将输入对象转换为长整型数值。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回long Value结果。
     */
    private long longValue(Object value) {
        if (value instanceof Number) {
            return Math.max(0L, ((Number) value).longValue());
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(String.valueOf(value).trim()));
        } catch (Exception e) {
            log.debug("会话快照数值字段解析失败，使用0兜底 error={}", e.getClass().getSimpleName());
            return 0L;
        }
    }

    /**
     * 生成安全展示用会话标识，避免日志中出现过长或异常文本。
     *
     * @param sessionRecord 会话记录参数。
     * @return 返回截断后的会话标识。
     */
    private String safeSessionId(SessionRecord sessionRecord) {
        String sessionId = sessionRecord == null ? "" : StrUtil.nullToEmpty(sessionRecord.getSessionId());
        return sessionId.length() > 64 ? sessionId.substring(0, 64) : sessionId;
    }

    /**
     * 判断配置值是否表达启用语义。
     *
     * @param value 待规范化或校验的原始值。
     * @return 如果Truthy满足条件则返回 true，否则返回 false。
     */
    private boolean isTruthy(Object value) {
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }
        return value != null && "true".equalsIgnoreCase(String.valueOf(value).trim());
    }
}
