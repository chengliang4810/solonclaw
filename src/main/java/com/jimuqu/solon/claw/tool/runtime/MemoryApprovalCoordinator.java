package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** 协调长期记忆暂存项与前台终端 UI 的一次性审批响应。 */
public class MemoryApprovalCoordinator {
    /** 工具与终端监听器共享的协调器。 */
    private static final MemoryApprovalCoordinator SHARED =
            new MemoryApprovalCoordinator(TimeUnit.MINUTES.toMillis(5L));

    /** 会话到当前终端连接的绑定。 */
    private final Map<String, SessionBinding> bindings =
            new ConcurrentHashMap<String, SessionBinding>();

    /** 审批标识到等待请求的映射。 */
    private final Map<String, PendingRequest> pendingRequests =
            new ConcurrentHashMap<String, PendingRequest>();

    /** 单次审批最大等待时长。 */
    private final long timeoutMillis;

    /** 创建使用默认五分钟超时的协调器。 */
    public MemoryApprovalCoordinator() {
        this(TimeUnit.MINUTES.toMillis(5L));
    }

    /** 创建可指定超时的协调器，供聚焦测试使用。 */
    public MemoryApprovalCoordinator(long timeoutMillis) {
        this.timeoutMillis = Math.max(1L, timeoutMillis);
    }

    /** 返回工具与终端监听器共享的默认协调器。 */
    public static MemoryApprovalCoordinator shared() {
        return SHARED;
    }

    /** 判断当前会话是否仍绑定可用的前台终端连接。 */
    public boolean canRequest(String sessionId) {
        return bindings.containsKey(StrUtil.nullToEmpty(sessionId).trim());
    }

    /** 将会话绑定到当前终端连接及事件发送器。 */
    public void bindSession(String sessionId, Object owner, RequestEmitter emitter) {
        String normalized = required(sessionId, "session_id is required");
        if (owner == null || emitter == null) {
            throw new IllegalArgumentException("memory approval transport is required");
        }
        SessionBinding replacement = new SessionBinding(owner, emitter);
        SessionBinding previous = bindings.put(normalized, replacement);
        if (previous != null && previous.owner != owner) {
            clearPending(normalized, previous.owner);
        }
    }

    /** 发出前台记忆审批并等待一次性决定；任何传输故障均返回不可用以保留暂存项。 */
    public Decision request(String sessionId, String pendingId, String summary, String detail) {
        String normalizedSession = required(sessionId, "session_id is required");
        String approvalId = "memory:" + required(pendingId, "pending_id is required");
        SessionBinding binding = bindings.get(normalizedSession);
        if (binding == null) {
            return Decision.UNAVAILABLE;
        }
        PendingRequest pending = new PendingRequest(normalizedSession, binding.owner);
        if (pendingRequests.putIfAbsent(approvalId, pending) != null) {
            return Decision.UNAVAILABLE;
        }
        try {
            if (bindings.get(normalizedSession) != binding) {
                return Decision.UNAVAILABLE;
            }
            binding.emitter.emit(
                    new ApprovalRequest(
                            approvalId,
                            normalizedSession,
                            StrUtil.nullToEmpty(summary),
                            StrUtil.nullToEmpty(detail)));
            return pending.decision.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Decision.UNAVAILABLE;
        } catch (Exception e) {
            return Decision.UNAVAILABLE;
        } finally {
            pendingRequests.remove(approvalId, pending);
        }
    }

    /** 响应仍在等待的记忆审批；非记忆标识返回 false 让其他审批流程继续处理。 */
    public boolean respondIfPending(
            String sessionId, String approvalId, String choice, Object owner) {
        if (!StrUtil.startWith(approvalId, "memory:")) {
            return false;
        }
        PendingRequest pending = pendingRequests.get(approvalId);
        if (pending == null) {
            throw new IllegalArgumentException("no pending memory approval");
        }
        if (!required(sessionId, "session_id is required").equals(pending.sessionId)) {
            throw new IllegalArgumentException("memory approval belongs to another session");
        }
        if (owner == null || pending.owner != owner) {
            throw new IllegalArgumentException("memory approval belongs to another connection");
        }
        String normalized = StrUtil.nullToEmpty(choice).trim().toLowerCase();
        Decision decision;
        if ("deny".equals(normalized)) {
            decision = Decision.DENY;
        } else if ("once".equals(normalized)) {
            decision = Decision.APPROVE;
        } else {
            throw new IllegalArgumentException("memory approval only supports once or deny");
        }
        if (!pendingRequests.remove(approvalId, pending)) {
            throw new IllegalArgumentException("no pending memory approval");
        }
        pending.decision.complete(decision);
        return true;
    }

    /** 清理断开连接拥有的绑定，并以不可用释放其等待请求。 */
    public void clearOwner(Object owner) {
        if (owner == null) {
            return;
        }
        for (Map.Entry<String, SessionBinding> entry : bindings.entrySet()) {
            if (entry.getValue().owner == owner) {
                bindings.remove(entry.getKey(), entry.getValue());
            }
        }
        clearPending(null, owner);
    }

    /** 释放匹配会话和连接的等待请求。 */
    private void clearPending(String sessionId, Object owner) {
        for (Map.Entry<String, PendingRequest> entry : pendingRequests.entrySet()) {
            PendingRequest pending = entry.getValue();
            if ((sessionId == null || sessionId.equals(pending.sessionId))
                    && (owner == null || owner == pending.owner)
                    && pendingRequests.remove(entry.getKey(), pending)) {
                pending.decision.complete(Decision.UNAVAILABLE);
            }
        }
    }

    /** 校验并规范化协议必填字符串。 */
    private String required(String value, String message) {
        String normalized = StrUtil.nullToEmpty(value).trim();
        if (StrUtil.isBlank(normalized)) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }

    /** 前台记忆审批决定。 */
    public enum Decision {
        APPROVE,
        DENY,
        UNAVAILABLE
    }

    /** 终端监听器实现的审批事件发送器。 */
    public interface RequestEmitter {
        /** 将审批请求发送给当前会话终端。 */
        void emit(ApprovalRequest request) throws Exception;
    }

    /** 发送到终端 UI 的只读记忆审批请求。 */
    public static final class ApprovalRequest {
        /** 前端回传使用的记忆审批标识。 */
        private final String approvalId;

        /** 审批所属会话标识。 */
        private final String sessionId;

        /** 展示给用户的动作摘要。 */
        private final String summary;

        /** 展示给用户核对的变更详情。 */
        private final String detail;

        /** 创建完整审批载荷。 */
        private ApprovalRequest(
                String approvalId, String sessionId, String summary, String detail) {
            this.approvalId = approvalId;
            this.sessionId = sessionId;
            this.summary = TerminalAnsiSanitizer.stripAnsi(summary);
            this.detail = TerminalAnsiSanitizer.stripAnsi(detail);
        }

        /** 返回审批标识。 */
        public String getApprovalId() {
            return approvalId;
        }

        /** 返回会话标识。 */
        public String getSessionId() {
            return sessionId;
        }

        /** 返回动作摘要。 */
        public String getSummary() {
            return summary;
        }

        /** 返回待写入详情。 */
        public String getDetail() {
            return detail;
        }
    }

    /** 会话绑定。 */
    private static final class SessionBinding {
        /** 绑定连接的身份对象。 */
        private final Object owner;

        /** 向绑定连接发送审批事件的回调。 */
        private final RequestEmitter emitter;

        /** 保存绑定连接及发送器。 */
        private SessionBinding(Object owner, RequestEmitter emitter) {
            this.owner = owner;
            this.emitter = emitter;
        }
    }

    /** 等待中的一次性审批。 */
    private static final class PendingRequest {
        /** 等待审批所属会话。 */
        private final String sessionId;

        /** 发起审批的连接身份。 */
        private final Object owner;

        /** 等待终端选择的一次性结果。 */
        private final CompletableFuture<Decision> decision = new CompletableFuture<Decision>();

        /** 保存审批归属。 */
        private PendingRequest(String sessionId, Object owner) {
            this.sessionId = sessionId;
            this.owner = owner;
        }
    }
}
