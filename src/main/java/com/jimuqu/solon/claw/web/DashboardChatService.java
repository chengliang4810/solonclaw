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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Dashboard chat 运行服务。 */
@Component
public class DashboardChatService {
    /** 记录 Dashboard chat 运行服务的内部诊断日志。 */
    private static final Logger log = LoggerFactory.getLogger(DashboardChatService.class);

    /** SSE保活MILLIS的统一常量值。 */
    private static final long SSE_KEEPALIVE_MILLIS = 15_000L;

    /** 运行TTLMILLIS的统一常量值。 */
    private static final long RUN_TTL_MILLIS = 5L * 60L * 1000L;

    /** Web 用户主动取消运行时写入会话历史的可恢复提示。 */
    private static final String CANCELED_ASSISTANT_TEXT = "当前 Web 运行已取消。";

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
        final ChatRunState state = new ChatRunState(runId, request.sessionId, request);
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
        } catch (IOException e) {
            if (!DashboardClientDisconnects.isClientDisconnected(e)) {
                throw e;
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

        Future<?> future;
        synchronized (state) {
            if (state.completed) {
                return cancelResponse(runId, state.status);
            }
            state.canceled = true;
            state.status = "canceled";
            state.updatedAt = System.currentTimeMillis();
            enqueue(
                    state,
                    "run.failed",
                    new LinkedHashMap<String, Object>() {
                        {
                            put("error", "Run canceled");
                        }
                    });
            state.completed = true;
            future = state.future;
        }

        if (future != null) {
            future.cancel(true);
        }
        persistCanceledTurnSafely(state);
        return cancelResponse(runId, "canceled");
    }

    /**
     * 构造取消接口响应，完成态重复请求必须返回既有状态而不是伪造取消成功。
     *
     * @param runId 运行标识。
     * @param status 当前持久化运行状态。
     * @return 返回接口响应。
     */
    private Map<String, Object> cancelResponse(String runId, String status) {
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("ok", true);
        response.put("run_id", runId);
        response.put("status", status);
        return response;
    }

    /**
     * 持久化用户取消的 Web 运行轮次，避免刷新或恢复会话后丢失取消上下文。
     *
     * @param state 当前运行状态。
     */
    private void persistCanceledTurnSafely(ChatRunState state) {
        try {
            persistCanceledTurn(state);
        } catch (Exception e) {
            log.warn(
                    "Persist dashboard chat cancel turn failed: runId={}, sessionId={}, error={}",
                    state == null ? "" : state.runId,
                    state == null ? "" : state.sessionId,
                    SecretRedactor.redact(
                            StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()),
                            500));
        }
    }

    /**
     * 写入一次 Web 取消轮次，记录原始输入和取消提示，供会话恢复与历史检索使用。
     *
     * @param state 当前运行状态。
     */
    private void persistCanceledTurn(ChatRunState state) throws Exception {
        if (state == null || state.request == null) {
            return;
        }
        ChatRunRequest request = state.request;
        String userText = StrUtil.nullToEmpty(request.input).trim();
        if (StrUtil.isBlank(userText)) {
            return;
        }

        SessionRecord session = prepareSession(request);
        state.sessionId = session.getSessionId();
        persistVisibleTurn(session.getSessionId(), request, userText, CANCELED_ASSISTANT_TEXT);
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
            synchronized (state) {
                if (state.canceled || state.completed) {
                    return;
                }
                state.status = "running";
            }
            eventSink.onRunStarted(state.sessionId);
            if (state.canceled) {
                return;
            }

            GatewayMessage message = buildMessage(request, state.sessionId);
            GatewayReply reply;
            if (request.input.trim().startsWith("/")) {
                DashboardCommandEventSink commandEventSink =
                        new DashboardCommandEventSink(eventSink, request);
                reply = commandService.handle(message, request.input.trim(), commandEventSink);
                commandEventSink.persistIfDirectReply(reply);
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
     * 持久化 Web 端直连 slash 命令轮次，确保刷新、恢复和历史检索能看到命令结果。
     *
     * @param sessionId 命令最终归属的会话标识。
     * @param request 当前聊天运行请求。
     * @param assistantText 命令返回给用户的可见文本。
     */
    private void persistDirectCommandTurn(
            String sessionId, ChatRunRequest request, String assistantText) throws Exception {
        String targetSessionId = StrUtil.blankToDefault(sessionId, request.sessionId);
        String userText = StrUtil.nullToEmpty(request.input).trim();
        String assistant = StrUtil.nullToEmpty(assistantText).trim();
        if (StrUtil.isBlank(targetSessionId)
                || StrUtil.isBlank(userText)
                || StrUtil.isBlank(assistant)) {
            return;
        }

        persistVisibleTurn(targetSessionId, request, userText, assistant);
    }

    /**
     * 持久化 Web 可见的用户输入和助手文本，统一处理刷新恢复所需的尾部去重与标题维护。
     *
     * @param sessionId 当前会话标识。
     * @param request 当前聊天运行请求。
     * @param userText 用户可见输入。
     * @param assistantText 助手可见文本。
     */
    private void persistVisibleTurn(
            String sessionId, ChatRunRequest request, String userText, String assistantText)
            throws Exception {
        if (StrUtil.isBlank(sessionId)
                || StrUtil.isBlank(userText)
                || StrUtil.isBlank(assistantText)) {
            return;
        }

        SessionRecord session = sessionRepository.findById(sessionId);
        if (session == null) {
            return;
        }

        List<ChatMessage> messages = MessageSupport.loadMessages(session.getNdjson());
        if (isSameTrailingTurn(messages, userText, assistantText)) {
            return;
        }

        if (!isSameTrailingUser(messages, userText)) {
            messages.add(ChatMessage.ofUser(userText));
        }
        messages.add(ChatMessage.ofAssistant(assistantText));
        session.setNdjson(MessageSupport.toNdjson(messages));
        if (StrUtil.isBlank(session.getTitle())) {
            session.setTitle(extractTitle(request));
        }
        session.setUpdatedAt(System.currentTimeMillis());
        sessionRepository.save(session);
    }

    /**
     * 判断末尾是否已经是同一条命令及其回复，避免事件回调和兜底持久化重复写入。
     *
     * @param messages 当前会话消息。
     * @param userText 用户命令文本。
     * @param assistantText 助手回复文本。
     * @return 如果末尾轮次一致则返回 true。
     */
    private boolean isSameTrailingTurn(
            List<ChatMessage> messages, String userText, String assistantText) {
        if (messages == null || messages.size() < 2) {
            return false;
        }
        ChatMessage user = messages.get(messages.size() - 2);
        ChatMessage assistant = messages.get(messages.size() - 1);
        return user != null
                && assistant != null
                && "USER".equals(user.getRole().name())
                && "ASSISTANT".equals(assistant.getRole().name())
                && StrUtil.equals(StrUtil.nullToEmpty(user.getContent()).trim(), userText)
                && StrUtil.equals(
                        StrUtil.nullToEmpty(assistant.getContent()).trim(), assistantText);
    }

    /**
     * 判断末尾是否已写入同一条用户输入，用于取消发生在模型落库用户消息之后时只补助手取消提示。
     *
     * @param messages 当前会话消息。
     * @param userText 用户输入文本。
     * @return 如果最后一条消息就是同一条用户输入则返回 true。
     */
    private boolean isSameTrailingUser(List<ChatMessage> messages, String userText) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        ChatMessage user = messages.get(messages.size() - 1);
        return user != null
                && "USER".equals(user.getRole().name())
                && StrUtil.equals(StrUtil.nullToEmpty(user.getContent()).trim(), userText);
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
        message.setAllowedToolsOverride(request.allowedTools);
        message.setRequiredToolsOverride(request.requiredTools);
        message.setMaxToolCallsOverride(request.maxToolCalls);
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
        if (StrUtil.isNotBlank(state.agentRunId)) {
            payload.put("agent_run_id", state.agentRunId);
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

        /** 标记启动事件是否已投递，避免外层运行和模型编排层重复发送同一类事件。 */
        private boolean runStartedEmitted;

        /** 标记本轮是否已发送助手正文增量，供非流式控制回复补发最终文本。 */
        private boolean assistantDeltaEmitted;

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
            if (runStartedEmitted) {
                return;
            }
            runStartedEmitted = true;
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
            assistantDeltaEmitted = true;
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("delta", delta);
            enqueue(state, "message.delta", payload);
        }

        /**
         * 发送独立阶段说明事件，避免与最终 assistant 正文增量拼接。
         *
         * @param text 已完成安全过滤的阶段说明。
         */
        @Override
        public void onProgressUpdate(String text) {
            if (StrUtil.isBlank(text) || state.completed || state.canceled) {
                return;
            }
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("text", safeText(text, 240));
            enqueue(state, "progress.update", payload);
        }

        /** 撤销当前候选已发送的正文，确保备用模型答复不会与部分响应拼接。 */
        @Override
        public void onAssistantReset(String reason) {
            if (state.completed || state.canceled) {
                return;
            }
            assistantDeltaEmitted = false;
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("reason", StrUtil.nullToEmpty(reason));
            enqueue(state, "message.reset", payload);
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
            onToolCompleted(toolName, result, null, durationMs);
        }

        /**
         * 响应工具完成事件，并把执行层给出的失败原因原样编码为结构化字段。
         *
         * @param toolName 工具名称。
         * @param result 工具返回内容。
         * @param error 明确失败原因；成功时为 null。
         * @param durationMs 执行耗时，单位毫秒。
         */
        @Override
        public void onToolCompleted(String toolName, String result, String error, long durationMs) {
            if (state.completed || state.canceled) {
                return;
            }
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("tool", safeText(toolName, 120));
            payload.put("duration_ms", durationMs);
            if (StrUtil.isNotBlank(result)) {
                payload.put("preview", truncateInline(safeText(result, 1000), 80));
            }
            if (StrUtil.isNotBlank(error)) {
                payload.put("error", safeText(error, 1000));
                payload.put("status", "error");
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
            state.agentRunId = safeText(runId, 120);
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("agent_run_id", state.agentRunId);
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
            state.agentRunId = safeText(runId, 120);
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("agent_run_id", state.agentRunId);
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
            state.agentRunId = safeText(runId, 120);
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("agent_run_id", state.agentRunId);
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
            state.agentRunId = safeText(runId, 120);
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("agent_run_id", state.agentRunId);
            payload.put("recovery_type", safeText(recoveryType, 240));
            enqueue(state, "recovery.started", payload);
        }

        /**
         * 响应兜底事件。
         *
         * @param runId 运行标识。
         * @param fromProvider from提供方标识或键值。
         * @param toProvider to提供方标识或键值。
         * @param toModel 切换后实际激活的模型名称。
         * @param reason 原因参数。
         */
        @Override
        public void onFallback(
                String runId,
                String fromProvider,
                String toProvider,
                String toModel,
                String reason) {
            state.agentRunId = safeText(runId, 120);
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("agent_run_id", state.agentRunId);
            payload.put("from_provider", safeText(fromProvider, 120));
            payload.put("to_provider", safeText(toProvider, 120));
            payload.put("to_model", safeText(toModel, 240));
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
            synchronized (state) {
                if (state.completed) {
                    return;
                }
                state.sessionId = StrUtil.blankToDefault(sessionId, state.sessionId);
                if (!assistantDeltaEmitted && StrUtil.isNotBlank(finalReply) && !state.canceled) {
                    Map<String, Object> finalReplyPayload = new LinkedHashMap<String, Object>();
                    finalReplyPayload.put("delta", finalReply);
                    enqueue(state, "message.delta", finalReplyPayload);
                    assistantDeltaEmitted = true;
                }
                state.status = "completed";
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
                state.completed = true;
            }
        }

        /**
         * 响应运行Failed事件。
         *
         * @param sessionId 当前会话标识。
         * @param error 错误参数。
         */
        @Override
        public void onRunFailed(String sessionId, Throwable error) {
            synchronized (state) {
                if (state.completed) {
                    return;
                }
                state.sessionId = StrUtil.blankToDefault(sessionId, state.sessionId);
                state.status = "failed";
                state.updatedAt = System.currentTimeMillis();

                Map<String, Object> payload = new LinkedHashMap<String, Object>();
                payload.put(
                        "error",
                        SecretRedactor.redact(
                                error == null
                                        ? "Run failed"
                                        : StrUtil.blankToDefault(
                                                error.getMessage(),
                                                error.getClass().getSimpleName()),
                                1000));
                enqueue(state, "run.failed", payload);
                state.completed = true;
            }
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

    /** Web 端 slash 命令事件包装器，负责在完成事件前落库直连命令回复。 */
    private final class DashboardCommandEventSink implements ConversationEventSink {
        /** 委托给原有运行事件接收端，保持 SSE 事件协议不变。 */
        private final ConversationEventSink delegate;

        /** 保存当前请求，便于写入用户原始命令。 */
        private final ChatRunRequest request;

        /** 收集命令直连回复的增量文本。 */
        private final StringBuilder assistantBuffer = new StringBuilder();

        /** 标记当前命令是否转入模型运行，避免重复写入模型已持久化的会话。 */
        private boolean modelRunCompleted;

        /** 标记直连命令轮次是否已经持久化。 */
        private boolean persisted;

        /**
         * 创建 Web 端命令事件包装器。
         *
         * @param delegate 原有事件接收端。
         * @param request 当前聊天运行请求。
         */
        private DashboardCommandEventSink(ConversationEventSink delegate, ChatRunRequest request) {
            this.delegate = delegate == null ? ConversationEventSink.noop() : delegate;
            this.request = request;
        }

        /**
         * 当命令服务没有主动发出完成事件时，使用返回值做一次持久化兜底。
         *
         * @param reply 命令服务返回结果。
         */
        private void persistIfDirectReply(GatewayReply reply) throws Exception {
            if (reply == null || reply.isError() || modelRunCompleted || persisted) {
                return;
            }
            String sessionId = StrUtil.blankToDefault(reply.getSessionId(), request.sessionId);
            String content =
                    StrUtil.blankToDefault(
                            assistantBuffer.toString(), StrUtil.nullToEmpty(reply.getContent()));
            persist(sessionId, content);
        }

        /**
         * 写入一次直连命令轮次。
         *
         * @param sessionId 命令归属会话。
         * @param content 可见回复文本。
         */
        private void persist(String sessionId, String content) throws Exception {
            if (persisted) {
                return;
            }
            persistDirectCommandTurn(sessionId, request, content);
            persisted = true;
        }

        /** 运行开始。 */
        @Override
        public void onRunStarted(String sessionId) {
            delegate.onRunStarted(sessionId);
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
            delegate.onAttemptStarted(runId, attemptNo, provider, model);
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
            delegate.onAttemptCompleted(runId, attemptNo, status, reason);
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
            delegate.onCompressionDecision(
                    runId, compressed, reason, estimatedTokens, thresholdTokens);
        }

        /**
         * 响应恢复Started事件。
         *
         * @param runId 运行标识。
         * @param recoveryType 恢复类型参数。
         */
        @Override
        public void onRecoveryStarted(String runId, String recoveryType) {
            delegate.onRecoveryStarted(runId, recoveryType);
        }

        /**
         * 响应兜底事件。
         *
         * @param runId 运行标识。
         * @param fromProvider from提供方标识或键值。
         * @param toProvider to提供方标识或键值。
         * @param toModel 切换后实际激活的模型名称。
         * @param reason 原因参数。
         */
        @Override
        public void onFallback(
                String runId,
                String fromProvider,
                String toProvider,
                String toModel,
                String reason) {
            delegate.onFallback(runId, fromProvider, toProvider, toModel, reason);
        }

        /**
         * 响应投递事件事件。
         *
         * @param runId 运行标识。
         * @param status 状态参数。
         * @param detail 详情参数。
         */
        @Override
        public void onDeliveryEvent(String runId, String status, String detail) {
            delegate.onDeliveryEvent(runId, status, detail);
        }

        /** assistant 文本增量。 */
        @Override
        public void onAssistantDelta(String delta) {
            if (StrUtil.isNotBlank(delta)) {
                assistantBuffer.append(delta);
            }
            delegate.onAssistantDelta(delta);
        }

        /** 转发独立阶段说明，且不把它写入直连命令的最终正文缓冲。 */
        @Override
        public void onProgressUpdate(String text) {
            delegate.onProgressUpdate(text);
        }

        /** 清除命令包装器累计的候选正文并转发撤销事件。 */
        @Override
        public void onAssistantReset(String reason) {
            assistantBuffer.setLength(0);
            delegate.onAssistantReset(reason);
        }

        /** assistant reasoning 文本增量。 */
        @Override
        public void onReasoningDelta(String delta) {
            delegate.onReasoningDelta(delta);
        }

        /** 工具开始。 */
        @Override
        public void onToolStarted(String toolName, Map<String, Object> args) {
            delegate.onToolStarted(toolName, args);
        }

        /** 工具结束。 */
        @Override
        public void onToolCompleted(String toolName, String result, long durationMs) {
            delegate.onToolCompleted(toolName, result, durationMs);
        }

        /** 工具结束并向下游保留结构化失败原因。 */
        @Override
        public void onToolCompleted(String toolName, String result, String error, long durationMs) {
            delegate.onToolCompleted(toolName, result, error, durationMs);
        }

        /** 运行成功完成。 */
        @Override
        public void onRunCompleted(String sessionId, String finalReply, LlmResult result) {
            if (result != null) {
                modelRunCompleted = true;
            } else {
                try {
                    persist(
                            sessionId,
                            StrUtil.blankToDefault(finalReply, assistantBuffer.toString()));
                } catch (Exception e) {
                    delegate.onRunFailed(sessionId, e);
                    return;
                }
            }
            delegate.onRunCompleted(sessionId, finalReply, result);
        }

        /** 运行失败。 */
        @Override
        public void onRunFailed(String sessionId, Throwable error) {
            delegate.onRunFailed(sessionId, error);
        }
    }

    /** 表示聊天运行数据，在服务、仓储和接口之间传递。 */
    private static class ChatRunState {
        /** 记录聊天运行中的运行标识。 */
        private final String runId;

        /** 记录内部 Agent 持久化运行标识，用于把 Web 订阅事件关联到运行记录列表。 */
        private volatile String agentRunId;

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

        /** 保存原始请求，便于取消、恢复和诊断路径写入可追溯上下文。 */
        private final ChatRunRequest request;

        /**
         * 创建不绑定原始请求的状态实例，仅供事件单元测试使用。
         *
         * @param runId 运行标识。
         * @param sessionId 当前会话标识。
         */
        private ChatRunState(String runId, String sessionId) {
            this(runId, sessionId, null);
        }

        /**
         * 创建Chat运行状态实例，并注入运行所需依赖。
         *
         * @param runId 运行标识。
         * @param sessionId 当前会话标识。
         * @param request 原始聊天运行请求。
         */
        private ChatRunState(String runId, String sessionId, ChatRunRequest request) {
            this.runId = runId;
            this.sessionId = sessionId;
            this.request = request;
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

        /** 本轮 Web 运行允许调用的工具名白名单；为空表示不限制工具名。 */
        private List<String> allowedTools;

        /** 本轮 Web 运行必须真实完成的工具名列表；为空表示不做运行后工具校验。 */
        private List<String> requiredTools;

        /** 本轮 Web 运行允许尝试的最大工具调用次数；为空表示不限制次数。 */
        private Integer maxToolCalls;

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
            request.allowedTools =
                    parseStringList(firstPresent(body, "allowed_tools", "allowedTools"));
            request.requiredTools =
                    parseStringList(firstPresent(body, "required_tools", "requiredTools"));
            request.maxToolCalls =
                    parsePositiveInteger(firstPresent(body, "max_tool_calls", "maxToolCalls"));

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

        /**
         * 读取首个存在的请求字段，兼容蛇形命名和驼峰命名。
         *
         * @param body 请求体节点。
         * @param names 候选字段名。
         * @return 返回第一个存在的字段节点；不存在时返回 null。
         */
        private static ONode firstPresent(ONode body, String... names) {
            if (body == null || names == null) {
                return null;
            }
            for (String name : names) {
                ONode value = body.get(name);
                if (value != null && !value.isNull()) {
                    return value;
                }
            }
            return null;
        }

        /**
         * 解析工具名列表，支持 JSON 数组和逗号分隔字符串。
         *
         * @param node 请求字段节点。
         * @return 返回去空白后的工具名集合。
         */
        private static List<String> parseStringList(ONode node) {
            List<String> values = new ArrayList<String>();
            if (node == null || node.isNull()) {
                return values;
            }
            if (node.isArray()) {
                for (int i = 0; i < node.size(); i++) {
                    addStringValue(values, node.get(i).getString());
                }
            } else {
                String raw = node.getString();
                if (raw != null) {
                    String[] parts = raw.split(",");
                    for (String part : parts) {
                        addStringValue(values, part);
                    }
                }
            }
            return values;
        }

        /**
         * 解析正整数请求字段，非法或非正数表示不启用次数限制。
         *
         * @param node 请求字段节点。
         * @return 返回正整数；未配置时返回 null。
         */
        private static Integer parsePositiveInteger(ONode node) {
            if (node == null || node.isNull()) {
                return null;
            }
            try {
                int value = node.getInt();
                return value > 0 ? Integer.valueOf(value) : null;
            } catch (Exception e) {
                String raw = node.getString();
                if (raw == null) {
                    return null;
                }
                try {
                    int value = Integer.parseInt(raw.trim());
                    return value > 0 ? Integer.valueOf(value) : null;
                } catch (Exception parseError) {
                    log.debug(
                            "Dashboard运行策略正整数解析失败，忽略该次数限制 error={}",
                            parseError.getClass().getSimpleName());
                    return null;
                }
            }
        }

        /**
         * 添加单个工具名，避免空白项进入运行策略。
         *
         * @param values 目标集合。
         * @param raw 原始工具名。
         */
        private static void addStringValue(List<String> values, String raw) {
            if (raw == null) {
                return;
            }
            String clean = raw.trim();
            if (StrUtil.isNotBlank(clean)) {
                values.add(clean);
            }
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
