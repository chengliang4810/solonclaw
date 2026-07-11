package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 协调 clarify 工具与交互式客户端之间的阻塞请求、响应和生命周期清理。 */
public class ClarifyRequestCoordinator {
    /** 对标实现的 clarify 默认等待时长，超时后以空回答释放当前工具调用。 */
    private static final long DEFAULT_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(5L);

    /** 默认运行实例，由工具注册表与终端 UI 监听器共享。 */
    private static final ClarifyRequestCoordinator SHARED =
            new ClarifyRequestCoordinator(DEFAULT_TIMEOUT_MILLIS);

    /** 会话到当前交互客户端的绑定，恢复或重新提交时允许由新连接覆盖。 */
    private final Map<String, SessionBinding> bindings =
            new ConcurrentHashMap<String, SessionBinding>();

    /** request_id 到等待请求的映射，用于严格校验会话和连接归属。 */
    private final Map<String, PendingRequest> pendingRequests =
            new ConcurrentHashMap<String, PendingRequest>();

    /** 单次 clarify 请求最大等待时长。 */
    private final long timeoutMillis;

    /** 创建使用默认五分钟超时的协调器。 */
    public ClarifyRequestCoordinator() {
        this(DEFAULT_TIMEOUT_MILLIS);
    }

    /** 创建可指定等待时长的协调器，主要用于专项测试和受控运行环境。 */
    public ClarifyRequestCoordinator(long timeoutMillis) {
        this.timeoutMillis = Math.max(1L, timeoutMillis);
    }

    /** 读取工具和终端 UI 共用的默认协调器。 */
    public static ClarifyRequestCoordinator shared() {
        return SHARED;
    }

    /**
     * 将会话绑定到当前交互客户端。
     *
     * @param sessionId 会话标识。
     * @param owner 客户端连接身份对象，响应时必须为同一实例。
     * @param emitter 请求事件发送器。
     */
    public void bindSession(String sessionId, Object owner, RequestEmitter emitter) {
        String normalizedSessionId = normalizeRequired(sessionId, "session_id is required");
        if (owner == null) {
            throw new IllegalArgumentException("clarify owner is required");
        }
        if (emitter == null) {
            throw new IllegalArgumentException("clarify emitter is required");
        }
        SessionBinding replacement = new SessionBinding(owner, emitter);
        SessionBinding previous = bindings.put(normalizedSessionId, replacement);
        if (previous != null && previous.owner != owner) {
            clearPending(normalizedSessionId, previous.owner);
        }
    }

    /**
     * 发出 clarify 请求并等待对应 request_id 的回答。
     *
     * @param sessionId 当前 Agent run 所属会话。
     * @param question 已校验的问题文本。
     * @param choices 最多四项候选；空集合表示开放问题。
     * @return 用户回答；超时、取消或会话关闭时返回空字符串。
     */
    public String request(String sessionId, String question, List<String> choices) {
        String normalizedSessionId = normalizeRequired(sessionId, "session_id is required");
        SessionBinding binding = bindings.get(normalizedSessionId);
        if (binding == null) {
            throw new IllegalStateException(
                    "Clarify tool is not available in this execution context.");
        }

        String requestId = newRequestId();
        PendingRequest pending = new PendingRequest(normalizedSessionId, binding.owner);
        pendingRequests.put(requestId, pending);
        if (bindings.get(normalizedSessionId) != binding) {
            pendingRequests.remove(requestId, pending);
            throw new IllegalStateException("clarify session transport changed");
        }

        try {
            if (pendingRequests.get(requestId) != pending) {
                return "";
            }
            binding.emitter.emit(
                    new ClarifyRequest(
                            requestId,
                            normalizedSessionId,
                            StrUtil.nullToEmpty(question).trim(),
                            immutableChoices(choices)));
            return StrUtil.nullToEmpty(pending.answer.get(timeoutMillis, TimeUnit.MILLISECONDS))
                    .trim();
        } catch (TimeoutException | CancellationException e) {
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to get user input: " + safeMessage(e), e);
        } finally {
            pendingRequests.remove(requestId, pending);
        }
    }

    /**
     * 完成指定 clarify 请求；会话或连接不匹配时拒绝响应，防止跨会话串答。
     *
     * @param sessionId 响应所属会话。
     * @param requestId 前端收到的请求标识。
     * @param answer 用户回答。
     * @param owner 发起响应的客户端连接身份对象。
     */
    public void respond(String sessionId, String requestId, String answer, Object owner) {
        String normalizedSessionId = normalizeRequired(sessionId, "session_id is required");
        String normalizedRequestId = normalizeRequired(requestId, "request_id is required");
        PendingRequest pending = pendingRequests.get(normalizedRequestId);
        if (pending == null) {
            throw new IllegalArgumentException("no pending clarify request");
        }
        if (!normalizedSessionId.equals(pending.sessionId)) {
            throw new IllegalArgumentException("clarify request belongs to another session");
        }
        if (owner == null || pending.owner != owner) {
            throw new IllegalArgumentException("clarify request belongs to another connection");
        }
        if (!pendingRequests.remove(normalizedRequestId, pending)) {
            throw new IllegalArgumentException("no pending clarify request");
        }
        pending.answer.complete(StrUtil.nullToEmpty(answer));
    }

    /** 用空回答释放指定会话全部等待请求，但保留当前交互客户端绑定供后续轮次继续使用。 */
    public void clearSession(String sessionId) {
        String normalizedSessionId = StrUtil.nullToEmpty(sessionId).trim();
        if (StrUtil.isBlank(normalizedSessionId)) {
            return;
        }
        clearPending(normalizedSessionId, null);
    }

    /** 清理指定会话绑定并释放等待请求，用于会话关闭而非临时中断。 */
    public void closeSession(String sessionId) {
        String normalizedSessionId = StrUtil.nullToEmpty(sessionId).trim();
        if (StrUtil.isBlank(normalizedSessionId)) {
            return;
        }
        bindings.remove(normalizedSessionId);
        clearPending(normalizedSessionId, null);
    }

    /** 清理某个已断开客户端拥有的全部会话和等待请求。 */
    public void clearOwner(Object owner) {
        if (owner == null) {
            return;
        }
        for (Map.Entry<String, SessionBinding> entry : bindings.entrySet()) {
            SessionBinding binding = entry.getValue();
            if (binding != null && binding.owner == owner) {
                bindings.remove(entry.getKey(), binding);
            }
        }
        clearPending(null, owner);
    }

    /** 返回当前等待请求数，供生命周期回归测试确认无残留。 */
    public int pendingCount() {
        return pendingRequests.size();
    }

    /** 用空回答释放满足会话或连接过滤条件的等待请求。 */
    private void clearPending(String sessionId, Object owner) {
        for (Map.Entry<String, PendingRequest> entry : pendingRequests.entrySet()) {
            PendingRequest pending = entry.getValue();
            boolean sessionMatches = sessionId == null || sessionId.equals(pending.sessionId);
            boolean ownerMatches = owner == null || owner == pending.owner;
            if (sessionMatches && ownerMatches && pendingRequests.remove(entry.getKey(), pending)) {
                pending.answer.complete("");
            }
        }
    }

    /** 生成短 request_id，保持终端协议载荷紧凑且避免复用。 */
    private String newRequestId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /** 复制候选项，避免工具调用等待期间被外部集合修改。 */
    private List<String> immutableChoices(List<String> choices) {
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        return Collections.unmodifiableList(new ArrayList<String>(choices));
    }

    /** 校验协议必填字符串。 */
    private String normalizeRequired(String value, String message) {
        String normalized = StrUtil.nullToEmpty(value).trim();
        if (StrUtil.isBlank(normalized)) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }

    /** 构造不泄露堆栈的异常摘要。 */
    private String safeMessage(Throwable error) {
        if (error == null) {
            return "request failed";
        }
        return StrUtil.blankToDefault(error.getMessage(), error.getClass().getSimpleName());
    }

    /** 终端或其他交互客户端实现的 clarify 请求事件发送器。 */
    public interface RequestEmitter {
        /** 将带 request_id 的请求发给当前会话客户端。 */
        void emit(ClarifyRequest request) throws Exception;
    }

    /** 发送给交互客户端的只读 clarify 请求。 */
    public static final class ClarifyRequest {
        /** 本次请求标识。 */
        private final String requestId;

        /** 请求所属会话。 */
        private final String sessionId;

        /** 展示给用户的问题。 */
        private final String question;

        /** 可选候选项；null 表示开放问题。 */
        private final List<String> choices;

        /** 创建只读 clarify 请求。 */
        private ClarifyRequest(
                String requestId, String sessionId, String question, List<String> choices) {
            this.requestId = requestId;
            this.sessionId = sessionId;
            this.question = question;
            this.choices = choices;
        }

        /** 读取请求标识。 */
        public String getRequestId() {
            return requestId;
        }

        /** 读取请求所属会话。 */
        public String getSessionId() {
            return sessionId;
        }

        /** 读取问题文本。 */
        public String getQuestion() {
            return question;
        }

        /** 读取候选项；null 表示开放问题。 */
        public List<String> getChoices() {
            return choices;
        }
    }

    /** 当前会话绑定的客户端身份和发送器。 */
    private static final class SessionBinding {
        /** 客户端连接身份对象。 */
        private final Object owner;

        /** 请求事件发送器。 */
        private final RequestEmitter emitter;

        /** 创建会话绑定。 */
        private SessionBinding(Object owner, RequestEmitter emitter) {
            this.owner = owner;
            this.emitter = emitter;
        }
    }

    /** 等待用户回答的单次请求。 */
    private static final class PendingRequest {
        /** 请求所属会话。 */
        private final String sessionId;

        /** 请求所属客户端连接。 */
        private final Object owner;

        /** 回答完成信号。 */
        private final CompletableFuture<String> answer = new CompletableFuture<String>();

        /** 创建等待请求。 */
        private PendingRequest(String sessionId, Object owner) {
            this.sessionId = sessionId;
            this.owner = owner;
        }
    }
}
