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
    private static final String META_PENDING = "_agent_pending_";
    private static final String META_PENDING_REASON = "_pending_reason_";
    private static final String META_PENDING_MARKED_AT = "_pending_marked_at_";
    private static final String META_PENDING_CLEARED_AT = "_pending_cleared_at_";
    private static final String META_PENDING_LAST_REASON = "_pending_last_reason_";
    private static final String STOP_LOOP_HISTORY = "stoploop_history";

    private final SessionRecord sessionRecord;
    private final SessionRepository sessionRepository;
    private final InMemoryAgentSession cache;

    public SqliteAgentSession(SessionRecord sessionRecord) {
        this(sessionRecord, null);
    }

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

    @Override
    public String getSessionId() {
        return cache.getSessionId();
    }

    @Override
    public List<ChatMessage> getMessages() {
        return cache.getMessages();
    }

    @Override
    public List<ChatMessage> getLatestMessages(int windowSize) {
        return cache.getLatestMessages(windowSize);
    }

    @Override
    public void addMessage(Collection<? extends ChatMessage> messages) {
        cache.addMessage(messages);
        syncRecord(false);
    }

    @Override
    public boolean isEmpty() {
        return cache.isEmpty();
    }

    @Override
    public void clear() {
        cache.clear();
        syncRecord(true);
    }

    @Override
    public Map<String, Object> attrs() {
        return cache.attrs();
    }

    @Override
    public void updateSnapshot() {
        syncRecord(true);
    }

    @Override
    public FlowContext getContext() {
        return cache.getContext();
    }

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

    @Override
    public boolean isPending() {
        return isTruthy(cache.getContext().get(META_PENDING)) || AgentSession.super.isPending();
    }

    @Override
    public String getPendingReason() {
        Object reason = cache.getContext().get(META_PENDING_REASON);
        return reason == null ? AgentSession.super.getPendingReason() : String.valueOf(reason);
    }

    public long getPendingMarkedAt() {
        return longValue(cache.getContext().get(META_PENDING_MARKED_AT));
    }

    public long getPendingClearedAt() {
        return longValue(cache.getContext().get(META_PENDING_CLEARED_AT));
    }

    public String getPendingLastReason() {
        Object reason = cache.getContext().get(META_PENDING_LAST_REASON);
        return reason == null ? null : String.valueOf(reason);
    }

    private void loadMessages(SessionRecord sessionRecord) {
        try {
            if (StrUtil.isNotBlank(sessionRecord.getNdjson())) {
                List<ChatMessage> messages = MessageSupport.loadMessages(sessionRecord.getNdjson());
                MessageSupport.repairMessageSequence(messages);
                cache.addMessage(messages);
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to load session ndjson: " + sessionRecord.getSessionId(), e);
        }
    }

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
            // fallback to a fresh context if the old snapshot cannot be restored
        }
        return FlowContext.of(sessionRecord.getSessionId());
    }

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

    private boolean isTruthy(Object value) {
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }
        return value != null && "true".equalsIgnoreCase(String.valueOf(value).trim());
    }
}
