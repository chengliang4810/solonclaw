package com.jimuqu.solon.claw.storage.session;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.support.MessageSupport;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.FlowContextInternal;

/** 基于现有 SessionRecord / SQLite 的 AgentSession 适配层。 */
public class SqliteAgentSession implements AgentSession {
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
     * 追加消息。
     *
     * @param messages messages 参数。
     */
    @Override
    public void addMessage(Collection<? extends ChatMessage> messages) {
        cache.addMessage(messages);
        syncRecord(false);
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
                MessageSupport.repairMessageSequence(messages, isPending());
                cache.addMessage(messages);
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
        } catch (Throwable ignored) {
            // 这里保留兜底路径，避免兼容输入导致主流程中断。
        }
        return FlowContext.of(sessionRecord.getSessionId());
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
        } catch (Exception ignored) {
            return 0L;
        }
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
