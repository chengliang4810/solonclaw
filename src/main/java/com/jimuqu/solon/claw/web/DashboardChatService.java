package com.jimuqu.solon.claw.web;

import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.CommandService;
import com.jimuqu.solon.claw.core.service.ConversationEventSink;
import com.jimuqu.solon.claw.core.service.ConversationOrchestrator;
import com.jimuqu.solon.claw.gateway.feedback.ToolPreviewSupport;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.BoundedExecutorFactory;
import com.jimuqu.solon.claw.support.IdSupport;
import com.jimuqu.solon.claw.support.MessageSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.CompressionConstants;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.annotation.Component;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.UploadedFile;

/** Dashboard chat 运行服务。 */
@Component
public class DashboardChatService {
    /** SSE保活MILLIS的统一常量值。 */
    private static final long SSE_KEEPALIVE_MILLIS = 15_000L;

    /** 运行TTLMILLIS的统一常量值。 */
    private static final long RUN_TTL_MILLIS = 5L * 60L * 1000L;

    /** 保存会话仓储依赖，用于访问持久化数据。 */
    private final SessionRepository sessionRepository;

    /** 记录控制台聊天中的对话编排器。 */
    private final ConversationOrchestrator conversationOrchestrator;

    /** 注入命令服务，用于调用对应业务能力。 */
    private final CommandService commandService;

    /** 注入附件缓存服务，用于调用对应业务能力。 */
    private final AttachmentCacheService attachmentCacheService;

    /** 保存执行器执行组件，负责调度异步或定时任务。 */
    private final ExecutorService executor;

    /** 保存运行映射，便于按键快速查询。 */
    private final ConcurrentMap<String, ChatRunState> runs =
            new ConcurrentHashMap<String, ChatRunState>();

    /**
     * 创建控制台Chat服务实例，并注入运行所需依赖。
     *
     * @param sessionRepository 会话仓储依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     * @param commandService 命令服务依赖。
     * @param attachmentCacheService 附件缓存服务依赖。
     */
    public DashboardChatService(
            SessionRepository sessionRepository,
            ConversationOrchestrator conversationOrchestrator,
            CommandService commandService,
            AttachmentCacheService attachmentCacheService) {
        this.sessionRepository = sessionRepository;
        this.conversationOrchestrator = conversationOrchestrator;
        this.commandService = commandService;
        this.attachmentCacheService = attachmentCacheService;
        this.executor = BoundedExecutorFactory.fixed("dashboard-chat-run", 4, 128);
    }

    /** 关闭当前组件持有的运行资源。 */
    public void shutdown() {
        executor.shutdownNow();
    }

    /**
     * 执行uploads相关逻辑。
     *
     * @param files 文件或目录路径参数。
     * @return 返回uploads结果。
     */
    public Map<String, Object> uploads(UploadedFile[] files) throws Exception {
        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("至少上传一个文件。");
        }

        List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();
        for (UploadedFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }

            try {
                MessageAttachment attachment =
                        attachmentCacheService.cacheBytes(
                                PlatformType.MEMORY,
                                null,
                                file.getName(),
                                file.getContentType(),
                                false,
                                null,
                                file.getContentAsBytes());
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                String reference = attachmentCacheService.mediaReference(attachment);
                item.put("name", attachment.getOriginalName());
                item.put("path", reference);
                item.put("local_path", reference);
                item.put("reference", reference);
                item.put("kind", attachment.getKind());
                item.put("mime_type", attachment.getMimeType());
                item.put("size", file.getContentSize());
                results.add(item);
            } finally {
                file.delete();
            }
        }

        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("files", results);
        return response;
    }

    /**
     * 启动运行。
     *
     * @param body 请求体或消息正文内容。
     * @return 返回运行结果。
     */
    public Map<String, Object> startRun(ONode body) throws Exception {
        cleanupExpiredRuns();

        final ChatRunRequest request = ChatRunRequest.from(body);
        if (StrUtil.isBlank(request.input)
                && (request.attachments == null || request.attachments.isEmpty())) {
            throw new IllegalArgumentException("input 与 attachments 不能同时为空。");
        }
        if (StrUtil.isBlank(request.sessionId)) {
            request.sessionId = IdSupport.newId();
        }
        request.resolvedAttachments = resolveAttachments(request.attachments);

        final String runId = IdSupport.newId();
        final ChatRunState state = new ChatRunState(runId, request.sessionId);
        runs.put(runId, state);

        Future<?> future =
                executor.submit(
                        new Runnable() {
                            /** 执行异步任务主体。 */
                            @Override
                            public void run() {
                                executeRun(state, request);
                            }
                        });
        state.future = future;

        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("run_id", runId);
        response.put("status", "running");
        response.put("session_id", request.sessionId);
        return response;
    }

    /**
     * 执行流Events相关逻辑。
     *
     * @param runId 运行标识。
     * @param context 当前请求或运行上下文。
     */
    public void streamEvents(String runId, Context context) throws Exception {
        ChatRunState state = runs.get(runId);
        if (state == null) {
            context.status(404);
            context.contentType("application/json;charset=UTF-8");
            context.output(ONode.serialize(Collections.singletonMap("error", "Run not found")));
            return;
        }

        context.contentType("text/event-stream;charset=UTF-8");
        context.headerSet("Cache-Control", "no-cache");
        context.headerSet("Connection", "keep-alive");
        context.headerSet("X-Accel-Buffering", "no");

        OutputStream outputStream = context.outputStream();
        long lastWriteAt = 0L;
        try {
            while (true) {
                ChatRunEvent event = state.events.poll(500, TimeUnit.MILLISECONDS);
                long now = System.currentTimeMillis();
                if (event != null) {
                    writeEvent(outputStream, event);
                    lastWriteAt = now;
                } else if (lastWriteAt == 0L || now - lastWriteAt >= SSE_KEEPALIVE_MILLIS) {
                    outputStream.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();
                    lastWriteAt = now;
                }

                if (state.completed && state.events.isEmpty()) {
                    break;
                }
            }
        } finally {
            if (state.completed && state.events.isEmpty()) {
                runs.remove(runId, state);
            }
            IoUtil.close(outputStream);
        }
    }

    /**
     * 执行cancel运行相关逻辑。
     *
     * @param runId 运行标识。
     * @return 返回cancel运行结果。
     */
    public Map<String, Object> cancelRun(String runId) {
        ChatRunState state = runs.get(runId);
        if (state == null) {
            throw new IllegalArgumentException("Run not found: " + runId);
        }

        state.canceled = true;
        state.completed = true;
        state.status = "canceled";
        enqueue(
                state,
                "run.failed",
                new LinkedHashMap<String, Object>() {
                    {
                        put("error", "Run canceled");
                    }
                });

        Future<?> future = state.future;
        if (future != null) {
            future.cancel(true);
        }

        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("ok", true);
        response.put("run_id", runId);
        response.put("status", "canceled");
        return response;
    }

    /**
     * 执行运行。
     *
     * @param state 状态参数。
     * @param request 当前请求对象。
     */
    private void executeRun(ChatRunState state, ChatRunRequest request) {
        DashboardRunEventSink eventSink = new DashboardRunEventSink(state);
        try {
            SessionRecord session = prepareSession(request);
            state.sessionId = session.getSessionId();
            state.status = "running";
            eventSink.onRunStarted(state.sessionId);

            GatewayMessage message = buildMessage(request, state.sessionId);
            GatewayReply reply;
            if (request.input.trim().startsWith("/")) {
                reply = commandService.handle(message, request.input.trim(), eventSink);
            } else {
                reply = conversationOrchestrator.handleIncoming(message, eventSink);
            }

            if (!state.completed) {
                if (reply != null && reply.isError()) {
                    eventSink.onRunFailed(
                            reply.getSessionId(), new IllegalStateException(reply.getContent()));
                } else {
                    eventSink.onRunCompleted(
                            reply == null ? state.sessionId : reply.getSessionId(),
                            reply == null ? "" : StrUtil.nullToEmpty(reply.getContent()),
                            null);
                }
            }
        } catch (Throwable e) {
            if (!state.completed) {
                eventSink.onRunFailed(state.sessionId, e);
            }
        }
    }

    /**
     * 执行prepare会话相关逻辑。
     *
     * @param request 当前请求对象。
     * @return 返回prepare会话结果。
     */
    private SessionRecord prepareSession(ChatRunRequest request) throws Exception {
        String sourceKey = sourceKey(request.sessionId);
        SessionRecord session = sessionRepository.findById(request.sessionId);
        if (session == null) {
            session = new SessionRecord();
            session.setSessionId(request.sessionId);
            session.setSourceKey(sourceKey);
            session.setBranchName("main");
            session.setNdjson(historyToNdjson(request.conversationHistory));
            session.setTitle(extractTitle(request));
            session.setCreatedAt(System.currentTimeMillis());
            session.setUpdatedAt(System.currentTimeMillis());
            if (StrUtil.isNotBlank(request.model)) {
                session.setModelOverride(request.model);
            }
            sessionRepository.save(session);
        } else if (StrUtil.isBlank(session.getSourceKey())) {
            session.setSourceKey(sourceKey);
            session.setUpdatedAt(System.currentTimeMillis());
            sessionRepository.save(session);
        }

        if (StrUtil.isNotBlank(request.model)) {
            sessionRepository.setModelOverride(session.getSessionId(), request.model);
            session.setModelOverride(request.model);
        }
        sessionRepository.bindSource(sourceKey, session.getSessionId());
        return sessionRepository.findById(session.getSessionId());
    }

    /**
     * 构建消息。
     *
     * @param request 当前请求对象。
     * @param sessionId 当前会话标识。
     * @return 返回创建好的消息。
     */
    private GatewayMessage buildMessage(ChatRunRequest request, String sessionId) {
        GatewayMessage message =
                new GatewayMessage(PlatformType.MEMORY, "dashboard", sessionId, request.input);
        message.setChatType("dm");
        message.setChatName("dashboard");
        message.setUserName("dashboard");
        message.setSourceKeyOverride(sourceKey(sessionId));
        if (request.attachments != null && !request.attachments.isEmpty()) {
            message.setAttachments(
                    request.resolvedAttachments == null
                            ? resolveAttachments(request.attachments)
                            : request.resolvedAttachments);
        }
        return message;
    }

    /**
     * 解析附件。
     *
     * @param inputs inputs 参数。
     * @return 返回解析后的附件。
     */
    private List<MessageAttachment> resolveAttachments(List<AttachmentInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return Collections.emptyList();
        }
        List<MessageAttachment> attachments = new ArrayList<MessageAttachment>();
        for (AttachmentInput item : inputs) {
            if (item == null) {
                continue;
            }
            MessageAttachment attachment =
                    attachmentCacheService.fromMediaCacheFile(
                            PlatformType.MEMORY,
                            attachmentCacheService.resolveMediaReference(item.localPath),
                            item.kind,
                            false,
                            null);
            attachments.add(attachment);
        }
        return attachments;
    }

    /**
     * 执行历史ToNDJSON相关逻辑。
     *
     * @param history 历史参数。
     * @return 返回历史To NDJSON结果。
     */
    private String historyToNdjson(List<HistoryItem> history) throws IOException {
        if (history == null || history.isEmpty()) {
            return "";
        }

        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        for (HistoryItem item : history) {
            if (item == null || StrUtil.isBlank(item.content)) {
                continue;
            }
            String role = StrUtil.blankToDefault(item.role, "user").trim().toLowerCase(Locale.ROOT);
            if ("assistant".equals(role)) {
                messages.add(ChatMessage.ofAssistant(item.content));
            } else if ("system".equals(role)) {
                messages.add(ChatMessage.ofSystem(item.content));
            } else {
                messages.add(ChatMessage.ofUser(item.content));
            }
        }
        return MessageSupport.toNdjson(messages);
    }

    /**
     * 提取标题。
     *
     * @param request 当前请求对象。
     * @return 返回标题结果。
     */
    private String extractTitle(ChatRunRequest request) {
        String base = "";
        if (request.conversationHistory != null) {
            for (HistoryItem item : request.conversationHistory) {
                if (item != null
                        && "user".equalsIgnoreCase(item.role)
                        && StrUtil.isNotBlank(item.content)) {
                    base = item.content;
                    break;
                }
            }
        }
        if (StrUtil.isBlank(base)) {
            base = request.input;
        }
        String normalized = StrUtil.nullToEmpty(base).replace('\r', ' ').replace('\n', ' ').trim();
        if (normalized.length() <= CompressionConstants.MAX_TITLE_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, CompressionConstants.MAX_TITLE_LENGTH) + "...";
    }

    /**
     * 执行enqueue相关逻辑。
     *
     * @param state 状态参数。
     * @param eventName 事件名称参数。
     * @param data 数据参数。
     */
    private void enqueue(ChatRunState state, String eventName, Map<String, Object> data) {
        if (state.canceled && !"run.failed".equals(eventName)) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("run_id", state.runId);
        if (StrUtil.isNotBlank(state.sessionId)) {
            payload.put("session_id", state.sessionId);
        }
        if (data != null) {
            payload.putAll(data);
        }
        state.events.offer(new ChatRunEvent(eventName, payload));
    }

    /**
     * 执行来源键相关逻辑。
     *
     * @param sessionId 当前会话标识。
     * @return 返回来源键结果。
     */
    private String sourceKey(String sessionId) {
        return "MEMORY:dashboard:" + StrUtil.blankToDefault(sessionId, "");
    }

    /** 执行cleanupExpired运行相关逻辑。 */
    private void cleanupExpiredRuns() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, ChatRunState> entry : runs.entrySet()) {
            ChatRunState state = entry.getValue();
            if (!state.completed) {
                continue;
            }
            if (now - state.updatedAt > RUN_TTL_MILLIS) {
                runs.remove(entry.getKey(), state);
            }
        }
    }

    /**
     * 写入事件。
     *
     * @param outputStream 输出流参数。
     * @param event 事件参数。
     */
    private void writeEvent(OutputStream outputStream, ChatRunEvent event) throws IOException {
        outputStream.write(("event: " + event.name + "\n").getBytes(StandardCharsets.UTF_8));
        outputStream.write(
                ("data: " + ONode.serialize(event.data) + "\n\n").getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
    }

    /** 承载控制台运行事件接收端相关状态和辅助逻辑。 */
    private final class DashboardRunEventSink implements ConversationEventSink {
        /** 记录控制台运行事件接收端中的状态。 */
        private final ChatRunState state;

        /**
         * 创建控制台运行事件接收端实例，并注入运行所需依赖。
         *
         * @param state 状态参数。
         */
        private DashboardRunEventSink(ChatRunState state) {
            this.state = state;
        }

        /**
         * 响应运行Started事件。
         *
         * @param sessionId 当前会话标识。
         */
        @Override
        public void onRunStarted(String sessionId) {
            state.sessionId = sessionId;
            enqueue(state, "run.started", Collections.<String, Object>emptyMap());
        }

        /**
         * 响应AssistantDelta事件。
         *
         * @param delta delta 参数。
         */
        @Override
        public void onAssistantDelta(String delta) {
            if (StrUtil.isBlank(delta) || state.completed || state.canceled) {
                return;
            }
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("delta", delta);
            enqueue(state, "message.delta", payload);
        }

        /**
         * 响应推理Delta事件。
         *
         * @param delta delta 参数。
         */
        @Override
        public void onReasoningDelta(String delta) {
            if (StrUtil.isBlank(delta) || state.completed || state.canceled) {
                return;
            }
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("delta", safeText(delta, 4000));
            enqueue(state, "reasoning.delta", payload);
        }

        /**
         * 响应工具Started事件。
         *
         * @param toolName 工具名称。
         * @param args 工具或命令参数。
         */
        @Override
        public void onToolStarted(String toolName, Map<String, Object> args) {
            if (state.completed || state.canceled) {
                return;
            }
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("tool", safeText(toolName, 120));
            payload.put(
                    "preview",
                    safeText(ToolPreviewSupport.buildPreview(toolName, args, 60, false), 240));
            enqueue(state, "tool.started", payload);
        }

        /**
         * 响应工具Completed事件。
         *
         * @param toolName 工具名称。
         * @param result 结果响应或执行结果。
         * @param durationMs durationMs 参数。
         */
        @Override
        public void onToolCompleted(String toolName, String result, long durationMs) {
            if (state.completed || state.canceled) {
                return;
            }
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("tool", safeText(toolName, 120));
            payload.put("duration_ms", durationMs);
            if (StrUtil.isNotBlank(result)) {
                payload.put("preview", truncateInline(safeText(result, 1000), 80));
            }
            enqueue(state, "tool.completed", payload);
        }

        /**
         * 响应AttemptStarted事件。
         *
         * @param runId 运行标识。
         * @param attemptNo attemptNo 参数。
         * @param provider 模型或能力提供方。
         * @param model 模型名称。
         */
        @Override
        public void onAttemptStarted(String runId, int attemptNo, String provider, String model) {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("agent_run_id", safeText(runId, 120));
            payload.put("attempt_no", attemptNo);
            payload.put("provider", safeText(provider, 120));
            payload.put("model", safeText(model, 120));
            enqueue(state, "attempt.started", payload);
        }

        /**
         * 响应AttemptCompleted事件。
         *
         * @param runId 运行标识。
         * @param attemptNo attemptNo 参数。
         * @param status 状态参数。
         * @param reason 原因参数。
         */
        @Override
        public void onAttemptCompleted(String runId, int attemptNo, String status, String reason) {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("agent_run_id", safeText(runId, 120));
            payload.put("attempt_no", attemptNo);
            payload.put("status", safeText(status, 120));
            payload.put("reason", safeText(reason, 1000));
            enqueue(state, "attempt.completed", payload);
        }

        /**
         * 响应压缩决策事件。
         *
         * @param runId 运行标识。
         * @param compressed compressed 参数。
         * @param reason 原因参数。
         * @param estimatedTokens estimatedtoken参数。
         * @param thresholdTokens thresholdtoken参数。
         */
        @Override
        public void onCompressionDecision(
                String runId,
                boolean compressed,
                String reason,
                int estimatedTokens,
                int thresholdTokens) {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("agent_run_id", safeText(runId, 120));
            payload.put("compressed", compressed);
            payload.put("reason", safeText(reason, 1000));
            payload.put("estimated_tokens", estimatedTokens);
            payload.put("threshold_tokens", thresholdTokens);
            enqueue(state, "compression.decision", payload);
        }

        /**
         * 响应恢复Started事件。
         *
         * @param runId 运行标识。
         * @param recoveryType 恢复类型参数。
         */
        @Override
        public void onRecoveryStarted(String runId, String recoveryType) {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("agent_run_id", safeText(runId, 120));
            payload.put("recovery_type", safeText(recoveryType, 240));
            enqueue(state, "recovery.started", payload);
        }

        /**
         * 响应兜底事件。
         *
         * @param runId 运行标识。
         * @param fromProvider from提供方标识或键值。
         * @param toProvider to提供方标识或键值。
         * @param reason 原因参数。
         */
        @Override
        public void onFallback(
                String runId, String fromProvider, String toProvider, String reason) {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("agent_run_id", safeText(runId, 120));
            payload.put("from_provider", safeText(fromProvider, 120));
            payload.put("to_provider", safeText(toProvider, 120));
            payload.put("reason", safeText(reason, 1000));
            enqueue(state, "fallback", payload);
        }

        /**
         * 响应运行Completed事件。
         *
         * @param sessionId 当前会话标识。
         * @param finalReply 最终回复参数。
         * @param result 结果响应或执行结果。
         */
        @Override
        public void onRunCompleted(String sessionId, String finalReply, LlmResult result) {
            state.sessionId = StrUtil.blankToDefault(sessionId, state.sessionId);
            state.status = "completed";
            state.completed = true;
            state.updatedAt = System.currentTimeMillis();

            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            if (result != null) {
                Map<String, Object> usage = new LinkedHashMap<String, Object>();
                usage.put("input_tokens", result.getInputTokens());
                usage.put("output_tokens", result.getOutputTokens());
                usage.put("reasoning_tokens", result.getReasoningTokens());
                usage.put("total_tokens", result.getTotalTokens());
                payload.put("usage", usage);
                if (StrUtil.isNotBlank(result.getReasoningText())) {
                    payload.put("reasoning", safeText(result.getReasoningText(), 4000));
                }
            }
            enqueue(state, "run.completed", payload);
        }

        /**
         * 响应运行Failed事件。
         *
         * @param sessionId 当前会话标识。
         * @param error 错误参数。
         */
        @Override
        public void onRunFailed(String sessionId, Throwable error) {
            state.sessionId = StrUtil.blankToDefault(sessionId, state.sessionId);
            state.status = "failed";
            state.completed = true;
            state.updatedAt = System.currentTimeMillis();

            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put(
                    "error",
                    SecretRedactor.redact(
                            error == null
                                    ? "Run failed"
                                    : StrUtil.blankToDefault(
                                            error.getMessage(), error.getClass().getSimpleName()),
                            1000));
            enqueue(state, "run.failed", payload);
        }

        /**
         * 执行truncate内联相关逻辑。
         *
         * @param text 待处理文本。
         * @param limit 最大返回数量。
         * @return 返回truncate Inline结果。
         */
        private String truncateInline(String text, int limit) {
            String normalized =
                    StrUtil.nullToEmpty(text).replace('\r', ' ').replace('\n', ' ').trim();
            if (normalized.length() <= limit) {
                return normalized;
            }
            return normalized.substring(0, Math.max(0, limit - 3)) + "...";
        }

        /**
         * 生成安全展示用的文本。
         *
         * @param text 待处理文本。
         * @param maxLength 最大保留字符数。
         * @return 返回safe Text结果。
         */
        private String safeText(String text, int maxLength) {
            String normalized =
                    StrUtil.nullToEmpty(text).replace('\r', ' ').replace('\n', ' ').trim();
            return SecretRedactor.redact(normalized, maxLength);
        }
    }

    /** 表示聊天运行数据，在服务、仓储和接口之间传递。 */
    private static class ChatRunState {
        /** 记录聊天运行中的运行标识。 */
        private final String runId;

        /** 记录聊天运行中的events。 */
        private final BlockingQueue<ChatRunEvent> events = new LinkedBlockingQueue<ChatRunEvent>();

        /** 记录聊天运行中的创建时间。 */
        private final long createdAt = System.currentTimeMillis();

        /** 记录聊天运行中的更新时间。 */
        private volatile long updatedAt = createdAt;

        /** 记录聊天运行中的会话标识。 */
        private volatile String sessionId;

        /** 记录聊天运行中的状态。 */
        private volatile String status = "queued";

        /** 是否启用completed。 */
        private volatile boolean completed;

        /** 是否启用canceled。 */
        private volatile boolean canceled;

        /** 记录聊天运行中的future。 */
        private volatile Future<?> future;

        /**
         * 创建Chat运行状态实例，并注入运行所需依赖。
         *
         * @param runId 运行标识。
         * @param sessionId 当前会话标识。
         */
        private ChatRunState(String runId, String sessionId) {
            this.runId = runId;
            this.sessionId = sessionId;
        }
    }

    /** 承载聊天运行事件相关状态和辅助逻辑。 */
    private static class ChatRunEvent {
        /** 记录聊天运行事件中的名称。 */
        private final String name;

        /** 保存数据映射，便于按键快速查询。 */
        private final Map<String, Object> data;

        /**
         * 创建Chat运行事件实例，并注入运行所需依赖。
         *
         * @param name 名称参数。
         * @param data 数据参数。
         */
        private ChatRunEvent(String name, Map<String, Object> data) {
            this.name = name;
            this.data = data;
        }
    }

    /** 承载聊天运行请求相关状态和辅助逻辑。 */
    private static class ChatRunRequest {
        /** 记录聊天运行请求中的输入。 */
        private String input;

        /** 记录聊天运行请求中的会话标识。 */
        private String sessionId;

        /** 记录聊天运行请求中的模型。 */
        private String model;

        /** 保存对话历史集合，维持调用顺序或去重语义。 */
        private List<HistoryItem> conversationHistory;

        /** 保存附件集合，维持调用顺序或去重语义。 */
        private List<AttachmentInput> attachments;

        /** 保存resolved附件集合，维持调用顺序或去重语义。 */
        private List<MessageAttachment> resolvedAttachments;

        /**
         * 执行from相关逻辑。
         *
         * @param body 请求体或消息正文内容。
         * @return 返回from结果。
         */
        private static ChatRunRequest from(ONode body) {
            ChatRunRequest request = new ChatRunRequest();
            request.input = body.get("input").getString();
            request.sessionId = body.get("session_id").getString();
            request.model = body.get("model").getString();

            List<HistoryItem> history = new ArrayList<HistoryItem>();
            ONode historyNode = body.get("conversation_history");
            if (historyNode != null && historyNode.isArray()) {
                for (int i = 0; i < historyNode.size(); i++) {
                    ONode item = historyNode.get(i);
                    HistoryItem historyItem = new HistoryItem();
                    historyItem.role = item.get("role").getString();
                    historyItem.content = item.get("content").getString();
                    history.add(historyItem);
                }
            }
            request.conversationHistory = history;

            List<AttachmentInput> attachments = new ArrayList<AttachmentInput>();
            ONode attachmentsNode = body.get("attachments");
            if (attachmentsNode != null && attachmentsNode.isArray()) {
                for (int i = 0; i < attachmentsNode.size(); i++) {
                    ONode item = attachmentsNode.get(i);
                    AttachmentInput attachment = new AttachmentInput();
                    attachment.name = item.get("name").getString();
                    attachment.localPath = item.get("local_path").getString();
                    attachment.kind = item.get("kind").getString();
                    attachment.mimeType = item.get("mime_type").getString();
                    attachments.add(attachment);
                }
            }
            request.attachments = attachments;
            return request;
        }
    }

    /** 承载历史Item相关状态和辅助逻辑。 */
    private static class HistoryItem {
        /** 记录历史Item中的role。 */
        private String role;

        /** 记录历史Item中的content。 */
        private String content;
    }

    /** 承载附件输入相关状态和辅助逻辑。 */
    private static class AttachmentInput {
        /** 记录附件输入中的名称。 */
        private String name;

        /** 记录附件输入中的本地路径。 */
        private String localPath;

        /** 记录附件输入中的kind。 */
        private String kind;

        /** 记录附件输入中的MIME 类型。 */
        private String mimeType;
    }
}
