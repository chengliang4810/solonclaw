package com.jimuqu.solon.claw.llm;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.config.RuntimeConfigResolver;
import com.jimuqu.solon.claw.context.MemoryContextBoundary;
import com.jimuqu.solon.claw.core.model.AgentRunContext;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.model.ToolCallRecord;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.ConversationEventSink;
import com.jimuqu.solon.claw.core.service.LlmGateway;
import com.jimuqu.solon.claw.gateway.feedback.ConversationFeedbackSink;
import com.jimuqu.solon.claw.llm.dialect.RawResponseLoggingChatDialect;
import com.jimuqu.solon.claw.media.MediaInputBoundaryService;
import com.jimuqu.solon.claw.storage.session.SqliteAgentSession;
import com.jimuqu.solon.claw.support.BoundedExecutorFactory;
import com.jimuqu.solon.claw.support.ErrorTextSupport;
import com.jimuqu.solon.claw.support.IdSupport;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.support.MessageAttachmentSupport;
import com.jimuqu.solon.claw.support.MessageSupport;
import com.jimuqu.solon.claw.support.ModelMetadataService;
import com.jimuqu.solon.claw.support.ProgressUpdateSanitizer;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.SecretValueGuard;
import com.jimuqu.solon.claw.support.ToolMessageStatusSupport;
import com.jimuqu.solon.claw.support.constants.LlmConstants;
import com.jimuqu.solon.claw.support.constants.RuntimePathConstants;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import com.jimuqu.solon.claw.tool.runtime.ReActToolObservationSupport;
import com.jimuqu.solon.claw.tool.runtime.SanitizedFunctionTool;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.tool.runtime.SmartApprovalDecision;
import com.jimuqu.solon.claw.tool.runtime.SmartApprovalJudge;
import com.jimuqu.solon.claw.tool.runtime.ToolCallLoopGuardrailService;
import com.jimuqu.solon.claw.tool.runtime.ToolResultStorageInterceptor;
import com.jimuqu.solon.claw.tool.runtime.ToolResultStorageService;
import com.jimuqu.solon.claw.tool.runtime.ToolResultTransformService;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.noear.snack4.ONode;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgentConfig;
import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActOptions;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.ToolRetryInterceptor;
import org.noear.solon.ai.agent.react.intercept.ToolSanitizerInterceptor;
import org.noear.solon.ai.agent.react.task.ToolExchanger;
import org.noear.solon.ai.agent.trace.Metrics;
import org.noear.solon.ai.chat.CacheControl;
import org.noear.solon.ai.chat.ChatChoice;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.ChatRequest;
import org.noear.solon.ai.chat.ChatRequestDesc;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.ChatResponseDefault;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.Contents;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.dialect.ChatDialectManager;
import org.noear.solon.ai.chat.interceptor.CallChain;
import org.noear.solon.ai.chat.interceptor.StreamChain;
import org.noear.solon.ai.chat.interceptor.ToolChain;
import org.noear.solon.ai.chat.interceptor.ToolRequest;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.talent.Talent;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.llm.dialect.anthropic.AnthropicChatDialect;
import org.noear.solon.ai.llm.dialect.gemini.GeminiChatDialect;
import org.noear.solon.ai.llm.dialect.ollama.OllamaChatDialect;
import org.noear.solon.ai.llm.dialect.openai.OpenaiChatDialect;
import org.noear.solon.ai.llm.dialect.openai.OpenaiResponsesDialect;
import org.noear.solon.ai.talents.pdf.PdfTalent;
import org.noear.solon.core.util.RankEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/** SolonAiLlmGateway 实现。 */
public class SolonAiLlmGateway implements LlmGateway {
    /** 单次模型流最长等待时间，防止提供方不发送完成事件时会话永久卡住。 */
    private static final Duration MODEL_STREAM_TIMEOUT = Duration.ofMinutes(5);

    /** 单轮正文因长度限制被截断后允许的最大续写次数。 */
    private static final int MAX_LENGTH_CONTINUATIONS = 4;

    /** 单轮最多向用户发送的语义阶段说明数量。 */
    private static final int MAX_PROGRESS_UPDATES = 3;

    /** 相邻语义阶段说明的最小间隔，避免配置过低时刷屏。 */
    private static final long MIN_PROGRESS_UPDATE_INTERVAL_MS = 5000L;

    /** 延迟发送长工具进度的守护线程，避免短工具产生无意义提示。 */
    private static final ScheduledExecutorService PROGRESS_UPDATE_EXECUTOR =
            BoundedExecutorFactory.scheduled("solonclaw-progress-timer", 1);

    /** 隔离渠道网络投递的受限执行器，避免一个渠道阻塞其他会话的进度定时器。 */
    private static final ExecutorService PROGRESS_DELIVERY_EXECUTOR =
            BoundedExecutorFactory.fixed("solonclaw-progress-delivery", 4, 64);

    /** 长度截断后的内部续写提示，只要求返回尚未输出的正文。 */
    private static final String LENGTH_CONTINUATION_PROMPT = "请从刚才被截断的位置继续，不要重复已经输出的内容，只输出续写部分。";

    /** LLM 网关日志器。 */
    private static final Logger log = LoggerFactory.getLogger(SolonAiLlmGateway.class);

    /** CUSTOMDIALECTSREGISTERED的统一常量值。 */
    private static final AtomicBoolean CUSTOM_DIALECTS_REGISTERED = new AtomicBoolean(false);

    /** 识别用户原文中明确要求 no_agent=true 的布尔赋值。 */
    private static final Pattern USER_NO_AGENT_TRUE_PATTERN =
            Pattern.compile("(?i)(^|[^a-z0-9_\\-])no_agent\\s*(=|:|：)\\s*true\\b");

    /** 识别用户原文中明确要求 wrap_response=false 的布尔赋值。 */
    private static final Pattern USER_WRAP_RESPONSE_FALSE_PATTERN =
            Pattern.compile("(?i)(^|[^a-z0-9_\\-])wrap_response\\s*(=|:|：)\\s*false\\b");

    /** 未被协议层识别的工具 XML 不属于用户可见答复，避免其参数泄漏到对话界面。 */
    private static final Pattern UNRECOGNIZED_TOOL_XML_BLOCK_PATTERN =
            Pattern.compile(
                    "<\\s*(tool_call|function_call)\\b[^>]*>[\\s\\S]*?(?:</\\s*\\1\\s*>|\\z)",
                    Pattern.CASE_INSENSITIVE);

    /** 清理残留的工具 XML 闭合标签，避免异常流响应留下协议标记。 */
    private static final Pattern UNRECOGNIZED_TOOL_XML_TAG_PATTERN =
            Pattern.compile("</?\\s*(tool_call|function_call)\\b[^>]*>", Pattern.CASE_INSENSITIVE);

    /** 注入应用配置，用于SolonAi大模型消息网关。 */
    private final AppConfig appConfig;

    /** 当前线程 Failover 尝试共享的阶段说明发送器，不改变普通运行上下文语义。 */
    private final ThreadLocal<ProgressUpdateEmitter> failoverProgressEmitter =
            new ThreadLocal<ProgressUpdateEmitter>();

    /** 标记当前纯文本辅助调用不得附加 PDF 等内建工具。 */
    private final ThreadLocal<Boolean> textOnlyCall = new ThreadLocal<Boolean>();

    /** 保存会话仓储依赖，用于访问持久化数据。 */
    private final SessionRepository sessionRepository;

    /** 注入dangerous命令审批服务，用于调用对应业务能力。 */
    private final DangerousCommandApprovalService dangerousCommandApprovalService;

    /** 注入大模型提供方服务，用于调用对应业务能力。 */
    private final LlmProviderService llmProviderService;

    /** 注入工具结果转换服务，用于调用对应业务能力。 */
    private final ToolResultTransformService toolResultTransformService;

    /** 注入工具Call循环防护服务，用于调用对应业务能力。 */
    private final ToolCallLoopGuardrailService toolCallLoopGuardrailService;

    /** 注入安全策略服务，用于调用对应业务能力。 */
    private final SecurityPolicyService securityPolicyService;

    /** 注入媒体输入Boundary服务，用于调用对应业务能力。 */
    private final MediaInputBoundaryService mediaInputBoundaryService;

    /** 注入模型元数据服务，用于调用对应业务能力。 */
    private final ModelMetadataService modelMetadataService;

    /** 记录SolonAi大模型消息网关中的pdf技能。 */
    private volatile PdfTalent pdfSkill;

    /**
     * 创建Solon Ai大模型消息网关实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     */
    public SolonAiLlmGateway(AppConfig appConfig) {
        this(appConfig, null, null, null);
    }

    /**
     * 创建Solon Ai大模型消息网关实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param sessionRepository 会话仓储依赖。
     */
    public SolonAiLlmGateway(AppConfig appConfig, SessionRepository sessionRepository) {
        this(appConfig, sessionRepository, null, null);
    }

    /**
     * 创建Solon Ai大模型消息网关实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param sessionRepository 会话仓储依赖。
     * @param dangerousCommandApprovalService dangerous命令审批服务依赖。
     */
    public SolonAiLlmGateway(
            AppConfig appConfig,
            SessionRepository sessionRepository,
            DangerousCommandApprovalService dangerousCommandApprovalService) {
        this(appConfig, sessionRepository, dangerousCommandApprovalService, null);
    }

    /**
     * 创建Solon Ai大模型消息网关实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param sessionRepository 会话仓储依赖。
     * @param dangerousCommandApprovalService dangerous命令审批服务依赖。
     * @param llmProviderService LLM提供方Service标识或键值。
     */
    public SolonAiLlmGateway(
            AppConfig appConfig,
            SessionRepository sessionRepository,
            DangerousCommandApprovalService dangerousCommandApprovalService,
            LlmProviderService llmProviderService) {
        this(
                appConfig,
                sessionRepository,
                dangerousCommandApprovalService,
                llmProviderService,
                null);
    }

    /**
     * 创建Solon Ai大模型消息网关实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param sessionRepository 会话仓储依赖。
     * @param dangerousCommandApprovalService dangerous命令审批服务依赖。
     * @param llmProviderService LLM提供方Service标识或键值。
     * @param toolResultTransformService 工具结果转换Service响应或执行结果。
     */
    public SolonAiLlmGateway(
            AppConfig appConfig,
            SessionRepository sessionRepository,
            DangerousCommandApprovalService dangerousCommandApprovalService,
            LlmProviderService llmProviderService,
            ToolResultTransformService toolResultTransformService) {
        this(
                appConfig,
                sessionRepository,
                dangerousCommandApprovalService,
                llmProviderService,
                toolResultTransformService,
                null);
    }

    /**
     * 创建Solon Ai大模型消息网关实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param sessionRepository 会话仓储依赖。
     * @param dangerousCommandApprovalService dangerous命令审批服务依赖。
     * @param llmProviderService LLM提供方Service标识或键值。
     * @param toolResultTransformService 工具结果转换Service响应或执行结果。
     * @param toolCallLoopGuardrailService 工具CallLoop护栏服务依赖。
     */
    public SolonAiLlmGateway(
            AppConfig appConfig,
            SessionRepository sessionRepository,
            DangerousCommandApprovalService dangerousCommandApprovalService,
            LlmProviderService llmProviderService,
            ToolResultTransformService toolResultTransformService,
            ToolCallLoopGuardrailService toolCallLoopGuardrailService) {
        this(
                appConfig,
                sessionRepository,
                dangerousCommandApprovalService,
                llmProviderService,
                toolResultTransformService,
                toolCallLoopGuardrailService,
                null);
    }

    /**
     * 创建Solon Ai大模型消息网关实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param sessionRepository 会话仓储依赖。
     * @param dangerousCommandApprovalService dangerous命令审批服务依赖。
     * @param llmProviderService LLM提供方Service标识或键值。
     * @param toolResultTransformService 工具结果转换Service响应或执行结果。
     * @param toolCallLoopGuardrailService 工具CallLoop护栏服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     */
    public SolonAiLlmGateway(
            AppConfig appConfig,
            SessionRepository sessionRepository,
            DangerousCommandApprovalService dangerousCommandApprovalService,
            LlmProviderService llmProviderService,
            ToolResultTransformService toolResultTransformService,
            ToolCallLoopGuardrailService toolCallLoopGuardrailService,
            SecurityPolicyService securityPolicyService) {
        this.appConfig = appConfig;
        this.sessionRepository = sessionRepository;
        this.dangerousCommandApprovalService = dangerousCommandApprovalService;
        this.llmProviderService =
                llmProviderService == null ? new LlmProviderService(appConfig) : llmProviderService;
        this.toolResultTransformService =
                toolResultTransformService == null
                        ? new ToolResultTransformService()
                        : toolResultTransformService;
        this.toolCallLoopGuardrailService =
                toolCallLoopGuardrailService == null
                        ? new ToolCallLoopGuardrailService(appConfig)
                        : toolCallLoopGuardrailService;
        this.securityPolicyService =
                securityPolicyService == null
                        ? new SecurityPolicyService(appConfig)
                        : securityPolicyService;
        this.mediaInputBoundaryService = new MediaInputBoundaryService(appConfig);
        this.modelMetadataService = new ModelMetadataService(appConfig);
        if (this.dangerousCommandApprovalService != null) {
            this.dangerousCommandApprovalService.setSmartApprovalJudge(
                    new SolonAiSmartApprovalJudge());
        }
    }

    /**
     * 执行聊天相关逻辑。
     *
     * @param session 会话参数。
     * @param systemPrompt 系统提示词参数。
     * @param userMessage 用户消息参数。
     * @param toolObjects 工具Objects参数。
     * @return 返回chat结果。
     */
    @Override
    public LlmResult chat(
            SessionRecord session,
            String systemPrompt,
            String userMessage,
            List<Object> toolObjects)
            throws Exception {
        return chat(
                session, systemPrompt, userMessage, toolObjects, ConversationFeedbackSink.noop());
    }

    /**
     * 发起不携带任何调用方或内建工具的纯文本辅助调用。
     *
     * @param session 不持久化的辅助会话。
     * @param systemPrompt 系统提示词。
     * @param userMessage 用户输入。
     * @return 模型调用结果。
     */
    @Override
    public LlmResult chatTextOnly(SessionRecord session, String systemPrompt, String userMessage)
            throws Exception {
        Boolean previous = textOnlyCall.get();
        textOnlyCall.set(Boolean.TRUE);
        try {
            return chat(session, systemPrompt, userMessage, Collections.emptyList());
        } finally {
            if (previous == null) {
                textOnlyCall.remove();
            } else {
                textOnlyCall.set(previous);
            }
        }
    }

    /**
     * 执行聊天相关逻辑。
     *
     * @param session 会话参数。
     * @param systemPrompt 系统提示词参数。
     * @param userMessage 用户消息参数。
     * @param toolObjects 工具Objects参数。
     * @param feedbackSink 反馈Sink参数。
     * @return 返回chat结果。
     */
    @Override
    public LlmResult chat(
            SessionRecord session,
            String systemPrompt,
            String userMessage,
            List<Object> toolObjects,
            ConversationFeedbackSink feedbackSink)
            throws Exception {
        return chat(
                session,
                systemPrompt,
                userMessage,
                toolObjects,
                feedbackSink,
                ConversationEventSink.noop());
    }

    /**
     * 执行聊天相关逻辑。
     *
     * @param session 会话参数。
     * @param systemPrompt 系统提示词参数。
     * @param userMessage 用户消息参数。
     * @param toolObjects 工具Objects参数。
     * @param feedbackSink 反馈Sink参数。
     * @param eventSink 事件Sink参数。
     * @return 返回chat结果。
     */
    @Override
    public LlmResult chat(
            SessionRecord session,
            String systemPrompt,
            String userMessage,
            List<Object> toolObjects,
            ConversationFeedbackSink feedbackSink,
            ConversationEventSink eventSink)
            throws Exception {
        return executeWithFailover(
                session, systemPrompt, userMessage, toolObjects, feedbackSink, eventSink, false);
    }

    /**
     * 执行resume相关逻辑。
     *
     * @param session 会话参数。
     * @param systemPrompt 系统提示词参数。
     * @param toolObjects 工具Objects参数。
     * @return 返回resume结果。
     */
    @Override
    public LlmResult resume(SessionRecord session, String systemPrompt, List<Object> toolObjects)
            throws Exception {
        return resume(session, systemPrompt, toolObjects, ConversationFeedbackSink.noop());
    }

    /**
     * 执行resume相关逻辑。
     *
     * @param session 会话参数。
     * @param systemPrompt 系统提示词参数。
     * @param toolObjects 工具Objects参数。
     * @param feedbackSink 反馈Sink参数。
     * @return 返回resume结果。
     */
    @Override
    public LlmResult resume(
            SessionRecord session,
            String systemPrompt,
            List<Object> toolObjects,
            ConversationFeedbackSink feedbackSink)
            throws Exception {
        return resume(
                session, systemPrompt, toolObjects, feedbackSink, ConversationEventSink.noop());
    }

    /**
     * 执行resume相关逻辑。
     *
     * @param session 会话参数。
     * @param systemPrompt 系统提示词参数。
     * @param toolObjects 工具Objects参数。
     * @param feedbackSink 反馈Sink参数。
     * @param eventSink 事件Sink参数。
     * @return 返回resume结果。
     */
    @Override
    public LlmResult resume(
            SessionRecord session,
            String systemPrompt,
            List<Object> toolObjects,
            ConversationFeedbackSink feedbackSink,
            ConversationEventSink eventSink)
            throws Exception {
        return executeWithFailover(
                session, systemPrompt, null, toolObjects, feedbackSink, eventSink, true);
    }

    /**
     * 执行Once。
     *
     * @param session 会话参数。
     * @param systemPrompt 系统提示词参数。
     * @param userMessage 用户消息参数。
     * @param toolObjects 工具Objects参数。
     * @param feedbackSink 反馈Sink参数。
     * @param eventSink 事件Sink参数。
     * @param resume resume 参数。
     * @param resolved resolved 参数。
     * @param runContext 运行上下文。
     * @return 返回Once结果。
     */
    @Override
    public LlmResult executeOnce(
            SessionRecord session,
            String systemPrompt,
            String userMessage,
            List<Object> toolObjects,
            ConversationFeedbackSink feedbackSink,
            ConversationEventSink eventSink,
            boolean resume,
            AppConfig.LlmConfig resolved,
            AgentRunContext runContext)
            throws Exception {
        return executeSingle(
                session,
                systemPrompt,
                userMessage,
                toolObjects,
                feedbackSink,
                eventSink,
                resume,
                resolved,
                runContext);
    }

    /**
     * 执行With故障切换。
     *
     * @param session 会话参数。
     * @param systemPrompt 系统提示词参数。
     * @param userMessage 用户消息参数。
     * @param toolObjects 工具Objects参数。
     * @param feedbackSink 反馈Sink参数。
     * @param eventSink 事件Sink参数。
     * @param resume resume 参数。
     * @return 返回With故障切换结果。
     */
    private LlmResult executeWithFailover(
            SessionRecord session,
            String systemPrompt,
            String userMessage,
            List<Object> toolObjects,
            ConversationFeedbackSink feedbackSink,
            ConversationEventSink eventSink,
            boolean resume)
            throws Exception {
        List<AppConfig.LlmConfig> candidates = buildCandidateConfigs(session);
        Throwable lastError = null;
        boolean primary = true;
        boolean continueFromSnapshot = resume;
        ProgressUpdateEmitter progressUpdateEmitter =
                new ProgressUpdateEmitter(feedbackSink, eventSink, null);

        for (AppConfig.LlmConfig resolved : candidates) {
            int maxAttempts = configuredRetryMax(session) + 1;
            boolean allowFallback = true;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                String previousNdjson = session == null ? null : session.getNdjson();
                try {
                    ProgressUpdateEmitter previousEmitter = failoverProgressEmitter.get();
                    failoverProgressEmitter.set(progressUpdateEmitter);
                    LlmResult result;
                    try {
                        result =
                                executeSingle(
                                        session,
                                        systemPrompt,
                                        userMessage,
                                        toolObjects,
                                        feedbackSink,
                                        eventSink,
                                        resume || continueFromSnapshot,
                                        resolved,
                                        null);
                    } finally {
                        if (previousEmitter == null) {
                            failoverProgressEmitter.remove();
                        } else {
                            failoverProgressEmitter.set(previousEmitter);
                        }
                    }
                    if (isIncompleteFinishReason(result)) {
                        String finishReason = result.getFinishReason();
                        continueFromSnapshot =
                                continueFromSnapshot
                                        || rollbackIncompleteCandidate(
                                                session, previousNdjson, eventSink, finishReason);
                        lastError = incompleteFinishReasonError(finishReason);
                        allowFallback = true;
                        break;
                    }
                    continueFromSnapshot =
                            continueFromSnapshot
                                    || (session != null
                                            && !StrUtil.equals(
                                                    previousNdjson, session.getNdjson()));
                    if (hasVisibleContent(result.getAssistantMessage(), result.getRawResponse())) {
                        return result;
                    }
                    if (attempt < maxAttempts) {
                        log.warn(
                                "LLM empty response, retrying same provider: provider={}, dialect={}, model={}, attempt={}",
                                resolved.getProvider(),
                                resolved.getDialect(),
                                resolved.getModel(),
                                attempt);
                        waitBeforeRetry(session);
                        continue;
                    }
                    lastError = new IllegalStateException("LLM returned empty assistant content");
                } catch (InterruptedException e) {
                    throw e;
                } catch (Exception e) {
                    lastError = e;
                    continueFromSnapshot =
                            continueFromSnapshot
                                    || restoreSafeFallbackSnapshot(session, previousNdjson);
                    LlmErrorClassifier.ClassifiedError classified = LlmErrorClassifier.classify(e);
                    allowFallback = classified.isShouldFallback();
                    if (classified.shouldRetrySameProvider(attempt, maxAttempts)) {
                        log.warn(
                                "LLM request failed, retrying same provider: provider={}, dialect={}, model={}, attempt={}, message={}",
                                resolved.getProvider(),
                                resolved.getDialect(),
                                resolved.getModel(),
                                attempt,
                                e.getMessage());
                        waitBeforeRetry(session);
                        continue;
                    }
                }

                if (!primary) {
                    log.warn(
                            "Fallback provider failed, trying next candidate: provider={}, dialect={}, model={}, message={}",
                            resolved.getProvider(),
                            resolved.getDialect(),
                            resolved.getModel(),
                            lastError == null ? "" : lastError.getMessage());
                } else {
                    log.warn(
                            "Primary provider failed, switching to fallback candidate: provider={}, dialect={}, model={}, message={}",
                            resolved.getProvider(),
                            resolved.getDialect(),
                            resolved.getModel(),
                            lastError == null ? "" : lastError.getMessage());
                }
                break;
            }
            if (!allowFallback) {
                break;
            }
            primary = false;
        }

        if (lastError instanceof Exception) {
            throw (Exception) lastError;
        }
        throw new IllegalStateException(
                lastError == null ? "LLM execution failed" : lastError.getMessage(), lastError);
    }

    /** 判断模型结果是否仍处于不可直接展示的异常结束状态。 */
    private boolean isIncompleteFinishReason(LlmResult result) {
        String finishReason = result == null ? "" : StrUtil.nullToEmpty(result.getFinishReason());
        return isIncompleteFinishReason(finishReason);
    }

    /** 判断标准化结束原因是否代表当前模型响应不完整。 */
    private boolean isIncompleteFinishReason(String finishReason) {
        return "length".equals(finishReason) || "content_filter".equals(finishReason);
    }

    /** 构造异常结束状态对应的明确失败原因。 */
    private IllegalStateException incompleteFinishReasonError(String finishReason) {
        if ("content_filter".equals(finishReason)) {
            return new IllegalStateException("模型响应被内容安全策略过滤");
        }
        return new IllegalStateException("模型响应连续 " + MAX_LENGTH_CONTINUATIONS + " 次续写后仍被长度限制截断");
    }

    /** 回滚当前候选的可见正文，并保留已经完整落盘的工具事实供备用模型继续。 */
    private boolean rollbackIncompleteCandidate(
            SessionRecord session,
            String previousNdjson,
            ConversationEventSink eventSink,
            String finishReason)
            throws Exception {
        boolean resume = restoreSafeFallbackSnapshot(session, previousNdjson);
        ConversationEventSink sink = eventSink == null ? ConversationEventSink.noop() : eventSink;
        sink.onAssistantReset(finishReason);
        return resume;
    }

    /** 保存失败切换所需的最小安全快照，并返回备用模型是否应以恢复模式继续。 */
    private boolean restoreSafeFallbackSnapshot(SessionRecord session, String previousNdjson)
            throws Exception {
        if (session == null) {
            return false;
        }
        String safeNdjson = MessageSupport.safeFallbackNdjson(previousNdjson, session.getNdjson());
        boolean resume = !StrUtil.equals(previousNdjson, safeNdjson);
        session.setNdjson(safeNdjson);
        if (hasPersistentSession(session)) {
            sessionRepository.save(session);
        }
        return resume;
    }

    /** 仅真实渠道会话写入仓储；辅助模型使用的 synthetic 会话只在当前调用内维护快照。 */
    private boolean hasPersistentSession(SessionRecord session) {
        return sessionRepository != null
                && session != null
                && StrUtil.isNotBlank(session.getSourceKey())
                && session.getPersistedConcurrentSettings() != null;
    }

    /** 返回当前会话实际使用的决策重试次数。 */
    private int configuredRetryMax(SessionRecord session) {
        int configured =
                isDelegateSession(session)
                        ? appConfig.getReact().getDelegateRetryMax()
                        : appConfig.getReact().getRetryMax();
        return Math.max(0, configured);
    }

    /** 按主代理或子代理配置等待下一次模型决策重试。 */
    private void waitBeforeRetry(SessionRecord session) throws InterruptedException {
        int delayMs =
                isDelegateSession(session)
                        ? appConfig.getReact().getDelegateRetryDelayMs()
                        : appConfig.getReact().getRetryDelayMs();
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    /**
     * 执行Single。
     *
     * @param session 会话参数。
     * @param systemPrompt 系统提示词参数。
     * @param userMessage 用户消息参数。
     * @param toolObjects 工具Objects参数。
     * @param feedbackSink 反馈Sink参数。
     * @param eventSink 事件Sink参数。
     * @param resume resume 参数。
     * @param resolved resolved 参数。
     * @param runContext 运行上下文。
     * @return 返回Single结果。
     */
    protected LlmResult executeSingle(
            SessionRecord session,
            String systemPrompt,
            String userMessage,
            List<Object> toolObjects,
            ConversationFeedbackSink feedbackSink,
            ConversationEventSink eventSink,
            boolean resume,
            AppConfig.LlmConfig resolved,
            AgentRunContext runContext)
            throws Exception {
        ProgressUpdateEmitter progressUpdateEmitter = failoverProgressEmitter.get();
        return executeSingleWithProgress(
                session,
                systemPrompt,
                userMessage,
                toolObjects,
                feedbackSink,
                eventSink,
                resume,
                resolved,
                runContext,
                progressUpdateEmitter == null
                        ? new ProgressUpdateEmitter(feedbackSink, eventSink, runContext)
                        : progressUpdateEmitter);
    }

    /** 在主备模型尝试之间复用同一阶段说明限流器，保证单轮总上限不会因故障切换重置。 */
    private LlmResult executeSingleWithProgress(
            SessionRecord session,
            String systemPrompt,
            String userMessage,
            List<Object> toolObjects,
            ConversationFeedbackSink feedbackSink,
            ConversationEventSink eventSink,
            boolean resume,
            AppConfig.LlmConfig resolved,
            AgentRunContext runContext,
            ProgressUpdateEmitter progressUpdateEmitter)
            throws Exception {
        validate(resolved);
        log.info(
                "LLM {}: provider={}, dialect={}, model={}, sessionId={}, stream={}, sessionOverride={}",
                resume ? "resume" : "request",
                resolved.getProvider(),
                resolved.getDialect(),
                resolved.getModel(),
                session == null ? "" : StrUtil.nullToEmpty(session.getSessionId()),
                resolved.isStream(),
                session != null && StrUtil.isNotBlank(session.getModelOverride()));
        SqliteAgentSession agentSession =
                new SqliteAgentSession(
                        session, hasPersistentSession(session) ? sessionRepository : null);
        return executeOwnedReActLoop(
                session,
                agentSession,
                systemPrompt,
                userMessage,
                toolObjects,
                feedbackSink,
                eventSink,
                resume,
                resolved,
                runContext,
                progressUpdateEmitter);
    }

    /**
     * 执行项目自有ReAct循环。Solon AI在这里仅负责协议调用和工具描述，不接管循环。
     *
     * @param session 会话参数。
     * @param agentSession Agent会话参数。
     * @param systemPrompt 系统提示词参数。
     * @param userMessage 用户消息参数。
     * @param toolObjects 工具Objects参数。
     * @param feedbackSink 反馈Sink参数。
     * @param eventSink 事件Sink参数。
     * @param resume resume 参数。
     * @param resolved resolved 参数。
     * @param runContext 运行上下文。
     * @return 返回大模型执行结果。
     */
    private LlmResult executeOwnedReActLoop(
            SessionRecord session,
            SqliteAgentSession agentSession,
            String systemPrompt,
            String userMessage,
            List<Object> toolObjects,
            ConversationFeedbackSink feedbackSink,
            ConversationEventSink eventSink,
            boolean resume,
            AppConfig.LlmConfig resolved,
            AgentRunContext runContext,
            ProgressUpdateEmitter progressUpdateEmitter)
            throws Exception {
        ChatModel chatModel = buildChatModel(resolved, session);
        UsageCollector usageCollector = new UsageCollector();
        ChatOptions options =
                buildOwnedLoopOptions(
                        toolObjects, userMessage, feedbackSink, runContext, usageCollector);
        List<ReActInterceptor> interceptors = flatInterceptors(options);
        OwnedReActTrace trace =
                new OwnedReActTrace(
                        chatModel,
                        interceptors,
                        agentSession,
                        Prompt.of(StrUtil.nullToEmpty(userMessage)));
        for (ReActInterceptor interceptor : interceptors) {
            interceptor.onAgentStart(trace);
        }

        int maxSteps = ownedLoopMaxSteps(agentSession);
        AssistantMessage assistantMessage = null;
        String rawResponse = "";
        boolean streamed = false;
        String streamedReasoningText = null;
        String finishReason = "";
        if (resume) {
            agentSession.pending(false, null);
            assistantMessage =
                    restoreResumeAssistantToolCall(
                            agentSession, lastUnresolvedAssistantToolCall(session));
            agentSession.updateSnapshot();
        }

        try {
            for (int step = 1; step <= maxSteps; step++) {
                trace.nextStep();
                if (assistantMessage == null) {
                    String effectivePrompt =
                            resume || step > 1 ? null : userPromptText(userMessage, runContext);
                    ChatRequestDesc requestDesc =
                            buildOwnedLoopRequest(
                                    chatModel,
                                    agentSession,
                                    systemPrompt,
                                    options,
                                    effectivePrompt == null
                                            ? null
                                            : userPrompt(effectivePrompt, runContext, resolved));
                    OwnedModelResponse modelResponse =
                            executeOwnedModelRequest(
                                    requestDesc,
                                    agentSession,
                                    feedbackSink,
                                    eventSink,
                                    usageCollector,
                                    resolved.isStream(),
                                    progressUpdateEmitter);
                    modelResponse =
                            continueLengthLimitedResponse(
                                    chatModel,
                                    agentSession,
                                    systemPrompt,
                                    options,
                                    modelResponse,
                                    feedbackSink,
                                    eventSink,
                                    usageCollector,
                                    resolved.isStream(),
                                    progressUpdateEmitter);
                    rawResponse = modelResponse.rawResponse;
                    assistantMessage = modelResponse.assistantMessage;
                    finishReason = modelResponse.finishReason;
                    streamed = streamed || modelResponse.streamed;
                    streamedReasoningText =
                            StrUtil.blankToDefault(
                                    modelResponse.reasoningText, streamedReasoningText);
                    if (assistantMessage == null) {
                        assistantMessage = ChatMessage.ofAssistant(rawResponse);
                    }
                    trace.setLastReasonMessage(assistantMessage);
                    for (ReActInterceptor interceptor : interceptors) {
                        interceptor.onReasonEnd(
                                trace, modelResponse.response, assistantMessage, 0L);
                    }
                } else {
                    trace.setLastReasonMessage(assistantMessage);
                }

                if (isIncompleteFinishReason(finishReason)) {
                    return ownedLoopResult(
                            session,
                            agentSession,
                            resolved,
                            assistantMessage,
                            rawResponse,
                            usageCollector,
                            false,
                            streamed,
                            runContext,
                            streamedReasoningText,
                            finishReason);
                }

                List<ToolCall> calls = assistantMessage.getToolCalls();
                if (agentSession.isPending()) {
                    return ownedLoopResult(
                            session,
                            agentSession,
                            resolved,
                            assistantMessage,
                            rawResponse,
                            usageCollector,
                            true,
                            streamed,
                            runContext,
                            streamedReasoningText,
                            finishReason);
                }
                if (calls == null || calls.isEmpty()) {
                    calls = extractOwnedTextActionCalls(agentSession, assistantMessage, options);
                    if (calls != null && !calls.isEmpty()) {
                        AssistantMessage textActionAssistant =
                                lastUnresolvedAssistantToolCall(agentSession.getMessages());
                        if (textActionAssistant != null) {
                            assistantMessage = textActionAssistant;
                        }
                        trace.setLastReasonMessage(assistantMessage);
                    }
                }
                if (calls == null || calls.isEmpty()) {
                    return ownedLoopResult(
                            session,
                            agentSession,
                            resolved,
                            assistantMessage,
                            rawResponse,
                            usageCollector,
                            false,
                            streamed,
                            runContext,
                            streamedReasoningText,
                            finishReason);
                }
                ensureOwnedAssistantToolCallRecorded(agentSession, assistantMessage);
                for (ToolCall call : calls) {
                    if (call == null || StrUtil.isBlank(call.getName())) {
                        continue;
                    }
                    executeOwnedToolCall(
                            trace,
                            options,
                            call,
                            feedbackSink,
                            eventSink,
                            runContext,
                            interceptors,
                            progressUpdateEmitter);
                    if (agentSession.isPending() || isTraceEnded(trace)) {
                        assistantMessage =
                                ChatMessage.ofAssistant(
                                        StrUtil.blankToDefault(
                                                trace.getFinalAnswer(),
                                                agentSession.getPendingReason()));
                        return ownedLoopResult(
                                session,
                                agentSession,
                                resolved,
                                assistantMessage,
                                rawResponse,
                                usageCollector,
                                agentSession.isPending(),
                                streamed,
                                runContext,
                                streamedReasoningText,
                                finishReason);
                    }
                }
                resume = true;
                assistantMessage = null;
            }
        } finally {
            for (ReActInterceptor interceptor : interceptors) {
                interceptor.onAgentEnd(trace);
            }
            restoreInjectedUserMessage(agentSession, runContext);
        }

        AssistantMessage maxStepsMessage =
                ChatMessage.ofAssistant(
                        "Agent error: Maximum steps reached (" + Math.max(1, maxSteps) + ").");
        agentSession.addMessage(maxStepsMessage);
        return ownedLoopResult(
                session,
                agentSession,
                resolved,
                maxStepsMessage,
                rawResponse,
                usageCollector,
                false,
                streamed,
                runContext,
                streamedReasoningText,
                finishReason);
    }

    /** 对无工具调用的长度截断响应执行有限续写，并只向展示层发送去重后的新增正文。 */
    private OwnedModelResponse continueLengthLimitedResponse(
            ChatModel chatModel,
            SqliteAgentSession agentSession,
            String systemPrompt,
            ChatOptions options,
            OwnedModelResponse initial,
            ConversationFeedbackSink feedbackSink,
            ConversationEventSink eventSink,
            UsageCollector usageCollector,
            boolean streamConfigured,
            ProgressUpdateEmitter progressUpdateEmitter)
            throws Exception {
        if (initial == null
                || !"length".equals(initial.finishReason)
                || hasToolCalls(initial.assistantMessage)) {
            return initial;
        }

        ConversationEventSink effectiveEventSink =
                eventSink == null ? ConversationEventSink.noop() : eventSink;
        int transcriptStart = continuationTranscriptStart(agentSession, initial.assistantMessage);
        OwnedModelResponse current = initial;
        String combinedText = ownedVisibleText(initial);
        String reasoningText = initial.reasoningText;
        ensureOwnedAssistantRecorded(agentSession, initial.assistantMessage);
        try {
            for (int continuation = 0;
                    continuation < MAX_LENGTH_CONTINUATIONS
                            && "length".equals(current.finishReason)
                            && !hasToolCalls(current.assistantMessage);
                    continuation++) {
                agentSession.addMessage(ChatMessage.ofUser(LENGTH_CONTINUATION_PROMPT));

                BufferedAssistantEventSink bufferedSink =
                        new BufferedAssistantEventSink(effectiveEventSink);
                ChatRequestDesc requestDesc =
                        buildOwnedLoopRequest(chatModel, agentSession, systemPrompt, options, null);
                OwnedModelResponse next =
                        executeOwnedModelRequest(
                                requestDesc,
                                agentSession,
                                feedbackSink,
                                bufferedSink,
                                usageCollector,
                                streamConfigured,
                                progressUpdateEmitter);
                ensureOwnedAssistantRecorded(agentSession, next.assistantMessage);
                reasoningText = StrUtil.blankToDefault(next.reasoningText, reasoningText);

                if ("content_filter".equals(next.finishReason)
                        || hasToolCalls(next.assistantMessage)) {
                    current =
                            new OwnedModelResponse(
                                    next.response,
                                    ChatMessage.ofAssistant(combinedText),
                                    combinedText,
                                    initial.streamed || next.streamed,
                                    reasoningText,
                                    "content_filter".equals(next.finishReason)
                                            ? "content_filter"
                                            : "length");
                    break;
                }

                String nextText =
                        StrUtil.blankToDefault(
                                ownedVisibleText(next), bufferedSink.assistantText());
                String mergedText = mergeContinuationText(combinedText, nextText);
                String appendedText = mergedText.substring(combinedText.length());
                if (StrUtil.isNotEmpty(appendedText)) {
                    effectiveEventSink.onAssistantDelta(appendedText);
                }
                combinedText = mergedText;
                current =
                        new OwnedModelResponse(
                                next.response,
                                ChatMessage.ofAssistant(combinedText),
                                combinedText,
                                initial.streamed || next.streamed,
                                reasoningText,
                                next.finishReason);
            }
        } finally {
            discardLengthContinuationTranscript(agentSession, transcriptStart);
        }
        agentSession.addMessage(current.assistantMessage);
        return current;
    }

    /** 定位首个长度截断 assistant，后续内部消息只允许存在于本次模型请求期间。 */
    private int continuationTranscriptStart(
            SqliteAgentSession agentSession, AssistantMessage initialAssistant) {
        List<ChatMessage> messages = agentSession == null ? null : agentSession.getMessages();
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int last = messages.size() - 1;
        ChatMessage candidate = messages.get(last);
        return candidate instanceof AssistantMessage
                        && MessageSupport.sameVisibleContent(
                                (AssistantMessage) candidate, initialAssistant)
                ? last
                : messages.size();
    }

    /** 丢弃内部续写尾段，调用方随后只写入合并后的单条 assistant。 */
    private void discardLengthContinuationTranscript(
            SqliteAgentSession agentSession, int transcriptStart) {
        if (agentSession == null) {
            return;
        }
        List<ChatMessage> messages = agentSession.getMessages();
        int removeCount = messages == null ? 0 : Math.max(0, messages.size() - transcriptStart);
        agentSession.removeLatestMessage(removeCount);
    }

    /** 提取单次模型响应中可合并的正文。 */
    private String ownedVisibleText(OwnedModelResponse response) {
        if (response == null) {
            return "";
        }
        return StrUtil.blankToDefault(
                visibleResponseText(extractText(response.assistantMessage)),
                visibleResponseText(response.rawResponse));
    }

    /** 判断 assistant 是否携带工具调用，长度截断的工具参数不能继续执行。 */
    private boolean hasToolCalls(AssistantMessage assistantMessage) {
        return assistantMessage != null
                && assistantMessage.getToolCalls() != null
                && !assistantMessage.getToolCalls().isEmpty();
    }

    /** 确保续写前的 assistant 已保存到会话，同时避免框架已自动保存时重复追加。 */
    private void ensureOwnedAssistantRecorded(
            SqliteAgentSession agentSession, AssistantMessage assistantMessage) {
        if (agentSession == null || assistantMessage == null) {
            return;
        }
        List<ChatMessage> messages = agentSession.getMessages();
        if (messages == null) {
            return;
        }
        if (!messages.isEmpty()) {
            ChatMessage last = messages.get(messages.size() - 1);
            if (last instanceof AssistantMessage
                    && MessageSupport.sameVisibleContent(
                            (AssistantMessage) last, assistantMessage)) {
                return;
            }
        }
        agentSession.addMessage(assistantMessage);
    }

    /** 使用最大后缀/前缀重叠消除提供方续写时重复返回的开头。 */
    private String mergeContinuationText(String existing, String continuation) {
        String base = StrUtil.nullToEmpty(existing);
        String next = StrUtil.nullToEmpty(continuation);
        int maxOverlap = Math.min(base.length(), next.length());
        for (int overlap = maxOverlap; overlap > 0; overlap--) {
            if (base.regionMatches(base.length() - overlap, next, 0, overlap)) {
                return base + next.substring(overlap);
            }
        }
        return base + next;
    }

    /**
     * 执行自有Loop模型请求，并在可用时转发流式文本事件。
     *
     * @param requestDesc 请求描述。
     * @param agentSession 当前 Agent 会话，用于在流中断时保存已接收正文。
     * @param feedbackSink feedbackSink参数。
     * @param eventSink eventSink参数。
     * @param usageCollector 当前 ReAct 轮次的模型用量收集器。
     * @param streamConfigured 是否由运行配置明确启用流式请求。
     * @return 返回模型响应。
     */
    private OwnedModelResponse executeOwnedModelRequest(
            ChatRequestDesc requestDesc,
            SqliteAgentSession agentSession,
            ConversationFeedbackSink feedbackSink,
            ConversationEventSink eventSink,
            UsageCollector usageCollector,
            boolean streamConfigured,
            ProgressUpdateEmitter progressUpdateEmitter)
            throws Exception {
        boolean hasEventSink = eventSink != null && eventSink != ConversationEventSink.noop();
        if (!streamConfigured && !hasEventSink) {
            ChatResponse response = requestDesc.call();
            logIncompleteFinishReason(response);
            AssistantMessage responseMessage =
                    response == null ? null : response.getAggregationMessage();
            if (responseMessage == null && response != null) {
                responseMessage = response.getMessage();
            }
            if (responseMessage != null && responseMessage.isToolCalls()) {
                String progressText = progressUpdateEmitter.emit(extractText(responseMessage));
                responseMessage = copyAssistantWithProgressText(responseMessage, progressText);
            }
            return new OwnedModelResponse(
                    response,
                    responseMessage,
                    response == null ? "" : StrUtil.nullToEmpty(response.getContent()),
                    false,
                    null,
                    finishReason(response));
        }
        ConversationEventSink effectiveEventSink =
                eventSink == null ? ConversationEventSink.noop() : eventSink;

        final StringBuilder emittedText = new StringBuilder();
        final StringBuilder bufferedVisibleText = new StringBuilder();
        final ChatResponse[] finalResponse = new ChatResponse[1];
        final boolean[] observedToolCall = new boolean[1];
        final ThinkingStreamSplitter thinkingSplitter = new ThinkingStreamSplitter();
        final MemoryContextBoundary.StreamingScrubber memoryScrubber =
                new MemoryContextBoundary.StreamingScrubber();

        try {
            requestDesc.stream()
                    .doOnNext(
                            chunk ->
                                    handleOwnedStreamChunk(
                                            chunk,
                                            emittedText,
                                            bufferedVisibleText,
                                            thinkingSplitter,
                                            effectiveEventSink,
                                            feedbackSink,
                                            finalResponse,
                                            observedToolCall,
                                            memoryScrubber))
                    .blockLast(MODEL_STREAM_TIMEOUT);
        } catch (Throwable e) {
            emitThinking(
                    thinkingSplitter.flushPending(),
                    emittedText,
                    bufferedVisibleText,
                    effectiveEventSink,
                    feedbackSink,
                    memoryScrubber);
            String remainingVisible = memoryScrubber.flush();
            if (StrUtil.isNotBlank(remainingVisible)) {
                bufferedVisibleText.append(remainingVisible);
            }
            String partial = visibleResponseText(bufferedVisibleText.toString());
            boolean unresolvedToolCall =
                    observedToolCall[0]
                            || lastUnresolvedAssistantToolCall(agentSession.getMessages()) != null;
            if (StrUtil.isNotBlank(partial) && !unresolvedToolCall) {
                String interrupted = partial + "\n\n（响应流意外中断，以上为已接收的部分内容）";
                AssistantMessage assistantMessage = ChatMessage.ofAssistant(interrupted);
                recordOwnedPartialAssistant(agentSession, assistantMessage);
                effectiveEventSink.onAssistantDelta(interrupted);
            }
            if (e instanceof Exception) {
                throw (Exception) e;
            }
            throw new IllegalStateException("Owned ReAct stream failed", e);
        }

        emitThinking(
                thinkingSplitter.flushPending(),
                emittedText,
                bufferedVisibleText,
                effectiveEventSink,
                feedbackSink,
                memoryScrubber);
        String remainingVisible = memoryScrubber.flush();
        if (StrUtil.isNotBlank(remainingVisible)) {
            bufferedVisibleText.append(remainingVisible);
        }

        ChatResponse response = finalResponse[0];
        logIncompleteFinishReason(response);
        if (response != null && usageCollector != null) {
            usageCollector.addFinalStreamUsage(response.getUsage());
        }
        AssistantMessage assistantMessage = null;
        String rawResponse = bufferedVisibleText.toString();
        if (response != null) {
            assistantMessage = response.getAggregationMessage();
            if (assistantMessage == null) {
                assistantMessage = response.getMessage();
            }
            rawResponse =
                    StrUtil.blankToDefault(
                            response.getAggregationContent(),
                            StrUtil.blankToDefault(response.getContent(), rawResponse));
        }
        if (assistantMessage == null) {
            assistantMessage = ChatMessage.ofAssistant(rawResponse);
        }

        String finalText = visibleResponseText(extractText(assistantMessage));
        if (assistantMessage.isToolCalls()) {
            // 工具调用轮的短文本作为独立阶段说明发送，不能进入最终答复增量。
            String progressText = progressUpdateEmitter.emit(finalText);
            assistantMessage = copyAssistantWithProgressText(assistantMessage, progressText);
            return new OwnedModelResponse(
                    response,
                    assistantMessage,
                    StrUtil.blankToDefault(finalText, rawResponse),
                    true,
                    thinkingSplitter.reasoningText(),
                    finishReason(response));
        }
        if (emittedText.length() == 0 && bufferedVisibleText.length() > 0) {
            // 确认本轮不是工具调用后，再把流式可见文本作为用户可见答复发出。
            String buffered = visibleResponseText(bufferedVisibleText.toString());
            if (StrUtil.isNotBlank(buffered)) {
                emittedText.append(buffered);
                effectiveEventSink.onAssistantDelta(buffered);
            }
        }
        String emitted = emittedText.toString();
        if (StrUtil.isNotBlank(finalText) && finalText.startsWith(emitted)) {
            String tail = visibleResponseText(finalText.substring(emitted.length()));
            if (StrUtil.isNotBlank(tail)) {
                effectiveEventSink.onAssistantDelta(tail);
            }
        } else if (StrUtil.isBlank(emitted)
                && StrUtil.isNotBlank(finalText)
                && !StrUtil.equals(finalText, emitted)) {
            // 只有前面没有发送过可见分片时，才用聚合内容补一次；避免前端收到重复完整回复。
            effectiveEventSink.onAssistantDelta(finalText);
        }

        return new OwnedModelResponse(
                response,
                assistantMessage,
                StrUtil.blankToDefault(finalText, rawResponse),
                true,
                thinkingSplitter.reasoningText(),
                finishReason(response));
    }

    /**
     * 处理自有Loop模型流式响应分片。
     *
     * @param chunk 响应分片。
     * @param emittedText 已发送文本。
     * @param thinkingSplitter 思考分离器。
     * @param eventSink eventSink参数。
     * @param feedbackSink feedbackSink参数。
     * @param finalResponse 最终响应容器。
     * @param observedToolCall 是否观察到未完成工具调用。
     * @param memoryScrubber 记忆边界过滤器。
     */
    private void handleOwnedStreamChunk(
            ChatResponse chunk,
            StringBuilder emittedText,
            StringBuilder bufferedVisibleText,
            ThinkingStreamSplitter thinkingSplitter,
            ConversationEventSink eventSink,
            ConversationFeedbackSink feedbackSink,
            ChatResponse[] finalResponse,
            boolean[] observedToolCall,
            MemoryContextBoundary.StreamingScrubber memoryScrubber) {
        if (chunk == null) {
            return;
        }
        finalResponse[0] = chunk;
        AssistantMessage aggregationMessage = chunk.getAggregationMessage();
        if (aggregationMessage != null && aggregationMessage.isToolCalls()) {
            observedToolCall[0] = true;
        }
        AssistantMessage message = chunk.getMessage();
        if (message == null) {
            return;
        }
        if (message.isToolCalls()) {
            observedToolCall[0] = true;
            return;
        }
        String delta = message.getContent();
        if (StrUtil.isBlank(delta)) {
            return;
        }
        emitThinking(
                thinkingSplitter.accept(delta, message.isThinking()),
                emittedText,
                bufferedVisibleText,
                eventSink,
                feedbackSink,
                memoryScrubber);
    }

    /**
     * 将流中断前的部分正文写入当前会话，避免成功返回后持久化历史缺少该条答复。
     *
     * @param agentSession 当前 Agent 会话。
     * @param assistantMessage 带中断说明的部分答复。
     */
    private void recordOwnedPartialAssistant(
            SqliteAgentSession agentSession, AssistantMessage assistantMessage) {
        List<ChatMessage> messages = agentSession.getMessages();
        int assistantIndex = lastAssistantMessageIndex(messages);
        if (assistantIndex >= 0) {
            messages.set(assistantIndex, assistantMessage);
        } else {
            agentSession.addMessage(assistantMessage);
        }
    }

    /**
     * 找出会话末尾尚未产生tool message的assistant tool_call，用于审批恢复后直接续跑。
     *
     * @param agentSession Agent会话。
     * @return 返回未完成tool_call对应的assistant消息。
     */
    private AssistantMessage lastUnresolvedAssistantToolCall(SessionRecord session) {
        if (session == null || StrUtil.isBlank(session.getNdjson())) {
            return null;
        }
        try {
            return lastUnresolvedAssistantToolCall(
                    MessageSupport.loadMessages(session.getNdjson()));
        } catch (Exception e) {
            log.warn(
                    "load raw session messages for owned loop resume failed: sessionId={}, error={}",
                    session.getSessionId(),
                    safeError(e));
            return null;
        }
    }

    /**
     * 找出消息末尾尚未产生tool message的assistant tool_call。
     *
     * @param messages 消息列表。
     * @return 返回未完成tool_call对应的assistant消息。
     */
    private AssistantMessage lastUnresolvedAssistantToolCall(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        Set<String> completedCallIds = new HashSet<String>();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message instanceof ToolMessage) {
                completedCallIds.add(StrUtil.nullToEmpty(((ToolMessage) message).getToolCallId()));
                continue;
            }
            if (message instanceof AssistantMessage) {
                AssistantMessage assistant = (AssistantMessage) message;
                List<ToolCall> calls = assistant.getToolCalls();
                if (calls == null || calls.isEmpty()) {
                    return null;
                }
                for (ToolCall call : calls) {
                    if (call != null
                            && !completedCallIds.contains(StrUtil.nullToEmpty(call.getId()))) {
                        return assistant;
                    }
                }
                return null;
            }
            if (message != null
                    && "user"
                            .equalsIgnoreCase(
                                    StrUtil.nullToEmpty(String.valueOf(message.getRole())))) {
                return null;
            }
        }
        return null;
    }

    /**
     * 恢复审批续跑前被消息修复剪掉的assistant tool_call。
     *
     * @param agentSession Agent会话。
     * @param rawAssistant 原始assistant tool_call消息。
     * @return 返回可继续执行的assistant消息。
     */
    private AssistantMessage restoreResumeAssistantToolCall(
            SqliteAgentSession agentSession, AssistantMessage rawAssistant) {
        if (agentSession == null) {
            return rawAssistant;
        }
        AssistantMessage cachedAssistant =
                lastUnresolvedAssistantToolCall(agentSession.getMessages());
        if (cachedAssistant != null) {
            return cachedAssistant;
        }
        if (rawAssistant == null) {
            return null;
        }
        List<ChatMessage> messages = agentSession.getMessages();
        if (messages == null) {
            return rawAssistant;
        }
        int replaceIndex = lastAssistantMessageIndex(messages);
        if (replaceIndex >= 0) {
            messages.set(replaceIndex, rawAssistant);
        } else {
            messages.add(rawAssistant);
        }
        return rawAssistant;
    }

    /**
     * 查找最后一条assistant消息索引。
     *
     * @param messages 消息列表。
     * @return 返回索引，未找到返回-1。
     */
    private int lastAssistantMessageIndex(List<ChatMessage> messages) {
        if (messages == null) {
            return -1;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message instanceof AssistantMessage) {
                return i;
            }
            if (message instanceof ToolMessage) {
                return -1;
            }
            if (message != null
                    && "user"
                            .equalsIgnoreCase(
                                    StrUtil.nullToEmpty(String.valueOf(message.getRole())))) {
                return -1;
            }
        }
        return -1;
    }

    /**
     * 构建自有Loop模型请求。
     *
     * @param chatModel ChatModel参数。
     * @param agentSession Agent会话参数。
     * @param systemPrompt 系统提示词参数。
     * @param options 选项参数。
     * @param prompt 本轮新增Prompt；恢复或工具后继续时为空。
     * @return 返回请求描述。
     */
    private ChatRequestDesc buildOwnedLoopRequest(
            ChatModel chatModel,
            SqliteAgentSession agentSession,
            String systemPrompt,
            ChatOptions options,
            Prompt prompt) {
        ChatRequestDesc desc =
                prompt == null ? chatModel.prompt(Prompt.of()) : chatModel.prompt(prompt);
        return desc.session(agentSession)
                .options(
                        current -> {
                            current.systemPrompt(systemPrompt);
                            current.autoToolCall(false);
                            current.toolContextPut(options.toolContext());
                            current.toolAdd(options.tools());
                            current.interceptorAdd(options.interceptors());
                            if (StrUtil.isNotBlank(options.instruction())) {
                                current.instruction(options.instruction());
                            }
                        });
    }

    /**
     * 从文本 ReAct Action 中提取工具调用，并修正会话中的 assistant 消息形态。
     *
     * @param agentSession Agent会话。
     * @param assistantMessage assistant消息。
     * @param options Chat选项。
     * @return 返回可执行工具调用。
     */
    private List<ToolCall> extractOwnedTextActionCalls(
            SqliteAgentSession agentSession,
            AssistantMessage assistantMessage,
            ChatOptions options) {
        ToolCall call = parseOwnedTextActionCall(assistantMessage, options);
        if (call == null) {
            return Collections.emptyList();
        }
        List<ToolCall> calls = new ArrayList<ToolCall>();
        calls.add(call);
        AssistantMessage replacement = copyAssistantWithToolCalls(assistantMessage, calls);
        List<ChatMessage> messages = agentSession == null ? null : agentSession.getMessages();
        int assistantIndex = lastAssistantMessageIndex(messages);
        if (assistantIndex >= 0) {
            messages.set(assistantIndex, replacement);
        } else if (messages != null) {
            // 连续文本 Action 可能出现在上一轮工具结果之后，此时不能替换历史 assistant，需要追加可执行形态。
            messages.add(replacement);
        }
        return calls;
    }

    /**
     * 解析文本 Action 工具调用。
     *
     * @param assistantMessage assistant消息。
     * @param options Chat选项。
     * @return 返回工具调用。
     */
    private ToolCall parseOwnedTextActionCall(
            AssistantMessage assistantMessage, ChatOptions options) {
        if (assistantMessage == null || options == null) {
            return null;
        }
        String content = StrUtil.nullToEmpty(assistantMessage.getResultContent());
        if (StrUtil.isBlank(content)) {
            content = StrUtil.nullToEmpty(assistantMessage.getContent());
        }
        int actionIndex = findOwnedActionLabel(content);
        if (actionIndex < 0) {
            return null;
        }
        String actionText = content.substring(actionIndex + "Action:".length()).trim();
        if (StrUtil.isBlank(actionText)) {
            return null;
        }

        String firstActionLine = firstNonBlankLine(actionText);
        ParsedTextAction parsed =
                firstActionLine.startsWith("{") ? parseOwnedJsonAction(actionText) : null;
        if (parsed == null || StrUtil.isBlank(parsed.name)) {
            parsed = parseOwnedPlainAction(actionText, content);
        }
        if (parsed == null || StrUtil.isBlank(parsed.name) || options.tool(parsed.name) == null) {
            return null;
        }
        Map<String, Object> arguments =
                parsed.arguments == null
                        ? new LinkedHashMap<String, Object>()
                        : new LinkedHashMap<String, Object>(parsed.arguments);
        String argumentsJson =
                parsed.argumentsValid
                        ? ONode.serialize(arguments)
                        : StrUtil.nullToEmpty(parsed.rawArguments);
        return new ToolCall(
                "0",
                "call-text-action-" + IdSupport.newId(),
                parsed.name,
                argumentsJson,
                arguments);
    }

    /**
     * 查找文本 Action 标签。
     *
     * @param content 文本内容。
     * @return 返回标签索引。
     */
    private int findOwnedActionLabel(String content) {
        if (StrUtil.isBlank(content)) {
            return -1;
        }
        int index = content.indexOf("Action:");
        while (index >= 0) {
            if (index == 0
                    || content.charAt(index - 1) == '\n'
                    || content.charAt(index - 1) == '\r') {
                return index;
            }
            index = content.indexOf("Action:", index + 1);
        }
        return -1;
    }

    /**
     * 解析 JSON Action。
     *
     * @param actionText Action后的文本。
     * @return 返回解析结果。
     */
    private ParsedTextAction parseOwnedJsonAction(String actionText) {
        String json = extractFirstJsonObject(actionText);
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            Object parsed = ONode.deserialize(json, Object.class);
            if (!(parsed instanceof Map)) {
                return null;
            }
            Map<?, ?> map = (Map<?, ?>) parsed;
            String name = textValue(firstPresent(map, "name", "tool", "tool_name"));
            Object rawArguments = firstPresent(map, "arguments", "args", "input");
            Map<String, Object> arguments =
                    rawArguments == null ? null : toArgumentsMap(rawArguments);
            return new ParsedTextAction(
                    name,
                    arguments,
                    rawArguments != null && arguments != null,
                    rawArguments == null ? "" : String.valueOf(rawArguments));
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 解析纯文本 Action。
     *
     * @param actionText Action后的文本。
     * @param content 完整内容。
     * @return 返回解析结果。
     */
    private ParsedTextAction parseOwnedPlainAction(String actionText, String content) {
        String line = firstNonBlankLine(actionText);
        if (StrUtil.isBlank(line)) {
            return null;
        }
        String name = stripActionToken(line);
        ParsedToolArguments arguments = parseOwnedActionInput(content);
        return new ParsedTextAction(
                name, arguments.arguments, arguments.valid, arguments.rawArguments);
    }

    /**
     * 解析 Action Input 参数。
     *
     * @param content 完整内容。
     * @return 返回参数。
     */
    private ParsedToolArguments parseOwnedActionInput(String content) {
        int inputIndex = content.indexOf("Action Input:");
        int labelLength = "Action Input:".length();
        if (inputIndex < 0) {
            inputIndex = content.indexOf("ActionInput:");
            labelLength = "ActionInput:".length();
        }
        if (inputIndex < 0) {
            return ParsedToolArguments.invalid("");
        }
        String input = content.substring(inputIndex + labelLength).trim();
        if (StrUtil.isBlank(input)) {
            return ParsedToolArguments.invalid(input);
        }
        try {
            Map<String, Object> arguments = toArgumentsMap(ONode.deserialize(input, Object.class));
            return arguments == null
                    ? ParsedToolArguments.invalid(input)
                    : ParsedToolArguments.valid(arguments, input);
        } catch (Throwable e) {
            return ParsedToolArguments.invalid(input);
        }
    }

    /**
     * 提取第一个 JSON 对象。
     *
     * @param text 文本。
     * @return 返回JSON对象文本。
     */
    private String extractFirstJsonObject(String text) {
        if (StrUtil.isBlank(text)) {
            return null;
        }
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    /**
     * 获取首个非空行。
     *
     * @param text 文本。
     * @return 返回行文本。
     */
    private String firstNonBlankLine(String text) {
        if (StrUtil.isBlank(text)) {
            return "";
        }
        String[] lines = text.split("\\r?\\n");
        for (String line : lines) {
            if (StrUtil.isNotBlank(line)) {
                return line.trim();
            }
        }
        return "";
    }

    /**
     * 清理Action工具名。
     *
     * @param text 原始文本。
     * @return 返回工具名。
     */
    private String stripActionToken(String text) {
        String result = StrUtil.nullToEmpty(text).trim();
        if (result.startsWith("`") && result.endsWith("`") && result.length() > 1) {
            result = result.substring(1, result.length() - 1).trim();
        }
        if ((result.startsWith("\"") && result.endsWith("\""))
                || (result.startsWith("'") && result.endsWith("'"))) {
            result = result.substring(1, result.length() - 1).trim();
        }
        int space = result.indexOf(' ');
        if (space > 0) {
            result = result.substring(0, space).trim();
        }
        return result;
    }

    /**
     * 获取第一个存在的值。
     *
     * @param map map参数。
     * @param keys keys参数。
     * @return 返回值。
     */
    private Object firstPresent(Map<?, ?> map, String... keys) {
        if (map == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
    }

    /**
     * 转换文本值。
     *
     * @param value 值。
     * @return 返回文本。
     */
    private String textValue(Object value) {
        return value == null ? "" : StrUtil.nullToEmpty(String.valueOf(value)).trim();
    }

    /**
     * 转换工具参数。
     *
     * @param value 值。
     * @return 返回参数Map。
     */
    private Map<String, Object> toArgumentsMap(Object value) {
        if (value instanceof Map) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (entry.getKey() != null) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return result;
        }
        if (value instanceof String && StrUtil.isNotBlank((String) value)) {
            try {
                return toArgumentsMap(ONode.deserialize((String) value, Object.class));
            } catch (RuntimeException e) {
                log.debug("Tool call arguments are not a JSON object: {}", safeError(e));
                return null;
            }
        }
        return null;
    }

    /**
     * 复制assistant消息并设置工具调用。
     *
     * @param source 原消息。
     * @param calls 工具调用。
     * @return 返回复制消息。
     */
    private AssistantMessage copyAssistantWithToolCalls(
            AssistantMessage source, List<ToolCall> calls) {
        AssistantMessage replacement =
                new AssistantMessage(
                        source == null ? "" : source.getContent(),
                        source != null && source.isThinking(),
                        source == null ? null : source.getContentRaw(),
                        Collections.<Map>emptyList(),
                        calls,
                        source == null
                                ? Collections.<Map>emptyList()
                                : source.getSearchResultsRaw());
        if (source != null) {
            replacement.reasoningFieldName(source.getReasoningFieldName());
        }
        return replacement;
    }

    /**
     * 构建自有Loop Chat选项。
     *
     * @param toolObjects 工具Objects参数。
     * @param userMessage 用户消息参数。
     * @param feedbackSink 反馈Sink参数。
     * @param runContext 运行上下文。
     * @param usageCollector 用量Collector参数。
     * @return 返回Chat选项。
     */
    private ChatOptions buildOwnedLoopOptions(
            List<Object> toolObjects,
            String userMessage,
            ConversationFeedbackSink feedbackSink,
            AgentRunContext runContext,
            UsageCollector usageCollector) {
        ChatOptions options = ChatOptions.of().autoToolCall(false);
        options.toolContextPut("user_message", userMessage);
        options.interceptorAdd(new ToolRetryInterceptor());
        options.interceptorAdd(new ToolSanitizerInterceptor(ownedToolSanitizerLimit()));
        if (dangerousCommandApprovalService != null) {
            options.interceptorAdd(
                    dangerousCommandApprovalService.buildInterceptor(
                            runContext == null ? null : runContext.getWorkspaceDir()));
        }
        options.interceptorAdd(toolCallLoopGuardrailService.buildInterceptor());
        options.interceptorAdd(toolResultTransformService.buildInterceptor());
        ToolResultStorageService toolResultStorageService =
                new ToolResultStorageService(appConfig, resolveWorkspace(runContext));
        options.interceptorAdd(
                new ToolResultStorageInterceptor(
                        toolResultStorageService,
                        runContext == null ? null : runContext.getRunId()));
        if (runContext != null) {
            options.interceptorAdd(
                    new TracingReActInterceptor(
                            runContext,
                            appConfig.getTrace().getToolPreviewLength(),
                            appConfig.getTask().getToolOutputInlineLimit(),
                            dangerousCommandApprovalService));
            if (runContext.hasToolPolicy()) {
                options.interceptorAdd(new ToolPolicyInterceptor(runContext));
            }
        }
        if (feedbackSink != null && feedbackSink != ConversationFeedbackSink.noop()) {
            options.interceptorAdd(
                    new FeedbackInterceptor(feedbackSink, dangerousCommandApprovalService));
        }
        if (usageCollector != null) {
            options.interceptorAdd(new UsageCollectingInterceptor(usageCollector));
        }
        if (toolObjects != null) {
            for (Object toolObject : toolObjects) {
                addOwnedLoopTool(
                        options,
                        sanitizeToolObject(toolObject),
                        Prompt.of(StrUtil.nullToEmpty(userMessage)));
            }
        }
        if (shouldAttachBuiltinTools()) {
            addOwnedLoopTool(
                    options,
                    sanitizeToolObject(pdfSkill()),
                    Prompt.of(StrUtil.nullToEmpty(userMessage)));
        }
        return options;
    }

    /** 当前调用允许附加 PDF 等内建工具时返回 true。 */
    boolean shouldAttachBuiltinTools() {
        return !Boolean.TRUE.equals(textOnlyCall.get());
    }

    /**
     * 上游 ToolSanitizer 默认只保留 2000 字符；本项目由 ToolResultStorageService 负责工具输出的内联、
     * 落盘与分页提示，因此这里放宽到本项目内联上限，避免 read_file 等结构化结果在进入存储策略前被截断。
     *
     * @return 返回自有 ReAct loop 的工具净化上限。
     */
    private int ownedToolSanitizerLimit() {
        int inlineLimit =
                appConfig == null || appConfig.getTask() == null
                        ? 50000
                        : appConfig.getTask().getToolOutputInlineLimit();
        return Math.max(2000, inlineLimit);
    }

    /**
     * 向自有Loop注册工具对象。
     *
     * @param options Chat选项。
     * @param toolObject 工具对象。
     * @param prompt 当前Prompt。
     */
    private void addOwnedLoopTool(ChatOptions options, Object toolObject, Prompt prompt) {
        if (options == null || toolObject == null) {
            return;
        }
        if (toolObject instanceof FunctionTool) {
            options.toolAdd((FunctionTool) toolObject);
        } else if (toolObject instanceof ToolProvider) {
            options.toolAdd((ToolProvider) toolObject);
        } else if (toolObject instanceof Talent) {
            Talent talent = (Talent) toolObject;
            if (talent.isSupported(prompt)) {
                talent.onAttach(prompt);
                options.toolAdd(talent.getTools(prompt));
                String instruction = talent.getInstruction(prompt);
                if (StrUtil.isNotBlank(instruction)) {
                    String existing = StrUtil.nullToEmpty(options.instruction());
                    options.instruction(existing + "\n\n" + instruction.trim());
                }
            }
        } else {
            options.toolAdd(new MethodToolProvider(toolObject));
        }
    }

    /**
     * 执行一个自有Loop工具调用。
     *
     * @param trace trace参数。
     * @param options Chat选项。
     * @param call 工具调用。
     * @param feedbackSink 反馈Sink参数。
     * @param eventSink 事件Sink参数。
     * @param runContext 运行上下文。
     * @param interceptors 拦截器集合。
     * @param progressUpdateEmitter 阶段进度发送器。
     */
    private void executeOwnedToolCall(
            OwnedReActTrace trace,
            ChatOptions options,
            ToolCall call,
            ConversationFeedbackSink feedbackSink,
            ConversationEventSink eventSink,
            AgentRunContext runContext,
            List<ReActInterceptor> interceptors,
            ProgressUpdateEmitter progressUpdateEmitter)
            throws Exception {
        clearCurrentThreadToolApprovals();
        try {
            executeOwnedToolCallWithinApprovalScope(
                    trace,
                    options,
                    call,
                    feedbackSink,
                    eventSink,
                    runContext,
                    interceptors,
                    progressUpdateEmitter);
        } finally {
            if (progressUpdateEmitter != null) {
                progressUpdateEmitter.onToolFinished();
            }
            clearCurrentThreadToolApprovals();
            if (dangerousCommandApprovalService != null
                    && trace != null
                    && trace.getSession() != null
                    && !trace.getSession().isPending()) {
                dangerousCommandApprovalService.clearWorkspaceToolCallApprovals(trace.getSession());
            }
        }
    }

    /** 清理单次工具调用遗留的命令与文件策略审批。 */
    private void clearCurrentThreadToolApprovals() {
        DangerousCommandApprovalService.clearCurrentThreadApprovals();
        SecurityPolicyService.clearCurrentThreadPolicyApprovals();
    }

    /**
     * 在已建立的一次工具调用审批作用域内执行拦截器与真实 handler。
     *
     * @param trace trace 参数。
     * @param options Chat 选项。
     * @param call 工具调用。
     * @param feedbackSink 反馈输出。
     * @param eventSink 事件输出。
     * @param runContext 运行上下文。
     * @param interceptors 工具拦截器。
     * @param progressUpdateEmitter 阶段进度发送器。
     */
    private void executeOwnedToolCallWithinApprovalScope(
            OwnedReActTrace trace,
            ChatOptions options,
            ToolCall call,
            ConversationFeedbackSink feedbackSink,
            ConversationEventSink eventSink,
            AgentRunContext runContext,
            List<ReActInterceptor> interceptors,
            ProgressUpdateEmitter progressUpdateEmitter)
            throws Exception {
        String toolName = call.getName();
        String argumentsError = validateToolCallArguments(call);
        if (argumentsError != null) {
            appendOwnedToolMessage(trace, argumentsError, toolName, call, false, true);
            trace.incrementToolCallCount();
            if (eventSink != null) {
                eventSink.onToolCompleted(toolName, argumentsError, argumentsError, 0L);
            }
            return;
        }
        Map<String, Object> args =
                call.getArguments() == null
                        ? Collections.<String, Object>emptyMap()
                        : call.getArguments();
        args =
                normalizeOwnedToolArguments(
                        toolName, args, options == null ? null : options.toolContext());
        FunctionTool tool = options.tool(toolName);
        long startedAt = System.currentTimeMillis();
        ToolExchanger exchanger = new ToolExchanger(toolName, args);
        ReActToolObservationSupport.clear(trace, exchanger);
        for (ReActInterceptor interceptor : interceptors) {
            interceptor.onAction(trace, exchanger);
        }
        if (trace.getSession().isPending() || isTraceEnded(trace)) {
            trace.getSession().updateSnapshot();
            return;
        }
        if (StrUtil.isNotBlank(ReActToolObservationSupport.get(trace, exchanger))) {
            String skipped = ReActToolObservationSupport.get(trace, exchanger);
            long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
            for (ReActInterceptor interceptor : interceptors) {
                interceptor.onObservation(trace, exchanger, null, null, durationMs);
            }
            appendOwnedToolMessage(
                    trace,
                    StrUtil.blankToDefault(
                            ReActToolObservationSupport.get(trace, exchanger), skipped),
                    toolName,
                    call,
                    false,
                    false);
            trace.incrementToolCallCount();
            return;
        }
        if (tool == null) {
            String missing = "Tool call not found: " + toolName;
            ReActToolObservationSupport.set(trace, exchanger, missing);
            appendOwnedToolMessage(trace, missing, toolName, call, false, true);
            return;
        }
        if (eventSink != null) {
            eventSink.onToolStarted(toolName, args);
        }
        if (progressUpdateEmitter != null) {
            progressUpdateEmitter.onToolStarted(toolName);
        }

        ToolResult toolResult;
        try {
            ToolRequest toolRequest = new ToolRequest(null, options.toolContext(), args);
            ToolChain<ReActInterceptor> chain =
                    new ToolChain<ReActInterceptor>(rankedInterceptors(interceptors), tool);
            toolResult = chain.doIntercept(toolRequest);
        } catch (Throwable e) {
            toolResult =
                    ToolResult.error("Execution error in tool [" + toolName + "]: " + safeError(e));
        }
        long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
        String observation = toolResult == null ? "" : StrUtil.nullToEmpty(toolResult.getContent());
        ReActToolObservationSupport.set(trace, exchanger, observation);
        ChatMessage toolMessage =
                ChatMessage.ofTool(toolResult, toolName, call.getId(), tool.returnDirect());
        String rawToolError = structuredToolError(toolResult, observation);
        Throwable observationError =
                rawToolError == null ? null : new IllegalStateException(rawToolError);
        for (ReActInterceptor interceptor : interceptors) {
            interceptor.onObservation(trace, exchanger, toolMessage, observationError, durationMs);
        }
        String finalObservation =
                StrUtil.blankToDefault(
                        ReActToolObservationSupport.get(trace, exchanger), observation);
        String toolError =
                StrUtil.blankToDefault(
                        rawToolError, structuredToolError(toolResult, finalObservation));
        appendOwnedToolMessage(
                trace, finalObservation, toolName, call, tool.returnDirect(), toolError != null);
        trace.incrementToolCallCount();
        if (eventSink != null) {
            eventSink.onToolCompleted(toolName, finalObservation, toolError, durationMs);
        }
    }

    /**
     * 从工具执行结果提取明确失败原因，供各交互端一致标记失败状态。
     *
     * <p>仅接受 Solon {@link ToolResult} 的 error 标志和本项目统一 envelope 的 JSON status， 不对普通文本做关键字推断。
     *
     * @param toolResult Solon 工具执行结果。
     * @param observation 最终写回模型的工具观察文本。
     * @return 工具失败原因；成功时返回 null。
     */
    static String structuredToolError(ToolResult toolResult, String observation) {
        if (toolResult != null && toolResult.isError()) {
            return StrUtil.blankToDefault(observation, "Tool execution failed");
        }
        if (StrUtil.isBlank(observation)) {
            return null;
        }
        try {
            ONode envelope = ONode.ofJson(observation);
            if (!envelope.isObject()
                    || !"error".equalsIgnoreCase(envelope.get("status").getString())) {
                return null;
            }
            String reason =
                    StrUtil.blankToDefault(
                            envelope.get("error").getString(), envelope.get("message").getString());
            return StrUtil.blankToDefault(
                    reason,
                    StrUtil.blankToDefault(
                            envelope.get("summary").getString(), "Tool execution failed"));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * 在任何审批、拦截器或 handler 执行前验证模型原始 arguments 必须是 JSON 对象。
     *
     * @param call 模型返回的工具调用。
     * @return 合法时返回 null；非法时返回可作为工具观察写回模型的错误文本。
     */
    private String validateToolCallArguments(ToolCall call) {
        String raw = call == null ? null : call.getArgumentsStr();
        if (StrUtil.isBlank(raw)) {
            return "Tool call arguments must be a non-empty JSON object";
        }
        try {
            ONode node = ONode.ofJson(raw);
            if (!node.isObject()) {
                return "Tool call arguments must be a JSON object";
            }
            return null;
        } catch (RuntimeException e) {
            return "Tool call arguments must be valid JSON object syntax";
        }
    }

    /**
     * 规范化模型漏传的工具参数。仅当用户原文明确写出布尔赋值时才补齐，避免根据脚本字段猜测任务模式。
     *
     * @param toolName 工具名。
     * @param args 模型生成的工具参数。
     * @param toolContext 本轮工具上下文。
     * @return 返回规范化后的工具参数。
     */
    private Map<String, Object> normalizeOwnedToolArguments(
            String toolName, Map<String, Object> args, Map<String, Object> toolContext) {
        if (!"cronjob".equals(toolName) || args == null || args.isEmpty()) {
            return args;
        }
        if (!isCronjobWriteAction(args.get("action"))) {
            return args;
        }
        Object userMessageValue = toolContext == null ? null : toolContext.get("user_message");
        String userMessage = userMessageValue == null ? "" : String.valueOf(userMessageValue);
        if (StrUtil.isBlank(userMessage)) {
            return args;
        }

        Map<String, Object> normalized = null;
        if (!args.containsKey("no_agent")
                && USER_NO_AGENT_TRUE_PATTERN.matcher(userMessage).find()) {
            normalized = normalizedArgs(args, normalized);
            normalized.put("no_agent", Boolean.TRUE);
        }
        if (!args.containsKey("wrap_response")
                && USER_WRAP_RESPONSE_FALSE_PATTERN.matcher(userMessage).find()) {
            normalized = normalizedArgs(args, normalized);
            normalized.put("wrap_response", Boolean.FALSE);
        }
        return normalized == null ? args : normalized;
    }

    /**
     * 判断 cronjob 动作是否会创建或编辑任务配置。
     *
     * @param action action 参数。
     * @return 如果是写配置动作则返回 true。
     */
    private boolean isCronjobWriteAction(Object action) {
        String normalized =
                action == null ? "list" : String.valueOf(action).trim().toLowerCase(Locale.ROOT);
        return "create".equals(normalized)
                || "add".equals(normalized)
                || "update".equals(normalized)
                || "edit".equals(normalized);
    }

    /**
     * 延迟复制工具参数，保证无补齐时仍沿用模型原始对象。
     *
     * @param args 原始参数。
     * @param normalized 已复制参数。
     * @return 返回可修改参数。
     */
    private Map<String, Object> normalizedArgs(
            Map<String, Object> args, Map<String, Object> normalized) {
        if (normalized != null) {
            return normalized;
        }
        return new LinkedHashMap<String, Object>(args);
    }

    /**
     * 追加自有Loop工具消息。
     *
     * @param trace trace参数。
     * @param observation 观察结果。
     * @param toolName 工具名。
     * @param call 工具调用。
     * @param returnDirect 是否直接返回。
     * @param failed 是否为已确认的工具失败。
     */
    private void appendOwnedToolMessage(
            OwnedReActTrace trace,
            String observation,
            String toolName,
            ToolCall call,
            boolean returnDirect,
            boolean failed) {
        ToolResult result =
                failed
                        ? ToolResult.error(StrUtil.nullToEmpty(observation))
                        : ToolResult.success(StrUtil.nullToEmpty(observation));
        ToolMessage message =
                ChatMessage.ofTool(
                        result,
                        toolName,
                        StrUtil.blankToDefault(call == null ? null : call.getId(), toolName),
                        returnDirect);
        ToolMessageStatusSupport.mark(message, failed);
        trace.getSession().addMessage(message);
    }

    /**
     * 构造自有Loop结果。
     *
     * @param session 会话参数。
     * @param agentSession Agent会话参数。
     * @param resolved resolved参数。
     * @param assistantMessage assistant消息。
     * @param rawResponse 原始响应。
     * @param usageCollector 用量Collector。
     * @param pending 是否挂起。
     * @param streamed 是否走过流式模型响应。
     * @param runContext 运行上下文。
     * @param reasoningText 流式解析出的推理文本。
     * @param finishReason 标准化后的模型结束原因。
     * @return 返回结果。
     */
    private LlmResult ownedLoopResult(
            SessionRecord session,
            SqliteAgentSession agentSession,
            AppConfig.LlmConfig resolved,
            AssistantMessage assistantMessage,
            String rawResponse,
            UsageCollector usageCollector,
            boolean pending,
            boolean streamed,
            AgentRunContext runContext,
            String reasoningText,
            String finishReason)
            throws Exception {
        restoreInjectedUserMessage(agentSession, runContext);
        LlmResult result = new LlmResult();
        result.setAssistantMessage(assistantMessage);
        result.setNdjson(ChatMessage.toNdjson(agentSession.getMessages()));
        result.setStreamed(streamed);
        result.setRawResponse(rawResponse);
        result.setFinishReason(finishReason);
        result.setReasoningText(
                StrUtil.blankToDefault(reasoningText, extractReasoning(assistantMessage)));
        result.setProvider(resolved.getProvider());
        result.setModel(StrUtil.blankToDefault(resolved.getModel(), ""));
        if (usageCollector != null) {
            usageCollector.applyTo(result);
        }
        if (pending) {
            agentSession.updateSnapshot();
        }
        logUsage(session, resolved, result);
        return result;
    }

    /**
     * 确保原生工具调用轮的 assistant tool_call 消息先进入会话历史。
     *
     * <p>部分流式协议只在聚合响应里返回 tool_calls，并不会把该 assistant 消息同步写入 AgentSession。若后续先追加 tool
     * 结果，消息修复会因为找不到匹配的 tool_call id 而把真实 工具结果当作游离 tool 消息删除，最大步数恢复阶段就会丢失已经执行的工具事实。
     *
     * @param agentSession 当前 Agent 会话。
     * @param assistantMessage 本轮模型返回的 assistant 消息。
     */
    private void ensureOwnedAssistantToolCallRecorded(
            SqliteAgentSession agentSession, AssistantMessage assistantMessage) {
        if (agentSession == null || assistantMessage == null) {
            return;
        }
        List<ToolCall> calls = assistantMessage.getToolCalls();
        if (calls == null || calls.isEmpty()) {
            return;
        }
        List<ChatMessage> messages = agentSession.getMessages();
        if (messages == null) {
            return;
        }
        int assistantIndex = lastAssistantMessageIndex(messages);
        if (assistantIndex >= 0
                && sameToolCallIds((AssistantMessage) messages.get(assistantIndex), calls)) {
            AssistantMessage existing = (AssistantMessage) messages.get(assistantIndex);
            int previousAssistantIndex = previousAssistantMessageIndex(messages, assistantIndex);
            if (previousAssistantIndex >= 0
                    && MessageSupport.sameVisibleContent(
                            (AssistantMessage) messages.get(previousAssistantIndex), existing)) {
                messages.remove(previousAssistantIndex);
                assistantIndex--;
            }
            messages.set(assistantIndex, assistantMessage);
            return;
        }
        if (assistantIndex >= 0 && messages.get(assistantIndex) instanceof AssistantMessage) {
            int previousAssistantIndex = previousAssistantMessageIndex(messages, assistantIndex);
            if (previousAssistantIndex >= 0
                    && MessageSupport.sameVisibleContent(
                            (AssistantMessage) messages.get(previousAssistantIndex),
                            assistantMessage)) {
                messages.remove(previousAssistantIndex);
                assistantIndex--;
            }
            messages.set(assistantIndex, assistantMessage);
        } else {
            messages.add(assistantMessage);
        }
    }

    /**
     * 查找指定位置之前最近的assistant消息索引。
     *
     * @param messages 消息列表。
     * @param beforeIndex 起始位置之前查找。
     * @return 返回最近assistant索引，未找到返回-1。
     */
    private int previousAssistantMessageIndex(List<ChatMessage> messages, int beforeIndex) {
        if (messages == null) {
            return -1;
        }
        for (int i = Math.min(beforeIndex - 1, messages.size() - 1); i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message instanceof AssistantMessage) {
                return i;
            }
            if (message instanceof ToolMessage) {
                return -1;
            }
            if (message != null
                    && "user"
                            .equalsIgnoreCase(
                                    StrUtil.nullToEmpty(String.valueOf(message.getRole())))) {
                return -1;
            }
        }
        return -1;
    }

    /**
     * 判断已有 assistant 消息是否已经携带同一批工具调用标识。
     *
     * @param assistant 已在会话中的 assistant 消息。
     * @param calls 当前待执行工具调用。
     * @return 如果工具调用标识完全一致则返回 true。
     */
    private boolean sameToolCallIds(AssistantMessage assistant, List<ToolCall> calls) {
        if (assistant == null || calls == null) {
            return false;
        }
        List<ToolCall> existing = assistant.getToolCalls();
        if (existing == null || existing.size() != calls.size()) {
            return false;
        }
        for (int i = 0; i < calls.size(); i++) {
            String left = calls.get(i) == null ? "" : StrUtil.nullToEmpty(calls.get(i).getId());
            String right =
                    existing.get(i) == null ? "" : StrUtil.nullToEmpty(existing.get(i).getId());
            if (!StrUtil.equals(left, right)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 构造本轮发给模型的用户文本，允许追加仅请求期可见的记忆召回上下文。
     *
     * @param userMessage 用户真实输入。
     * @param runContext 运行上下文。
     * @return 返回模型请求使用的用户文本。
     */
    private String userPromptText(String userMessage, AgentRunContext runContext) {
        if (runContext == null) {
            return userMessage;
        }
        return MemoryContextBoundary.appendPrefetchedContext(
                userMessage,
                runContext.getMemoryPrefetchUserMessage(),
                runContext.getMemoryPrefetchContext());
    }

    /**
     * 将模型请求期注入了记忆的用户消息恢复为原始输入，保证会话历史不保存内部上下文。
     *
     * @param agentSession 当前 SQLite 会话适配器。
     * @param runContext 运行上下文。
     */
    private void restoreInjectedUserMessage(
            SqliteAgentSession agentSession, AgentRunContext runContext) {
        if (runContext == null || StrUtil.isBlank(runContext.getMemoryPrefetchContext())) {
            return;
        }
        String expected = userPromptText(runContext.getMemoryPrefetchUserMessage(), runContext);
        agentSession.replaceLastUserMessage(expected, runContext.getMemoryPrefetchUserMessage());
    }

    /**
     * 解析自有Loop最大步数。
     *
     * @param agentSession Agent会话参数。
     * @return 返回最大步数。
     */
    private int ownedLoopMaxSteps(SqliteAgentSession agentSession) {
        boolean delegateSession = isDelegateSession(agentSession);
        int maxSteps =
                delegateSession
                        ? appConfig.getReact().getDelegateMaxSteps()
                        : appConfig.getReact().getMaxSteps();
        return Math.max(1, maxSteps);
    }

    /**
     * 判断trace是否已结束。
     *
     * @param trace trace参数。
     * @return 如果结束返回true。
     */
    private boolean isTraceEnded(ReActTrace trace) {
        return trace != null
                && ("end".equalsIgnoreCase(StrUtil.nullToEmpty(trace.getRoute()))
                        || StrUtil.isNotBlank(trace.getFinalAnswer()));
    }

    /**
     * 拉平成ReAct拦截器。
     *
     * @param options Chat选项。
     * @return 返回拦截器列表。
     */
    private List<ReActInterceptor> flatInterceptors(ChatOptions options) {
        List<ReActInterceptor> result = new ArrayList<ReActInterceptor>();
        if (options == null) {
            return result;
        }
        for (RankEntity<org.noear.solon.ai.chat.interceptor.ChatInterceptor> item :
                options.interceptors()) {
            if (item != null && item.target instanceof ReActInterceptor) {
                result.add((ReActInterceptor) item.target);
            }
        }
        return result;
    }

    /**
     * 给工具链构造带排序信息的ReAct拦截器集合。
     *
     * @param interceptors 拦截器列表。
     * @return 返回排序实体集合。
     */
    private List<RankEntity<ReActInterceptor>> rankedInterceptors(
            List<ReActInterceptor> interceptors) {
        List<RankEntity<ReActInterceptor>> result = new ArrayList<RankEntity<ReActInterceptor>>();
        if (interceptors == null) {
            return result;
        }
        for (ReActInterceptor interceptor : interceptors) {
            if (interceptor != null) {
                result.add(new RankEntity<ReActInterceptor>(interceptor, 0));
            }
        }
        return result;
    }

    /**
     * 发送思考。
     *
     * @param delta delta 参数。
     * @param emittedText emitted文本参数。
     * @param eventSink 事件Sink参数。
     * @param feedbackSink 反馈Sink参数。
     * @param memoryScrubber 记忆Scrubber参数。
     */
    private void emitThinking(
            ThinkingStreamSplitter.Delta delta,
            StringBuilder emittedText,
            StringBuilder bufferedVisibleText,
            ConversationEventSink eventSink,
            ConversationFeedbackSink feedbackSink,
            MemoryContextBoundary.StreamingScrubber memoryScrubber) {
        if (delta == null) {
            return;
        }
        if (StrUtil.isNotBlank(delta.reasoning)) {
            eventSink.onReasoningDelta(delta.reasoning);
            if (feedbackSink != null) {
                feedbackSink.onReasoning(delta.reasoning);
            }
        }
        if (StrUtil.isNotBlank(delta.visible)) {
            String visible = memoryScrubber.feed(delta.visible);
            if (StrUtil.isNotBlank(visible)) {
                // 先缓存，等聚合消息确认没有工具调用后再发送，避免工具调用前缀污染最终消息流。
                bufferedVisibleText.append(visible);
            }
        }
    }

    /**
     * 构建Candidate Configs。
     *
     * @param session 会话参数。
     * @return 返回创建好的Candidate Configs。
     */
    private List<AppConfig.LlmConfig> buildCandidateConfigs(SessionRecord session) {
        List<AppConfig.LlmConfig> candidates = new java.util.ArrayList<AppConfig.LlmConfig>();
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<String>();

        if (appConfig.getProviders() == null
                || appConfig.getProviders().isEmpty()
                || StrUtil.isBlank(appConfig.getModel().getProviderKey())) {
            candidates.add(copyLlmConfig(appConfig.getLlm()));
            return candidates;
        }

        AppConfig.LlmConfig primary =
                toLlmConfig(llmProviderService.resolveEffectiveProvider(session));
        candidates.add(primary);
        seen.add(providerSignature(primary));

        for (LlmProviderService.ResolvedProvider fallback :
                llmProviderService.resolveFallbackProviders()) {
            AppConfig.LlmConfig candidate = toLlmConfig(fallback);
            String signature = providerSignature(candidate);
            if (seen.add(signature)) {
                candidates.add(candidate);
            }
        }

        return candidates;
    }

    /**
     * 转换为大模型配置。
     *
     * @param resolved resolved 参数。
     * @return 返回转换后的大模型配置。
     */
    private AppConfig.LlmConfig toLlmConfig(LlmProviderService.ResolvedProvider resolved) {
        AppConfig.LlmConfig config = copyLlmConfig(appConfig.getLlm());
        config.setProvider(StrUtil.nullToEmpty(resolved.getProviderKey()).trim());
        config.setDialect(StrUtil.nullToEmpty(resolved.getDialect()).trim());
        config.setApiUrl(StrUtil.nullToEmpty(resolved.getApiUrl()).trim());
        config.setApiKey(resolved.getApiKey());
        config.setModel(StrUtil.nullToEmpty(resolved.getModel()).trim());
        config.setContextWindowTokens(resolved.getContextWindowTokens());
        return config;
    }

    /**
     * 判断是否存在Visible Content。
     *
     * @param assistantMessage assistant消息参数。
     * @param rawResponse 原始响应响应或执行结果。
     * @return 如果Visible Content满足条件则返回 true，否则返回 false。
     */
    private boolean hasVisibleContent(AssistantMessage assistantMessage, String rawResponse) {
        return StrUtil.isNotBlank(extractText(assistantMessage))
                || StrUtil.isNotBlank(
                        suppressUnrecognizedToolXml(MessageSupport.visibleText(rawResponse)));
    }

    /**
     * 提取Text。
     *
     * @param assistantMessage assistant消息参数。
     * @return 返回Text结果。
     */
    private String extractText(AssistantMessage assistantMessage) {
        String text = suppressUnrecognizedToolXml(MessageSupport.assistantText(assistantMessage));
        if (StrUtil.isNotBlank(text)) {
            return text;
        }
        if (assistantMessage == null) {
            return "";
        }
        log.warn(
                "Assistant message has no visible content; suppressing message object fallback: role={}, contentRawType={}, toolCalls={}",
                assistantMessage.getRole(),
                assistantMessage.getContentRaw() == null
                        ? ""
                        : assistantMessage.getContentRaw().getClass().getName(),
                assistantMessage.getToolCalls() == null
                        ? 0
                        : assistantMessage.getToolCalls().size());
        return "";
    }

    /** 清理完成答复中的未识别工具 XML，保留标签外的正常文本。 */
    private String suppressUnrecognizedToolXml(String value) {
        if (StrUtil.isBlank(value)) {
            return "";
        }
        String sanitized = UNRECOGNIZED_TOOL_XML_BLOCK_PATTERN.matcher(value).replaceAll("");
        return UNRECOGNIZED_TOOL_XML_TAG_PATTERN.matcher(sanitized).replaceAll("").trim();
    }

    /** 对最终可见文本统一执行思考、记忆边界和未识别工具 XML 清理。 */
    private String visibleResponseText(String value) {
        return suppressUnrecognizedToolXml(
                MemoryContextBoundary.scrubVisibleText(ThinkingStreamSplitter.visibleText(value)));
    }

    /**
     * 将工具调用轮的可见短文本收敛为安全阶段说明。
     *
     * @param value 模型在工具调用轮返回的可见文本。
     * @return 可发送的单行阶段说明；疑似思维链或内部提示词时返回空串。
     */
    private String sanitizeProgressUpdate(String value) {
        return ProgressUpdateSanitizer.sanitizeDeclared(visibleResponseText(value));
    }

    /**
     * 复制工具调用 assistant，并只保留实际发送给用户的安全阶段说明正文。
     *
     * @param source 原始工具调用 assistant。
     * @param progressText 已通过过滤和限流的阶段说明；未发送时为空。
     * @return 可安全进入模型历史和持久化会话的 assistant。
     */
    private AssistantMessage copyAssistantWithProgressText(
            AssistantMessage source, String progressText) {
        AssistantMessage replacement =
                new AssistantMessage(
                        StrUtil.nullToEmpty(progressText),
                        false,
                        null,
                        source == null ? null : source.getToolCallsRaw(),
                        source == null ? null : source.getToolCalls(),
                        source == null ? null : source.getSearchResultsRaw());
        if (source != null) {
            replacement.reasoningFieldName(source.getReasoningFieldName());
        }
        return replacement;
    }

    /** 从公开选择项或默认响应聚合状态中提取标准化结束原因。 */
    private String finishReason(ChatResponse response) {
        ChatChoice choice = response == null ? null : response.lastChoice();
        String value = choice == null ? "" : choice.getFinishReason();
        if (StrUtil.isBlank(value) && response instanceof ChatResponseDefault) {
            value = ((ChatResponseDefault) response).getLastFinishReasonNormalized();
        }
        return StrUtil.nullToEmpty(ChatResponseDefault.normalizeFinishReason(value))
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    /** 记录被截断或内容过滤的完成状态。 */
    private void logIncompleteFinishReason(ChatResponse response) {
        String finishReason = finishReason(response);
        if ("length".equals(finishReason) || "content_filter".equals(finishReason)) {
            log.warn(
                    "LLM response ended with incomplete finish reason: finishReason={}",
                    finishReason);
        }
    }

    /**
     * 提取Reasoning。
     *
     * @param assistantMessage assistant消息参数。
     * @return 返回Reasoning结果。
     */
    private String extractReasoning(AssistantMessage assistantMessage) {
        if (assistantMessage == null) {
            return "";
        }
        return StrUtil.nullToEmpty(assistantMessage.getReasoning()).trim();
    }

    /** 将流式思考标签内容拆成推理和可见答复，未闭合标签后的内容不得外泄。 */
    private static class ThinkingStreamSplitter {
        /** 模型可能内嵌在正文中的思考开始标签，匹配时忽略大小写。 */
        private static final String[] THINK_OPEN_TAGS = {
            "<think>", "<thinking>", "<reasoning>", "<thought>", "<reasoning_scratchpad>"
        };

        /** 与思考开始标签对应的结束标签，匹配时忽略大小写。 */
        private static final String[] THINK_CLOSE_TAGS = {
            "</think>", "</thinking>", "</reasoning>", "</thought>", "</reasoning_scratchpad>"
        };

        /** 记录思考流Splitter中的visible。 */
        private final StringBuilder visible = new StringBuilder();

        /** 记录思考流Splitter中的推理。 */
        private final StringBuilder reasoning = new StringBuilder();

        /** 记录思考流Splitter中的待恢复Tag。 */
        private final StringBuilder pendingTag = new StringBuilder();

        /** 是否启用思考。 */
        private boolean thinking;

        /**
         * 执行accept相关逻辑。
         *
         * @param text 待处理文本。
         * @param thinkingOnly 思考Only参数。
         * @return 返回accept结果。
         */
        Delta accept(String text, boolean thinkingOnly) {
            if (StrUtil.isBlank(text)) {
                return Delta.empty();
            }
            if (thinkingOnly) {
                StringBuilder reasoningDelta = new StringBuilder();
                appendThinkingOnly(text, reasoningDelta);
                return buildDelta(new StringBuilder(), reasoningDelta);
            }

            StringBuilder visibleDelta = new StringBuilder();
            StringBuilder reasoningDelta = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (pendingTag.length() > 0 || ch == '<') {
                    pendingTag.append(ch);
                    String pending = pendingTag.toString();
                    if (matchesTag(pending, THINK_OPEN_TAGS)) {
                        thinking = true;
                        pendingTag.setLength(0);
                        continue;
                    }
                    if (matchesTag(pending, THINK_CLOSE_TAGS)) {
                        thinking = false;
                        pendingTag.setLength(0);
                        continue;
                    }
                    if (isTagPrefix(pending, THINK_OPEN_TAGS)
                            || isTagPrefix(pending, THINK_CLOSE_TAGS)) {
                        continue;
                    }
                    appendCurrent(pending, visibleDelta, reasoningDelta);
                    pendingTag.setLength(0);
                    continue;
                }
                appendCurrent(String.valueOf(ch), visibleDelta, reasoningDelta);
            }
            return buildDelta(visibleDelta, reasoningDelta);
        }

        /**
         * 执行flush待恢复相关逻辑。
         *
         * @return 返回flush Pending结果。
         */
        Delta flushPending() {
            if (pendingTag.length() == 0) {
                return Delta.empty();
            }
            StringBuilder visibleDelta = new StringBuilder();
            StringBuilder reasoningDelta = new StringBuilder();
            appendCurrent(pendingTag.toString(), visibleDelta, reasoningDelta);
            pendingTag.setLength(0);
            return buildDelta(visibleDelta, reasoningDelta);
        }

        /**
         * 执行推理文本相关逻辑。
         *
         * @return 返回reasoning Text结果。
         */
        String reasoningText() {
            return reasoning.toString().trim();
        }

        /**
         * 提取模型聚合文本中的可见回复，避免流式结束时把 <think> 块补发到前端。
         *
         * @param text 模型聚合出的完整文本。
         * @return 返回剥离思考块后的用户可见文本。
         */
        static String visibleText(String text) {
            if (StrUtil.isBlank(text)) {
                return "";
            }
            ThinkingStreamSplitter splitter = new ThinkingStreamSplitter();
            Delta accepted = splitter.accept(text, false);
            Delta flushed = splitter.flushPending();
            return (StrUtil.nullToEmpty(accepted.visible) + StrUtil.nullToEmpty(flushed.visible))
                    .trim();
        }

        /**
         * 构建Delta。
         *
         * @param visibleDelta visibleDelta 参数。
         * @param reasoningDelta 推理Delta参数。
         * @return 返回创建好的Delta。
         */
        private Delta buildDelta(StringBuilder visibleDelta, StringBuilder reasoningDelta) {
            if (visibleDelta.length() > 0) {
                visible.append(visibleDelta);
            }
            if (reasoningDelta.length() > 0) {
                reasoning.append(reasoningDelta);
            }
            return new Delta(visibleDelta.toString(), reasoningDelta.toString());
        }

        /**
         * 追加当前。
         *
         * @param value 待规范化或校验的原始值。
         * @param visibleDelta visibleDelta 参数。
         * @param reasoningDelta 推理Delta参数。
         */
        private void appendCurrent(
                String value, StringBuilder visibleDelta, StringBuilder reasoningDelta) {
            if (thinking) {
                reasoningDelta.append(value);
            } else {
                visibleDelta.append(value);
            }
        }

        /**
         * 处理模型明确标记为思考态的分片；分片中若仍带 <think> 标签，只保留真实推理文本。
         *
         * @param text 思考态分片文本。
         * @param reasoningDelta 本次新增的推理文本。
         */
        private void appendThinkingOnly(String text, StringBuilder reasoningDelta) {
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (pendingTag.length() > 0 || ch == '<') {
                    pendingTag.append(ch);
                    String pending = pendingTag.toString();
                    if (matchesTag(pending, THINK_OPEN_TAGS)
                            || matchesTag(pending, THINK_CLOSE_TAGS)) {
                        pendingTag.setLength(0);
                        continue;
                    }
                    if (isTagPrefix(pending, THINK_OPEN_TAGS)
                            || isTagPrefix(pending, THINK_CLOSE_TAGS)) {
                        continue;
                    }
                    reasoningDelta.append(pending);
                    pendingTag.setLength(0);
                    continue;
                }
                reasoningDelta.append(ch);
            }
        }

        /** 判断当前缓存是否等于任一思考标签，兼容模型返回的大小写差异。 */
        private static boolean matchesTag(String value, String[] tags) {
            for (String tag : tags) {
                if (tag.equalsIgnoreCase(value)) {
                    return true;
                }
            }
            return false;
        }

        /** 判断当前缓存是否仍可能组成任一思考标签。 */
        private static boolean isTagPrefix(String value, String[] tags) {
            for (String tag : tags) {
                if (tag.regionMatches(true, 0, value, 0, value.length())) {
                    return true;
                }
            }
            return false;
        }

        /** 承载Delta相关状态和辅助逻辑。 */
        private static class Delta {
            /** EMPTY的统一常量值。 */
            private static final Delta EMPTY = new Delta("", "");

            /** 记录Delta中的visible。 */
            private final String visible;

            /** 记录Delta中的推理。 */
            private final String reasoning;

            /**
             * 创建Delta实例，并注入运行所需依赖。
             *
             * @param visible visible 参数。
             * @param reasoning 推理参数。
             */
            private Delta(String visible, String reasoning) {
                this.visible = visible;
                this.reasoning = reasoning;
            }

            /**
             * 返回当前类型的空结果。
             *
             * @return 返回empty结果。
             */
            private static Delta empty() {
                return EMPTY;
            }
        }
    }

    /** 校验 provider 与 URL 配置，避免隐式降级到错误协议。 */
    private void validate(AppConfig.LlmConfig resolved) {
        if (StrUtil.isBlank(resolved.getProvider())) {
            throw new IllegalStateException("LLM provider 不能为空。");
        }
        String dialect =
                LlmProviderSupport.normalizeDialect(
                        StrUtil.isNotBlank(resolved.getDialect())
                                ? resolved.getDialect()
                                : resolved.getProvider());
        if (!LlmConstants.SUPPORTED_PROVIDERS.contains(dialect)) {
            throw new IllegalStateException("不支持的 provider dialect：" + dialect);
        }
        if (StrUtil.isBlank(resolved.getApiUrl())) {
            throw new IllegalStateException("LLM apiUrl 不能为空。");
        }
        if (LlmConstants.PROVIDER_OPENAI_RESPONSES.equals(dialect)
                && !StrUtil.containsIgnoreCase(resolved.getApiUrl(), "/responses")) {
            throw new IllegalStateException("openai-responses 的 apiUrl 必须直接指向 /responses 接口。");
        }
        if (!LlmConstants.PROVIDER_OLLAMA.equals(dialect)
                && SecretValueGuard.isPlaceholderSecret(resolved.getApiKey())) {
            throw new IllegalStateException("LLM apiKey 不能使用示例或占位符密钥。");
        }
        // LLM API URL 由管理员在配置中显式指定，视为可信目标；检查安全策略但不要求外部 URL 审批。
        SecurityPolicyService.UrlVerdict apiUrlVerdict =
                securityPolicyService.checkUrlSafety(
                        resolved.getApiUrl(),
                        LlmConstants.PROVIDER_OLLAMA.equals(dialect) ? Boolean.TRUE : null);
        if (!apiUrlVerdict.isAllowed()) {
            throw new IllegalStateException("LLM apiUrl 被安全策略阻断：" + apiUrlVerdict.getMessage());
        }
        if (StrUtil.isBlank(resolved.getModel())) {
            throw new IllegalStateException("LLM model 不能为空。");
        }
    }

    /**
     * 执行用户提示词相关逻辑。
     *
     * @param userMessage 用户消息参数。
     * @param runContext 运行上下文。
     * @param resolved resolved 参数。
     * @return 返回用户提示词结果。
     */
    private Prompt userPrompt(
            String userMessage, AgentRunContext runContext, AppConfig.LlmConfig resolved) {
        List<ContentBlock> blocks = userContentBlocks(userMessage, runContext, resolved);
        if (blocks.isEmpty()) {
            return Prompt.of(userMessage);
        }
        if (StrUtil.isBlank(userMessage)) {
            return Prompt.of(ChatMessage.ofUser(new Contents().addBlocks(blocks)));
        }
        return Prompt.of(ChatMessage.ofUser(StrUtil.nullToEmpty(userMessage), blocks));
    }

    /**
     * 执行用户Content块s相关逻辑。
     *
     * @param userMessage 用户消息参数。
     * @param runContext 运行上下文。
     * @param resolved resolved 参数。
     * @return 返回用户Content 块s结果。
     */
    private List<ContentBlock> userContentBlocks(
            String userMessage, AgentRunContext runContext, AppConfig.LlmConfig resolved) {
        List<MessageAttachment> attachments =
                runContext == null
                        ? Collections.<MessageAttachment>emptyList()
                        : runContext.getUserAttachments();
        if (attachments.isEmpty()) {
            return Collections.emptyList();
        }
        boolean providerSupportsVision = supportsVisionPayload(resolved);
        if (!providerSupportsVision) {
            return Collections.emptyList();
        }
        List<ContentBlock> blocks = new ArrayList<ContentBlock>();
        for (MessageAttachment attachment : attachments) {
            if (blocks.size() >= mediaInputBoundaryService.maxImageAttachments()) {
                continue;
            }
            if (!MessageAttachmentSupport.canSendAsVisionPayload(
                    attachment, providerSupportsVision)) {
                continue;
            }
            ImageBlock image = mediaInputBoundaryService.toImageBlock(attachment);
            if (image != null) {
                blocks.add(image);
            }
        }
        return blocks;
    }

    /**
     * 构建Chat配置。
     *
     * @param resolved resolved 参数。
     * @return 返回创建好的Chat配置。
     */
    private ChatConfig buildChatConfig(AppConfig.LlmConfig resolved) {
        return buildChatConfig(resolved, null);
    }

    /**
     * 判断是否支持Vision载荷。
     *
     * @param resolved resolved 参数。
     * @return 返回supports Vision Payload结果。
     */
    private boolean supportsVisionPayload(AppConfig.LlmConfig resolved) {
        if (resolved == null) {
            return false;
        }
        AppConfig.ProviderConfig provider = appConfig.getProviders().get(resolved.getProvider());
        AppConfig.ProviderConfig effective = new AppConfig.ProviderConfig();
        if (provider != null) {
            effective.setName(provider.getName());
            effective.setBaseUrl(provider.getBaseUrl());
            effective.setApiKey(provider.getApiKey());
            effective.setSupportsVision(provider.getSupportsVision());
        }
        effective.setDialect(resolved.getDialect());
        effective.setDefaultModel(resolved.getModel());
        return modelMetadataService.resolve(resolved.getProvider(), effective).isSupportsVision();
    }

    /**
     * 构建Chat配置。
     *
     * @param resolved resolved 参数。
     * @param session 会话参数。
     * @return 返回创建好的Chat配置。
     */
    private ChatConfig buildChatConfig(AppConfig.LlmConfig resolved, SessionRecord session) {
        ensureCustomDialectsRegistered();
        String dialect =
                LlmProviderSupport.normalizeDialect(
                        StrUtil.isNotBlank(resolved.getDialect())
                                ? resolved.getDialect()
                                : resolved.getProvider());

        ChatConfig chatConfig = new ChatConfig();
        chatConfig.setApiUrl(resolved.getApiUrl());
        chatConfig.setProvider(dialect);
        chatConfig.setModel(resolved.getModel());
        chatConfig.setTimeout(Duration.ofMinutes(5));

        if (StrUtil.isNotBlank(resolved.getApiKey())) {
            chatConfig.setApiKey(resolved.getApiKey());
        }

        String reasoningEffort =
                session != null && StrUtil.isNotBlank(session.getReasoningEffortOverride())
                        ? session.getReasoningEffortOverride().trim()
                        : resolved.getReasoningEffort();
        applyProviderRequestOptions(chatConfig, resolved, dialect, reasoningEffort);
        applyFastMode(chatConfig, resolved, dialect, session);
        applyPromptCache(chatConfig, resolved, dialect, session);

        return chatConfig;
    }

    /** 将通用模型配置转换为各协议实际接受的请求字段。 */
    private void applyProviderRequestOptions(
            ChatConfig chatConfig,
            AppConfig.LlmConfig resolved,
            String dialect,
            String reasoningEffort) {
        String effort = StrUtil.nullToEmpty(reasoningEffort).trim().toLowerCase(Locale.ROOT);
        boolean reasoningEnabled = StrUtil.isNotBlank(effort) && !"none".equals(effort);

        if (LlmConstants.PROVIDER_GEMINI.equals(dialect)) {
            Map<String, Object> generationConfig = new LinkedHashMap<String, Object>();
            generationConfig.put("temperature", resolved.getTemperature());
            generationConfig.put(
                    "maxOutputTokens",
                    resolved.getMaxTokens() > 0
                            ? resolved.getMaxTokens()
                            : RuntimePathConstants.DEFAULT_MAX_TOKENS);
            if (StrUtil.isNotBlank(effort)) {
                Map<String, Object> thinkingConfig = new LinkedHashMap<String, Object>();
                thinkingConfig.put("includeThoughts", reasoningEnabled);
                if (reasoningEnabled
                        && !resolved.getModel()
                                .toLowerCase(Locale.ROOT)
                                .startsWith("gemini-2.5-")) {
                    if ("minimal".equals(effort) || "low".equals(effort)) {
                        thinkingConfig.put("thinkingLevel", "LOW");
                    } else if ("high".equals(effort) || "xhigh".equals(effort)) {
                        thinkingConfig.put("thinkingLevel", "HIGH");
                    }
                }
                generationConfig.put("thinkingConfig", thinkingConfig);
            }
            // Solon AI 4.0.3 的 Gemini Map 转换会丢失嵌套 thinkingConfig；结构化节点可由官方方言原样输出。
            chatConfig
                    .getModelOptions()
                    .optionSet("generationConfig", ONode.ofBean(generationConfig));
            return;
        }

        if (LlmConstants.PROVIDER_OLLAMA.equals(dialect)) {
            Map<String, Object> options = new LinkedHashMap<String, Object>();
            options.put("temperature", resolved.getTemperature());
            if (resolved.getMaxTokens() > 0) {
                options.put("num_predict", resolved.getMaxTokens());
            }
            chatConfig.getModelOptions().optionSet("options", options);
            if (StrUtil.isNotBlank(effort)) {
                chatConfig.getModelOptions().optionSet("think", reasoningEnabled);
            }
            return;
        }

        if (resolved.getMaxTokens() > 0) {
            chatConfig.getModelOptions().max_tokens(resolved.getMaxTokens());
        }
        if (!reasoningEnabled
                || (!LlmConstants.PROVIDER_ANTHROPIC.equals(dialect)
                        && !supportsOpenAiReasoning(resolved.getModel()))) {
            chatConfig.getModelOptions().temperature(resolved.getTemperature());
        }

        if (LlmConstants.PROVIDER_OPENAI_RESPONSES.equals(dialect) && reasoningEnabled) {
            Map<String, Object> reasoning = new LinkedHashMap<String, Object>();
            reasoning.put("effort", effort);
            reasoning.put("summary", "auto");
            chatConfig.getModelOptions().optionSet("reasoning", reasoning);
        } else if (LlmConstants.PROVIDER_OPENAI.equals(dialect)
                && reasoningEnabled
                && supportsOpenAiReasoning(resolved.getModel())) {
            chatConfig.getModelOptions().optionSet("reasoning_effort", effort);
        } else if (LlmConstants.PROVIDER_ANTHROPIC.equals(dialect) && reasoningEnabled) {
            applyAnthropicReasoning(chatConfig, resolved, effort);
        }
    }

    /** 将推理强度转换为 Anthropic 自适应或预算式 thinking 参数。 */
    private void applyAnthropicReasoning(
            ChatConfig chatConfig, AppConfig.LlmConfig resolved, String effort) {
        if (usesAdaptiveAnthropicThinking(resolved.getModel())) {
            Map<String, Object> thinking = new LinkedHashMap<String, Object>();
            thinking.put("type", "adaptive");
            chatConfig.getModelOptions().optionSet("thinking", thinking);
            Map<String, Object> outputConfig = new LinkedHashMap<String, Object>();
            outputConfig.put("effort", normalizeAnthropicEffort(resolved.getModel(), effort));
            chatConfig.getModelOptions().optionSet("output_config", outputConfig);
            return;
        }

        int maxTokens = resolved.getMaxTokens();
        if (maxTokens <= 1024) {
            return;
        }
        int requestedBudget;
        if ("minimal".equals(effort)) {
            requestedBudget = 1024;
        } else if ("low".equals(effort)) {
            requestedBudget = 4000;
        } else if ("high".equals(effort)) {
            requestedBudget = 16000;
        } else if ("xhigh".equals(effort)) {
            requestedBudget = 32000;
        } else {
            requestedBudget = 8000;
        }
        Map<String, Object> thinking = new LinkedHashMap<String, Object>();
        thinking.put("enabled", true);
        thinking.put("budget_tokens", Math.min(requestedBudget, maxTokens - 1024));
        chatConfig.getModelOptions().optionSet("thinking", thinking);
    }

    /** 判断 Claude 模型是否使用 4.6 之后的自适应 thinking 合约。 */
    private boolean usesAdaptiveAnthropicThinking(String model) {
        String normalized = StrUtil.nullToEmpty(model).toLowerCase(Locale.ROOT).replace('.', '-');
        if (!normalized.contains("claude")) {
            return false;
        }
        return !(normalized.contains("claude-3")
                || normalized.contains("opus-4-0")
                || normalized.contains("opus-4-1")
                || normalized.contains("opus-4-5")
                || normalized.contains("sonnet-4-0")
                || normalized.contains("sonnet-4-5")
                || normalized.contains("haiku-4-5"));
    }

    /** 规范化 Anthropic 自适应 thinking 的 effort 值。 */
    private String normalizeAnthropicEffort(String model, String effort) {
        if ("minimal".equals(effort)) {
            return "low";
        }
        String normalizedModel = StrUtil.nullToEmpty(model).toLowerCase(Locale.ROOT);
        if ("xhigh".equals(effort)
                && (normalizedModel.contains("4-6") || normalizedModel.contains("4.6"))) {
            return "max";
        }
        return effort;
    }

    /** 判断 OpenAI 模型是否接受 reasoning effort。 */
    private boolean supportsOpenAiReasoning(String model) {
        String normalized = StrUtil.nullToEmpty(model).toLowerCase(Locale.ROOT);
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) {
            normalized = normalized.substring(slash + 1);
        }
        return normalized.startsWith("gpt-5")
                || normalized.startsWith("o1")
                || normalized.startsWith("o3")
                || normalized.startsWith("o4");
    }

    /** 将会话 /fast 状态转换为提供方原生快速模式参数。 */
    private void applyFastMode(
            ChatConfig chatConfig,
            AppConfig.LlmConfig resolved,
            String dialect,
            SessionRecord session) {
        if (session == null
                || !"priority"
                        .equalsIgnoreCase(
                                StrUtil.nullToEmpty(session.getServiceTierOverride()).trim())) {
            return;
        }
        if ((LlmConstants.PROVIDER_OPENAI.equals(dialect)
                        || LlmConstants.PROVIDER_OPENAI_RESPONSES.equals(dialect))
                && LlmProviderSupport.isDirectOpenAiBaseUrl(resolved.getApiUrl())
                && supportsOpenAiReasoning(resolved.getModel())
                && !StrUtil.nullToEmpty(resolved.getModel())
                        .toLowerCase(Locale.ROOT)
                        .contains("codex")) {
            chatConfig.getModelOptions().optionSet("service_tier", "priority");
            return;
        }
        if (LlmConstants.PROVIDER_ANTHROPIC.equals(dialect)
                && LlmProviderSupport.baseUrlHostMatches(resolved.getApiUrl(), "api.anthropic.com")
                && isAnthropicFastModel(resolved.getModel())) {
            chatConfig.getModelOptions().optionSet("speed", "fast");
            chatConfig.setHeader("anthropic-beta", "fast-mode-2026-02-01");
        }
    }

    /** 判断模型是否支持 Anthropic Fast Mode。 */
    private boolean isAnthropicFastModel(String model) {
        String normalized = StrUtil.nullToEmpty(model).toLowerCase(Locale.ROOT);
        return normalized.contains("opus-4-6") || normalized.contains("opus-4.6");
    }

    /** 将提示词缓存配置接入 Solon AI 官方 CacheControl 与协议补充策略。 */
    private void applyPromptCache(
            ChatConfig chatConfig,
            AppConfig.LlmConfig resolved,
            String dialect,
            SessionRecord session) {
        PromptCachePolicy policy = new PromptCachePolicy(resolved.getPromptCache());
        if (!policy.isEnabled()) {
            return;
        }
        if (LlmConstants.PROVIDER_ANTHROPIC.equals(dialect)) {
            chatConfig.setCacheControl(CacheControl.ofEphemeral());
            chatConfig.getModelOptions().toolContextPut(PromptCachePolicy.TOOL_CONTEXT_KEY, policy);
            return;
        }

        String seed =
                StrUtil.nullToEmpty(resolved.getProvider())
                        + '|'
                        + StrUtil.nullToEmpty(resolved.getModel())
                        + '|'
                        + (session == null ? "" : StrUtil.nullToEmpty(session.getSessionId()));
        String cacheKey = "solonclaw-" + SecureUtil.sha256(seed).substring(0, 32);
        if (LlmConstants.PROVIDER_OPENAI.equals(dialect)) {
            chatConfig.setCacheControl(CacheControl.ofPromptKey(cacheKey));
        } else if (LlmConstants.PROVIDER_OPENAI_RESPONSES.equals(dialect)) {
            chatConfig.getModelOptions().optionSet("prompt_cache_key", cacheKey);
        }
    }

    /**
     * 构建Chat模型。
     *
     * @param resolved resolved 参数。
     * @return 返回创建好的Chat模型。
     */
    protected ChatModel buildChatModel(AppConfig.LlmConfig resolved) {
        return buildChatModel(resolved, null);
    }

    /**
     * 使用当前会话覆盖构建 Chat 模型，确保 /reasoning、/fast 与缓存键进入真实请求。
     *
     * @param resolved 已解析的大模型配置。
     * @param session 当前会话。
     * @return 带会话请求选项的 Chat 模型。
     */
    protected ChatModel buildChatModel(AppConfig.LlmConfig resolved, SessionRecord session) {
        return buildChatConfig(resolved, session).toChatModel();
    }

    /**
     * 执行提供方签名相关逻辑。
     *
     * @param config 当前模块使用的配置对象。
     * @return 返回提供方签名结果。
     */
    private String providerSignature(AppConfig.LlmConfig config) {
        return StrUtil.nullToEmpty(config.getProvider())
                + "|"
                + StrUtil.nullToEmpty(config.getDialect())
                + "|"
                + StrUtil.nullToEmpty(config.getApiUrl())
                + "|"
                + StrUtil.nullToEmpty(config.getModel())
                + "|"
                + (StrUtil.isBlank(config.getApiKey()) ? "no-key" : "has-key");
    }

    /** 确保Custom Dialects Registered。 */
    private void ensureCustomDialectsRegistered() {
        if (CUSTOM_DIALECTS_REGISTERED.compareAndSet(false, true)) {
            ChatDialectManager.register(
                    new RawResponseLoggingChatDialect(
                            OpenaiResponsesDialect.getInstance(),
                            LlmConstants.PROVIDER_OPENAI_RESPONSES,
                            true),
                    -100);
            ChatDialectManager.register(
                    new RawResponseLoggingChatDialect(
                            OpenaiChatDialect.getInstance(), LlmConstants.PROVIDER_OPENAI, false),
                    -99);
            ChatDialectManager.register(
                    new RawResponseLoggingChatDialect(
                            OllamaChatDialect.getInstance(), LlmConstants.PROVIDER_OLLAMA, false),
                    -98);
            ChatDialectManager.register(
                    new RawResponseLoggingChatDialect(
                            GeminiChatDialect.getInstance(), LlmConstants.PROVIDER_GEMINI, false),
                    -97);
            ChatDialectManager.register(
                    new RawResponseLoggingChatDialect(
                            AnthropicChatDialect.getInstance(),
                            LlmConstants.PROVIDER_ANTHROPIC,
                            false),
                    -96);
        }
    }

    /**
     * 清理工具Object。
     *
     * @param toolObject 工具Object参数。
     * @return 返回工具Object结果。
     */
    private Object sanitizeToolObject(Object toolObject) {
        if (toolObject instanceof FunctionTool) {
            return SanitizedFunctionTool.wrap((FunctionTool) toolObject);
        }
        if (toolObject instanceof ToolProvider) {
            final ToolProvider provider = (ToolProvider) toolObject;
            return new ToolProvider() {
                /**
                 * 读取工具。
                 *
                 * @return 返回读取到的工具。
                 */
                @Override
                public java.util.Collection<FunctionTool> getTools() {
                    java.util.List<FunctionTool> result = new java.util.ArrayList<FunctionTool>();
                    java.util.Collection<FunctionTool> tools = provider.getTools();
                    if (tools != null) {
                        for (FunctionTool tool : tools) {
                            result.add(SanitizedFunctionTool.wrap(tool));
                        }
                    }
                    return result;
                }
            };
        }
        if (toolObject instanceof Talent) {
            final Talent talent = (Talent) toolObject;
            return new Talent() {
                /**
                 * 执行名称相关逻辑。
                 *
                 * @return 返回名称结果。
                 */
                @Override
                public String name() {
                    return talent.name();
                }

                /**
                 * 执行description相关逻辑。
                 *
                 * @return 返回description结果。
                 */
                @Override
                public String description() {
                    return talent.description();
                }

                /**
                 * 执行元数据相关逻辑。
                 *
                 * @return 返回元数据结果。
                 */
                @Override
                public org.noear.solon.ai.chat.talent.TalentMetadata metadata() {
                    return talent.metadata();
                }

                /**
                 * 判断是否Supported。
                 *
                 * @param prompt 提示词参数。
                 * @return 如果Supported满足条件则返回 true，否则返回 false。
                 */
                @Override
                public boolean isSupported(Prompt prompt) {
                    return talent.isSupported(prompt);
                }

                /**
                 * 响应Attach事件。
                 *
                 * @param prompt 提示词参数。
                 */
                @Override
                public void onAttach(Prompt prompt) {
                    talent.onAttach(prompt);
                }

                /**
                 * 读取Instruction。
                 *
                 * @param prompt 提示词参数。
                 * @return 返回读取到的Instruction。
                 */
                @Override
                public String getInstruction(Prompt prompt) {
                    return talent.getInstruction(prompt);
                }

                /**
                 * 读取工具。
                 *
                 * @param prompt 提示词参数。
                 * @return 返回读取到的工具。
                 */
                @Override
                public java.util.Collection<FunctionTool> getTools(Prompt prompt) {
                    java.util.List<FunctionTool> result = new java.util.ArrayList<FunctionTool>();
                    java.util.Collection<FunctionTool> tools = talent.getTools(prompt);
                    if (tools != null) {
                        for (FunctionTool tool : tools) {
                            result.add(SanitizedFunctionTool.wrap(tool));
                        }
                    }
                    return result;
                }
            };
        }
        return toolObject;
    }

    /**
     * 解析工作区。
     *
     * @param runContext 运行上下文。
     * @return 返回解析后的工作区。
     */
    private String resolveWorkspace(AgentRunContext runContext) {
        if (runContext != null && StrUtil.isNotBlank(runContext.getWorkspaceDir())) {
            return runContext.getWorkspaceDir();
        }
        return appConfig.getWorkspace().getDir();
    }

    /**
     * 复制大模型配置。
     *
     * @param source 来源参数。
     * @return 返回大模型配置。
     */
    private AppConfig.LlmConfig copyLlmConfig(AppConfig.LlmConfig source) {
        AppConfig.LlmConfig copy = new AppConfig.LlmConfig();
        copy.setProvider(source.getProvider());
        copy.setDialect(source.getDialect());
        copy.setApiUrl(source.getApiUrl());
        copy.setApiKey(source.getApiKey());
        copy.setModel(source.getModel());
        copy.setStream(source.isStream());
        copy.setReasoningEffort(source.getReasoningEffort());
        copy.setTemperature(source.getTemperature());
        copy.setMaxTokens(source.getMaxTokens());
        copy.setContextWindowTokens(source.getContextWindowTokens());
        copy.getPromptCache().setEnabled(source.getPromptCache().isEnabled());
        copy.getPromptCache().setTtl(source.getPromptCache().getTtl());
        copy.getPromptCache().setLayout(source.getPromptCache().getLayout());
        return copy;
    }

    /**
     * 应用Metrics。
     *
     * @param result 结果响应或执行结果。
     * @param metrics metrics 参数。
     */
    private void applyMetrics(LlmResult result, Metrics metrics) {
        if (metrics == null) {
            return;
        }
        result.setInputTokens(metrics.getPromptTokens());
        result.setOutputTokens(metrics.getCompletionTokens());
        result.setTotalTokens(metrics.getTotalTokens());
    }

    /**
     * 执行日志用量相关逻辑。
     *
     * @param session 会话参数。
     * @param resolved resolved 参数。
     * @param result 结果响应或执行结果。
     */
    private void logUsage(SessionRecord session, AppConfig.LlmConfig resolved, LlmResult result) {
        if (result.getTotalTokens() <= 0
                && result.getInputTokens() <= 0
                && result.getOutputTokens() <= 0
                && result.getCacheReadTokens() <= 0
                && result.getCacheWriteTokens() <= 0
                && result.getRequestCount() <= 0) {
            log.info(
                    "LLM usage unavailable: provider={}, dialect={}, model={}, sessionId={}",
                    resolved.getProvider(),
                    resolved.getDialect(),
                    resolved.getModel(),
                    session == null ? "" : StrUtil.nullToEmpty(session.getSessionId()));
            return;
        }

        log.info(
                "LLM usage: provider={}, dialect={}, model={}, sessionId={}, inputTokens={}, outputTokens={}, cacheReadTokens={}, cacheWriteTokens={}, requestCount={}, totalTokens={}",
                resolved.getProvider(),
                resolved.getDialect(),
                resolved.getModel(),
                session == null ? "" : StrUtil.nullToEmpty(session.getSessionId()),
                result.getInputTokens(),
                result.getOutputTokens(),
                result.getCacheReadTokens(),
                result.getCacheWriteTokens(),
                result.getRequestCount(),
                result.getTotalTokens());
    }

    /**
     * 判断是否委托会话。
     *
     * @param agentSession Agent会话参数。
     * @return 如果委托会话满足条件则返回 true，否则返回 false。
     */
    private boolean isDelegateSession(SqliteAgentSession agentSession) {
        Object sourceKey = agentSession.getContext().get("source_key");
        if (sourceKey != null && String.valueOf(sourceKey).contains(":delegate:")) {
            return true;
        }
        Object parentSessionId = agentSession.getContext().get("parent_session_id");
        if (parentSessionId != null && StrUtil.isNotBlank(String.valueOf(parentSessionId))) {
            return true;
        }
        return false;
    }

    /** 判断持久化会话是否为子代理会话。 */
    private boolean isDelegateSession(SessionRecord session) {
        if (session == null) {
            return false;
        }
        return StrUtil.contains(session.getSourceKey(), ":delegate:")
                || StrUtil.isNotBlank(session.getParentSessionId());
    }

    /** 自有 ReAct 循环使用的轻量 trace，复用 Solon AI 生命周期拦截器。 */
    private static class OwnedReActTrace extends ReActTrace {
        /**
         * 创建Owned ReAct Trace。
         *
         * @param chatModel ChatModel参数。
         * @param options Chat选项。
         * @param session Agent会话。
         * @param prompt 原始Prompt。
         */
        private OwnedReActTrace(
                ChatModel chatModel,
                List<ReActInterceptor> interceptors,
                AgentSession session,
                Prompt prompt) {
            super();
            ReActAgentConfig config = new ReActAgentConfig(chatModel);
            OwnedReActOptions reactOptions = new OwnedReActOptions(chatModel);
            if (interceptors != null) {
                for (ReActInterceptor interceptor : interceptors) {
                    if (interceptor != null) {
                        reactOptions.getModelOptions().interceptorAdd(interceptor);
                    }
                }
            }
            prepare(config, reactOptions, session, null, config.getName());
            reset(prompt == null ? Prompt.of("") : prompt);
        }
    }

    /** 自有Loop模型响应。 */
    private static class OwnedModelResponse {
        /** 模型原始响应。 */
        private final ChatResponse response;

        /** assistant消息。 */
        private final AssistantMessage assistantMessage;

        /** 原始响应文本。 */
        private final String rawResponse;

        /** 是否流式。 */
        private final boolean streamed;

        /** 流式解析出的推理文本。 */
        private final String reasoningText;

        /** 标准化后的模型结束原因。 */
        private final String finishReason;

        /**
         * 创建自有Loop模型响应。
         *
         * @param response 模型响应。
         * @param assistantMessage assistant消息。
         * @param rawResponse 原始响应文本。
         * @param streamed 是否流式。
         * @param reasoningText 推理文本。
         * @param finishReason 标准化后的模型结束原因。
         */
        private OwnedModelResponse(
                ChatResponse response,
                AssistantMessage assistantMessage,
                String rawResponse,
                boolean streamed,
                String reasoningText,
                String finishReason) {
            this.response = response;
            this.assistantMessage = assistantMessage;
            this.rawResponse = StrUtil.nullToEmpty(rawResponse);
            this.streamed = streamed;
            this.reasoningText = reasoningText;
            this.finishReason = StrUtil.nullToEmpty(finishReason);
        }
    }

    /** 对单轮阶段说明执行安全过滤、去重、节流和数量限制，并隔离展示端异常。 */
    private final class ProgressUpdateEmitter {
        /** 渠道反馈接收器。 */
        private final ConversationFeedbackSink feedbackSink;

        /** Dashboard 或 TUI 运行事件接收器。 */
        private final ConversationEventSink eventSink;

        /** Agent 运行上下文，用于跨模型尝试共享阶段说明限制。 */
        private final AgentRunContext runContext;

        /** 已发送的阶段说明集合，用于无运行上下文时执行全量去重。 */
        private final Set<String> emittedTexts = new HashSet<String>();

        /** 上一次发送阶段说明的时间戳。 */
        private long lastEmittedAt;

        /** 当前模型运行已经发送的阶段说明数量。 */
        private int emittedCount;

        /** 当前运行已观察到的真实工具事件数量。 */
        private int toolEventCount;

        /** 等待工具达到长任务阈值后发送的进度任务。 */
        private ScheduledFuture<?> pendingToolProgress;

        /** 当前活动工具的递增代次，用于阻止完成边界后的迟到进度。 */
        private long activeToolGeneration;

        /**
         * 创建阶段说明发送器。
         *
         * @param feedbackSink 渠道反馈接收器。
         * @param eventSink 运行事件接收器。
         * @param runContext Agent 运行上下文。
         */
        private ProgressUpdateEmitter(
                ConversationFeedbackSink feedbackSink,
                ConversationEventSink eventSink,
                AgentRunContext runContext) {
            this.feedbackSink =
                    feedbackSink == null ? ConversationFeedbackSink.noop() : feedbackSink;
            this.eventSink = eventSink == null ? ConversationEventSink.noop() : eventSink;
            this.runContext = runContext;
        }

        /**
         * 在符合安全和频率约束时发送一条阶段说明。
         *
         * @param rawText 模型工具调用轮中的原始可见文本。
         */
        private synchronized String emit(String rawText) {
            if ((feedbackSink == ConversationFeedbackSink.noop()
                    && eventSink == ConversationEventSink.noop())) {
                return "";
            }
            String runKind = runContext == null ? "" : runContext.getRunKind();
            if (!"conversation".equalsIgnoreCase(runKind) && !"resume".equalsIgnoreCase(runKind)) {
                return "";
            }
            String text = sanitizeProgressUpdate(rawText);
            if (StrUtil.isBlank(text)) {
                return "";
            }
            long now = System.currentTimeMillis();
            long interval =
                    Math.max(
                            MIN_PROGRESS_UPDATE_INTERVAL_MS,
                            appConfig.getDisplay().getProgressThrottleMs());
            if (runContext != null) {
                if (!runContext.tryRegisterProgressUpdate(
                        text, now, interval, MAX_PROGRESS_UPDATES)) {
                    return "";
                }
            } else {
                if (emittedCount >= MAX_PROGRESS_UPDATES
                        || emittedTexts.contains(text)
                        || (lastEmittedAt > 0 && now - lastEmittedAt < interval)) {
                    return "";
                }
                emittedCount++;
                emittedTexts.add(text);
                lastEmittedAt = now;
            }
            try {
                eventSink.onProgressUpdate(text);
            } catch (RuntimeException e) {
                log.warn("Progress event delivery failed: error={}", ErrorTextSupport.safeError(e));
            }
            try {
                feedbackSink.onProgressUpdate(text);
            } catch (RuntimeException e) {
                log.warn(
                        "Progress feedback delivery failed: error={}",
                        ErrorTextSupport.safeError(e));
            }
            return text;
        }

        /**
         * 根据真实工具生命周期生成阶段进度；第二个工具立即显示，首个长工具延迟显示。
         *
         * @param toolName 已开始执行的工具名称。
         */
        private synchronized void onToolStarted(String toolName) {
            cancelPendingToolProgress();
            toolEventCount++;
            final long generation = ++activeToolGeneration;
            final String text = deterministicToolProgress(toolName);
            if (toolEventCount > 1 && StrUtil.isNotBlank(emit(text))) {
                return;
            }
            pendingToolProgress =
                    PROGRESS_UPDATE_EXECUTOR.schedule(
                            () -> dispatchDelayedToolProgress(generation, text),
                            MIN_PROGRESS_UPDATE_INTERVAL_MS,
                            TimeUnit.MILLISECONDS);
        }

        /** 工具结束时取消尚未达到长任务阈值的延迟进度。 */
        private synchronized void onToolFinished() {
            activeToolGeneration++;
            cancelPendingToolProgress();
        }

        /**
         * 把到期任务交给独立投递线程，并在实际发送前再次确认工具仍处于活动状态。
         *
         * @param generation 工具启动时的代次。
         * @param text 已完成安全分类的阶段说明。
         */
        private void dispatchDelayedToolProgress(long generation, String text) {
            try {
                PROGRESS_DELIVERY_EXECUTOR.execute(() -> emitDelayedToolProgress(generation, text));
            } catch (RuntimeException e) {
                log.warn(
                        "Progress delivery scheduling failed: error={}",
                        ErrorTextSupport.safeError(e));
            }
        }

        /**
         * 仅在对应工具仍活动时发送延迟进度，避免完成或失败后的迟到消息。
         *
         * @param generation 工具启动时的代次。
         * @param text 已完成安全分类的阶段说明。
         */
        private synchronized void emitDelayedToolProgress(long generation, String text) {
            if (generation == activeToolGeneration) {
                emit(text);
            }
        }

        /** 取消当前工具尚未发送的延迟进度任务。 */
        private void cancelPendingToolProgress() {
            if (pendingToolProgress != null) {
                pendingToolProgress.cancel(false);
                pendingToolProgress = null;
            }
        }
    }

    /**
     * 把工具类型转换为不含参数、凭据和内部实现细节的用户可见阶段说明。
     *
     * @param toolName 工具名称。
     * @return 带展示协议前缀的简短中文阶段说明。
     */
    private static String deterministicToolProgress(String toolName) {
        String normalized = StrUtil.nullToEmpty(toolName).trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("search") || normalized.contains("fetch")) {
            return "【阶段说明】我正在检索并核对信息";
        }
        if (normalized.contains("read") || normalized.contains("list")) {
            return "【阶段说明】我正在读取并核对资料";
        }
        if (normalized.contains("write")
                || normalized.contains("patch")
                || normalized.contains("edit")) {
            return "【阶段说明】我正在更新并检查内容";
        }
        if (normalized.contains("shell")
                || normalized.contains("terminal")
                || normalized.contains("execute")) {
            return "【阶段说明】我正在执行并验证操作";
        }
        if (normalized.contains("delegate") || normalized.contains("profile")) {
            return "【阶段说明】我正在分派并汇总任务";
        }
        if (normalized.contains("approval")) {
            return "【阶段说明】我正在等待操作确认";
        }
        return "【阶段说明】我正在继续处理任务";
    }

    /** 续写请求的 assistant 增量缓冲器，确认无重复后再交给真实展示层。 */
    private static class BufferedAssistantEventSink implements ConversationEventSink {
        /** 真实事件接收器。 */
        private final ConversationEventSink delegate;

        /** 当前续写请求接收到的原始 assistant 增量。 */
        private final StringBuilder assistantText = new StringBuilder();

        /** 创建续写增量缓冲器。 */
        private BufferedAssistantEventSink(ConversationEventSink delegate) {
            this.delegate = delegate == null ? ConversationEventSink.noop() : delegate;
        }

        /** 暂存 assistant 增量，避免提供方重复开头直接进入用户界面。 */
        @Override
        public void onAssistantDelta(String delta) {
            assistantText.append(StrUtil.nullToEmpty(delta));
        }

        /** reasoning 不参与正文去重，仍实时转发到独立思考区域。 */
        @Override
        public void onReasoningDelta(String delta) {
            delegate.onReasoningDelta(delta);
        }

        /** 返回当前续写请求累计的 assistant 原始增量。 */
        private String assistantText() {
            return assistantText.toString();
        }
    }

    /** 文本 Action 解析结果。 */
    private static class ParsedTextAction {
        /** 工具名。 */
        private final String name;

        /** 工具参数。 */
        private final Map<String, Object> arguments;

        /** 参数是否来自完整 JSON 对象。 */
        private final boolean argumentsValid;

        /** 模型原始参数文本，非法时保留给执行边界做 fail-closed 判断。 */
        private final String rawArguments;

        /**
         * 创建文本 Action 解析结果。
         *
         * @param name 工具名。
         * @param arguments 工具参数。
         */
        private ParsedTextAction(
                String name,
                Map<String, Object> arguments,
                boolean argumentsValid,
                String rawArguments) {
            this.name = name;
            this.arguments = arguments;
            this.argumentsValid = argumentsValid;
            this.rawArguments = rawArguments;
        }
    }

    /** 文本 Action 参数解析结果。 */
    private static class ParsedToolArguments {
        /** 仅在合法 JSON 对象时有值。 */
        private final Map<String, Object> arguments;

        /** 是否为完整 JSON 对象。 */
        private final boolean valid;

        /** 原始参数文本。 */
        private final String rawArguments;

        /** 创建参数解析结果。 */
        private ParsedToolArguments(
                Map<String, Object> arguments, boolean valid, String rawArguments) {
            this.arguments = arguments;
            this.valid = valid;
            this.rawArguments = rawArguments;
        }

        /** 返回合法参数结果。 */
        private static ParsedToolArguments valid(
                Map<String, Object> arguments, String rawArguments) {
            return new ParsedToolArguments(arguments, true, rawArguments);
        }

        /** 返回非法参数结果。 */
        private static ParsedToolArguments invalid(String rawArguments) {
            return new ParsedToolArguments(null, false, rawArguments);
        }
    }

    /** 暴露受保护构造路径的自有 ReActOptions。 */
    private static class OwnedReActOptions extends ReActOptions {
        /**
         * 创建Owned ReAct Options。
         *
         * @param chatModel ChatModel参数。
         */
        private OwnedReActOptions(ChatModel chatModel) {
            super(chatModel);
        }
    }

    /** 懒加载 PDF 技能，统一复用 workspace/cache/pdf 目录。 */
    PdfTalent pdfSkill() {
        if (pdfSkill == null) {
            synchronized (this) {
                if (pdfSkill == null) {
                    pdfSkill = buildPdfSkill();
                }
            }
        }
        return pdfSkill;
    }

    /**
     * 构建Pdf技能。
     *
     * @return 返回创建好的Pdf技能。
     */
    private PdfTalent buildPdfSkill() {
        File pdfWorkDir = new File(appConfig.getRuntime().getCacheDir(), "pdf");
        if (!pdfWorkDir.exists() && !pdfWorkDir.mkdirs()) {
            log.warn("Failed to create pdf work directory: {}", safePathRef(pdfWorkDir));
        }

        final File fontFile = resolvePdfFontFile();
        if (fontFile != null) {
            log.info("PDF skill font detected: {}", safePathRef(fontFile));
            return new PdfTalent(
                    pdfWorkDir.getAbsolutePath(),
                    new Supplier<InputStream>() {
                        /**
                         * 获取当前注册项或配置项。
                         *
                         * @return 返回get结果。
                         */
                        @Override
                        public InputStream get() {
                            try {
                                return new FileInputStream(fontFile);
                            } catch (Exception e) {
                                log.warn(
                                        "Failed to open PDF font file: path={}, error={}",
                                        safePathRef(fontFile),
                                        safeError(e));
                                return null;
                            }
                        }
                    });
        }

        log.warn("No PDF font detected, PDF generation will use default font fallback");
        return new PdfTalent(pdfWorkDir.getAbsolutePath());
    }

    /**
     * 解析Pdf Font文件。
     *
     * @return 返回解析后的Pdf Font文件。
     */
    private File resolvePdfFontFile() {
        String override = RuntimeConfigResolver.getValue("solonclaw.pdf.fontPath");
        if (StrUtil.isNotBlank(override)) {
            File file = new File(override.trim());
            if (file.isFile()) {
                return file;
            }
            log.warn("Configured PDF font path not found: {}", safePathRef(file));
        }

        List<String> candidates =
                Arrays.asList(
                        "C:\\Windows\\Fonts\\msyh.ttf",
                        "C:\\Windows\\Fonts\\simhei.ttf",
                        "/Library/Fonts/Arial Unicode.ttf",
                        "/usr/share/fonts/truetype/arphic-gbsn00lp/gbsn00lp.ttf",
                        "/usr/share/fonts/truetype/arphic/gbsn00lp.ttf",
                        "/usr/share/fonts/truetype/gbsn00lp.ttf",
                        "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttf",
                        "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttf");

        for (String path : candidates) {
            File file = new File(path);
            if (file.isFile()) {
                return file;
            }
        }

        return null;
    }

    /** 承载SolonAiSmart审批Judge相关状态和辅助逻辑。 */
    private class SolonAiSmartApprovalJudge implements SmartApprovalJudge {
        /**
         * 执行judge相关逻辑。
         *
         * @param toolName 工具名称。
         * @param command 待执行或解析的命令文本。
         * @param description 描述参数。
         * @return 返回judge结果。
         */
        @Override
        public SmartApprovalDecision judge(String toolName, String command, String description) {
            try {
                String prompt =
                        "You are the smart approval judge for a local AI agent. "
                                + "Decide whether this flagged command is low risk enough to run without asking the user, genuinely dangerous, or uncertain. "
                                + "Reply with only compact JSON: {\"decision\":\"approve\"|\"deny\"|\"escalate\",\"reason\":\"...\"}. "
                                + "Approve only read-only, diagnostic, or clearly reversible low-risk actions. "
                                + "Deny genuinely destructive actions. Escalate credential, network install, privilege, persistence, service, or ambiguous actions.\n\n"
                                + "tool: "
                                + StrUtil.nullToEmpty(toolName)
                                + "\nreason: "
                                + StrUtil.nullToEmpty(description)
                                + "\ncommand:\n"
                                + StrUtil.nullToEmpty(command);
                LlmResult result =
                        SolonAiLlmGateway.this.chatTextOnly(
                                smartApprovalSession(),
                                "You are a strict command risk classifier.",
                                prompt);
                String output =
                        result == null
                                ? ""
                                : MessageSupport.assistantText(result.getAssistantMessage());
                if (StrUtil.isBlank(output) && result != null) {
                    output = MessageSupport.visibleText(result.getRawResponse());
                }
                return parseSmartApprovalResponse(output);
            } catch (Exception e) {
                log.warn(
                        "Smart approval judge failed, escalating to manual approval: {}",
                        e.getMessage());
                return SmartApprovalDecision.escalate("smart approval failed");
            }
        }
    }

    /**
     * 创建只供危险操作智能审批使用的辅助会话；路由留空时由统一 Provider 解析器继承 Profile 主模型。
     *
     * @return 携带审批模型路由的临时会话。
     */
    private SessionRecord smartApprovalSession() {
        SessionRecord session = new SessionRecord();
        session.setSessionId("smart-approval");
        if (StrUtil.isNotBlank(appConfig.getApprovals().getModelProvider())) {
            session.setTransientProviderOverride(
                    appConfig.getApprovals().getModelProvider().trim());
        }
        if (StrUtil.isNotBlank(appConfig.getApprovals().getModel())) {
            session.setTransientModelOverride(appConfig.getApprovals().getModel().trim());
        }
        return session;
    }

    /**
     * 解析Smart审批响应。
     *
     * @param raw 原始输入值。
     * @return 返回解析后的Smart审批响应。
     */
    private SmartApprovalDecision parseSmartApprovalResponse(String raw) {
        String text = StrUtil.nullToEmpty(raw).trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("(?is)^```(?:json)?\\s*", "");
            text = text.replaceFirst("(?is)\\s*```$", "").trim();
        }
        try {
            Object parsed = ONode.deserialize(text, Object.class);
            if (parsed instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) parsed;
                String decision =
                        StrUtil.nullToEmpty(String.valueOf(map.get("decision")))
                                .trim()
                                .toLowerCase(Locale.ROOT);
                String reason = StrUtil.nullToEmpty(String.valueOf(map.get("reason")));
                if ("approve".equals(decision)) {
                    return SmartApprovalDecision.approve(reason);
                }
                if ("deny".equals(decision)) {
                    return SmartApprovalDecision.deny(reason);
                }
                return SmartApprovalDecision.escalate(reason);
            }
        } catch (Exception e) {
            log.debug(
                    "Smart approval response was not valid JSON; using text fallback: {}",
                    safeError(e));
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("\"approve\"") || lower.startsWith("approve")) {
            return SmartApprovalDecision.approve(text);
        }
        if (lower.contains("\"deny\"") || lower.startsWith("deny")) {
            return SmartApprovalDecision.deny(text);
        }
        return SmartApprovalDecision.escalate(text);
    }

    /**
     * 将异常转换为可展示且不泄漏敏感信息的错误文本。
     *
     * @param error 错误参数。
     * @return 返回safe Error结果。
     */
    private String safeError(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        String message = error.getMessage();
        String value = StrUtil.isBlank(message) ? error.getClass().getSimpleName() : message;
        return SecretRedactor.redact(value, 1000);
    }

    /**
     * 生成安全展示用的路径Ref。
     *
     * @param file 文件或目录路径参数。
     * @return 返回safe路径Ref结果。
     */
    private String safePathRef(File file) {
        if (file == null) {
            return "path://unknown";
        }
        String name = file.getName();
        if (StrUtil.isBlank(name)) {
            name = "path";
        }
        return "path://" + SecretRedactor.redact(name, 200);
    }

    /** 根据 Web 单轮运行策略在工具真正执行前拒绝越界调用。 */
    private static class ToolPolicyInterceptor implements ReActInterceptor {
        /** 保存当前运行上下文，用于读取工具白名单和次数预算。 */
        private final AgentRunContext runContext;

        /**
         * 创建工具策略拦截器。
         *
         * @param runContext 当前 Agent 运行上下文。
         */
        private ToolPolicyInterceptor(AgentRunContext runContext) {
            this.runContext = runContext;
        }

        /**
         * 在模型选择工具后、真实工具执行前检查本轮 Web 运行策略。
         *
         * @param trace ReAct 轨迹对象。
         * @param exchanger 工具交换对象。
         */
        @Override
        public void onAction(ReActTrace trace, ToolExchanger exchanger) {
            if (runContext == null || !runContext.hasToolPolicy()) {
                return;
            }
            String toolName = exchanger == null ? null : exchanger.getToolName();
            Map<String, Object> args = exchanger == null ? null : exchanger.getArgs();
            String rejection = runContext.recordToolAttempt(toolName);
            if (StrUtil.isBlank(rejection)) {
                return;
            }
            if (trace != null) {
                ReActToolObservationSupport.set(trace, exchanger, rejection);
            }
            Map<String, Object> metadata = new LinkedHashMap<String, Object>();
            metadata.put("tool", toolName);
            metadata.put("args", args);
            metadata.put("allowed_tools", runContext.getAllowedToolNames());
            metadata.put("max_tool_calls", runContext.getMaxToolCalls());
            metadata.put(
                    "attempted_tool_calls", Integer.valueOf(runContext.getAttemptedToolCalls()));
            runContext.event("tool.policy.denied", rejection, metadata);
        }
    }

    /** 将 ReAct 生命周期事件桥接到网关反馈 sink。 */
    private static class FeedbackInterceptor implements ReActInterceptor {
        /** 记录反馈Interceptor中的反馈接收端。 */
        private final ConversationFeedbackSink feedbackSink;

        /** 注入dangerous命令审批服务，用于调用对应业务能力。 */
        private final DangerousCommandApprovalService dangerousCommandApprovalService;

        /**
         * 创建Feedback Interceptor实例，并注入运行所需依赖。
         *
         * @param feedbackSink 反馈Sink参数。
         * @param dangerousCommandApprovalService dangerous命令审批服务依赖。
         */
        private FeedbackInterceptor(
                ConversationFeedbackSink feedbackSink,
                DangerousCommandApprovalService dangerousCommandApprovalService) {
            this.feedbackSink = feedbackSink;
            this.dangerousCommandApprovalService = dangerousCommandApprovalService;
        }

        /**
         * 响应Thought事件。
         *
         * @param trace trace 参数。
         * @param thought thought 参数。
         * @param message assistant消息。
         */
        @Override
        public void onThought(ReActTrace trace, String thought, AssistantMessage message) {
            feedbackSink.onReasoning(thought);
        }

        /**
         * 响应Action事件。
         *
         * @param trace trace 参数。
         * @param exchanger 工具交换对象。
         */
        @Override
        public void onAction(ReActTrace trace, ToolExchanger exchanger) {
            if (trace != null
                    && StrUtil.isNotBlank(ReActToolObservationSupport.get(trace, exchanger))) {
                return;
            }
            if (trace != null && trace.getSession() != null && trace.getSession().isPending()) {
                return;
            }
            if (dangerousCommandApprovalService != null
                    && trace != null
                    && trace.getSession() != null
                    && dangerousCommandApprovalService.getPendingApproval(trace.getSession())
                            != null) {
                return;
            }
            String toolName = exchanger == null ? null : exchanger.getToolName();
            Map<String, Object> args = exchanger == null ? null : exchanger.getArgs();
            feedbackSink.onToolStarted(toolName, args);
        }

        /**
         * 响应观察结果事件。
         *
         * @param trace trace 参数。
         * @param exchanger 工具交换对象。
         * @param message 工具消息。
         * @param error 工具异常。
         * @param durationMs durationMs 参数。
         */
        @Override
        public void onObservation(
                ReActTrace trace,
                ToolExchanger exchanger,
                ChatMessage message,
                Throwable error,
                long durationMs) {
            String toolName = exchanger == null ? null : exchanger.getToolName();
            String result = ReActToolObservationSupport.get(trace, exchanger);
            feedbackSink.onToolFinished(toolName, result, durationMs);
        }
    }

    /** 承载用量CollectingInterceptor相关状态和辅助逻辑。 */
    private static class UsageCollectingInterceptor implements ReActInterceptor {
        /** 记录用量CollectingInterceptor中的用量Collector。 */
        private final UsageCollector usageCollector;

        /**
         * 创建用量Collecting Interceptor实例，并注入运行所需依赖。
         *
         * @param usageCollector 用量Collector参数。
         */
        private UsageCollectingInterceptor(UsageCollector usageCollector) {
            this.usageCollector = usageCollector;
        }

        /**
         * 响应模型End事件。
         *
         * @param request 模型请求。
         * @param chain 后续调用链。
         * @return 返回模型响应。
         */
        @Override
        public ChatResponse interceptCall(ChatRequest request, CallChain chain)
                throws java.io.IOException {
            ChatResponse resp = chain.doIntercept(request);
            if (resp != null) {
                usageCollector.add(resp.getUsage());
            }
            return resp;
        }

        /**
         * 收集流式模型分片中的用量信息。
         *
         * @param request 模型请求。
         * @param chain 后续流式调用链。
         * @return 返回流式模型响应。
         */
        @Override
        public Flux<ChatResponse> interceptStream(ChatRequest request, StreamChain chain) {
            return chain.doIntercept(request)
                    .doOnNext(
                            resp -> {
                                if (resp != null) {
                                    usageCollector.add(resp.getUsage());
                                }
                            });
        }
    }

    /** 承载用量Collector相关状态和辅助逻辑。 */
    private static class UsageCollector {
        /** 记录用量Collector中的提示词 token。 */
        private long promptTokens;

        /** 记录用量Collector中的输出 token。 */
        private long completionTokens;

        /** 记录用量Collector中的totaltoken。 */
        private long totalTokens;

        /** 记录用量Collector中的推理 token。 */
        private long reasoningTokens;

        /** 记录用量Collector中的缓存读取 token。 */
        private long cacheReadTokens;

        /** 记录用量Collector中的缓存写入 token。 */
        private long cacheWriteTokens;

        /** 记录用量Collector中的请求次数。 */
        private long requestCount;

        /** 保存原始用量 JSON集合，维持调用顺序或去重语义。 */
        private final List<String> rawUsageJson = new ArrayList<String>();

        /**
         * 执行add相关逻辑。
         *
         * @param usage 用量参数。
         */
        private synchronized void add(AiUsage usage) {
            if (usage == null) {
                return;
            }
            UsageSnapshot snapshot = normalize(usage);
            promptTokens += snapshot.inputTokens;
            completionTokens += snapshot.outputTokens;
            totalTokens += snapshot.totalTokens;
            reasoningTokens += snapshot.reasoningTokens;
            cacheReadTokens += snapshot.cacheReadTokens;
            cacheWriteTokens += snapshot.cacheWriteTokens;
            requestCount += snapshot.requestCount;
            if (usage.getSource() != null) {
                rawUsageJson.add(usage.getSource().toJson());
            }
        }

        /**
         * 记录最终流式分片里的模型用量；若拦截器已经收过同一份原始 usage，则避免重复累计。
         *
         * @param usage 最终流式分片携带的用量。
         */
        private synchronized void addFinalStreamUsage(AiUsage usage) {
            if (usage == null) {
                return;
            }
            ONode source = usage.getSource();
            if (source != null && rawUsageJson.contains(source.toJson())) {
                return;
            }
            add(usage);
        }

        /**
         * 应用To。
         *
         * @param result 结果响应或执行结果。
         */
        private synchronized void applyTo(LlmResult result) {
            if (result == null) {
                return;
            }
            result.setCacheReadTokens(cacheReadTokens);
            result.setCacheWriteTokens(cacheWriteTokens);
            result.setReasoningTokens(reasoningTokens);
            result.setRequestCount(requestCount);
            if (promptTokens > 0) {
                result.setInputTokens(promptTokens);
            }
            if (completionTokens > 0) {
                result.setOutputTokens(completionTokens);
            }
            if (totalTokens > 0) {
                result.setTotalTokens(totalTokens);
            }
            if (!rawUsageJson.isEmpty()) {
                result.setRawUsageJson(
                        rawUsageJson.size() == 1 ? rawUsageJson.get(0) : rawUsageArrayJson());
            }
        }

        /**
         * 执行原始用量ArrayJSON相关逻辑。
         *
         * @return 返回原始用量Array JSON结果。
         */
        private String rawUsageArrayJson() {
            StringBuilder json = new StringBuilder();
            json.append('[');
            for (int i = 0; i < rawUsageJson.size(); i++) {
                if (i > 0) {
                    json.append(',');
                }
                json.append(rawUsageJson.get(i));
            }
            json.append(']');
            return json.toString();
        }

        /**
         * 执行规范化相关逻辑。
         *
         * @param usage 用量参数。
         * @return 返回规范化结果。
         */
        private UsageSnapshot normalize(AiUsage usage) {
            ONode source = usage.getSource();
            long rawPromptTokens = Math.max(0L, usage.promptTokens());
            long outputTokens = Math.max(0L, usage.completionTokens());
            long cacheReadTokens = cacheReadTokens(usage, source);
            long cacheWriteTokens = cacheWriteTokens(usage, source);
            long inputTokens = rawPromptTokens;
            if (promptTotalIncludesCache(source)) {
                inputTokens = Math.max(0L, rawPromptTokens - cacheReadTokens - cacheWriteTokens);
            }
            long canonicalTotal = inputTokens + outputTokens + cacheReadTokens + cacheWriteTokens;
            long apiTotal = Math.max(0L, usage.totalTokens());
            return new UsageSnapshot(
                    inputTokens,
                    outputTokens,
                    Math.max(apiTotal, canonicalTotal),
                    reasoningTokens(usage, source),
                    cacheReadTokens,
                    cacheWriteTokens,
                    requestCount(source));
        }

        /**
         * 执行缓存读取 token相关逻辑。
         *
         * @param usage 用量参数。
         * @param source 来源参数。
         * @return 返回缓存读取 token结果。
         */
        private long cacheReadTokens(AiUsage usage, ONode source) {
            return max(
                    Math.max(0L, usage.cacheReadInputTokens()),
                    detailLong(source, "prompt_tokens_details", "cached_tokens"),
                    detailLong(source, "input_tokens_details", "cached_tokens"),
                    nodeLong(source, "cache_read_input_tokens"));
        }

        /**
         * 执行缓存写入 token相关逻辑。
         *
         * @param usage 用量参数。
         * @param source 来源参数。
         * @return 返回缓存写入 token结果。
         */
        private long cacheWriteTokens(AiUsage usage, ONode source) {
            return max(
                    Math.max(0L, usage.cacheCreationInputTokens()),
                    detailLong(source, "prompt_tokens_details", "cache_write_tokens"),
                    detailLong(source, "input_tokens_details", "cache_creation_tokens"),
                    detailLong(source, "input_tokens_details", "cache_write_tokens"),
                    nodeLong(source, "cache_creation_input_tokens"));
        }

        /**
         * 执行推理 token相关逻辑。
         *
         * @param usage 用量参数。
         * @param source 来源参数。
         * @return 返回推理 token结果。
         */
        private long reasoningTokens(AiUsage usage, ONode source) {
            return max(
                    Math.max(0L, usage.thinkTokens()),
                    detailLong(source, "completion_tokens_details", "reasoning_tokens"),
                    detailLong(source, "output_tokens_details", "reasoning_tokens"));
        }

        /**
         * 执行请求次数相关逻辑。
         *
         * @param source 来源参数。
         * @return 返回请求次数结果。
         */
        private long requestCount(ONode source) {
            long raw = nodeLong(source, "request_count");
            return raw > 0L ? raw : 1L;
        }

        /**
         * 执行提示词TotalIncludes缓存相关逻辑。
         *
         * @param source 来源参数。
         * @return 返回提示词Total Includes缓存结果。
         */
        private boolean promptTotalIncludesCache(ONode source) {
            return objectNode(source, "prompt_tokens_details") != null
                    || objectNode(source, "input_tokens_details") != null
                    || nodeLong(source, "prompt_tokens") > 0L;
        }

        /**
         * 执行object节点相关逻辑。
         *
         * @param source 来源参数。
         * @param key 配置键或映射键。
         * @return 返回object Node结果。
         */
        private ONode objectNode(ONode source, String key) {
            if (source == null || !source.isObject()) {
                return null;
            }
            ONode node = source.getOrNull(key);
            return node == null || !node.isObject() ? null : node;
        }

        /**
         * 执行详情长整型相关逻辑。
         *
         * @param source 来源参数。
         * @param detailsKey details键标识或键值。
         * @param key 配置键或映射键。
         * @return 返回detail Long结果。
         */
        private long detailLong(ONode source, String detailsKey, String key) {
            return nodeLong(objectNode(source, detailsKey), key);
        }

        /**
         * 执行节点长整型相关逻辑。
         *
         * @param node 节点参数。
         * @param key 配置键或映射键。
         * @return 返回node Long结果。
         */
        private long nodeLong(ONode node, String key) {
            if (node == null || !node.isObject() || !node.hasKey(key)) {
                return 0L;
            }
            Long value = node.get(key).getLong(0L);
            return value == null ? 0L : Math.max(0L, value);
        }

        /**
         * 返回多个候选值中的最大值。
         *
         * @param values 待规范化或校验的原始值集合。
         * @return 返回max结果。
         */
        private long max(long... values) {
            long result = 0L;
            if (values == null) {
                return result;
            }
            for (long value : values) {
                result = Math.max(result, value);
            }
            return result;
        }

        /** 承载用量快照相关状态和辅助逻辑。 */
        private static class UsageSnapshot {
            /** 记录用量快照中的输入 token。 */
            private final long inputTokens;

            /** 记录用量快照中的输出 token。 */
            private final long outputTokens;

            /** 记录用量快照中的totaltoken。 */
            private final long totalTokens;

            /** 记录用量快照中的推理 token。 */
            private final long reasoningTokens;

            /** 记录用量快照中的缓存读取 token。 */
            private final long cacheReadTokens;

            /** 记录用量快照中的缓存写入 token。 */
            private final long cacheWriteTokens;

            /** 记录用量快照中的请求次数。 */
            private final long requestCount;

            /**
             * 创建用量Snapshot实例，并注入运行所需依赖。
             *
             * @param inputTokens 输入 token 数。
             * @param outputTokens 输出 token 数。
             * @param totalTokens totaltoken参数。
             * @param reasoningTokens 推理 token 数。
             * @param cacheReadTokens 缓存读取 token 数。
             * @param cacheWriteTokens 缓存写入 token 数。
             * @param requestCount 请求Count请求载荷。
             */
            private UsageSnapshot(
                    long inputTokens,
                    long outputTokens,
                    long totalTokens,
                    long reasoningTokens,
                    long cacheReadTokens,
                    long cacheWriteTokens,
                    long requestCount) {
                this.inputTokens = inputTokens;
                this.outputTokens = outputTokens;
                this.totalTokens = totalTokens;
                this.reasoningTokens = reasoningTokens;
                this.cacheReadTokens = cacheReadTokens;
                this.cacheWriteTokens = cacheWriteTokens;
                this.requestCount = requestCount;
            }
        }
    }

    /** 将 ReAct 生命周期写入持久化 run 轨迹。 */
    private static class TracingReActInterceptor implements ReActInterceptor {
        /** 记录TracingReActInterceptor中的运行上下文。 */
        private final AgentRunContext runContext;

        /** 记录TracingReActInterceptor中的预览Length。 */
        private final int previewLength;

        /** 记录TracingReActInterceptor中的内联限制字节。 */
        private final int inlineLimitBytes;

        /** 危险命令审批服务，用于区分待审批暂停与异常中断。 */
        private final DangerousCommandApprovalService approvalService;

        /** 保存active工具Calls映射，便于按键快速查询。 */
        private final ConcurrentMap<String, ToolCallRecord> activeToolCalls =
                new ConcurrentHashMap<String, ToolCallRecord>();

        /**
         * 创建Tracing Re Act Interceptor实例，并注入运行所需依赖。
         *
         * @param runContext 运行上下文。
         * @param previewLength 预览Length参数。
         * @param inlineLimitBytes 内联Limit字节参数。
         * @param approvalService 危险命令审批服务。
         */
        private TracingReActInterceptor(
                AgentRunContext runContext,
                int previewLength,
                int inlineLimitBytes,
                DangerousCommandApprovalService approvalService) {
            this.runContext = runContext;
            this.previewLength = Math.max(200, previewLength);
            this.inlineLimitBytes = Math.max(256, inlineLimitBytes);
            this.approvalService = approvalService;
        }

        /**
         * 响应模型Start事件。
         *
         * @param request 模型请求。
         * @param chain 后续调用链。
         * @return 返回模型响应。
         */
        @Override
        public ChatResponse interceptCall(ChatRequest request, CallChain chain)
                throws java.io.IOException {
            runContext.setPhase("model");
            runContext.event("model.start", "开始请求模型");
            try {
                return chain.doIntercept(request);
            } finally {
                runContext.setPhase("model");
                runContext.event("model.end", "模型响应完成");
            }
        }

        /**
         * 记录流式模型请求的开始与结束事件。
         *
         * @param request 模型请求。
         * @param chain 后续流式调用链。
         * @return 返回流式模型响应。
         */
        @Override
        public Flux<ChatResponse> interceptStream(ChatRequest request, StreamChain chain) {
            runContext.setPhase("model");
            runContext.event("model.start", "开始请求模型");
            return chain.doIntercept(request)
                    .doFinally(
                            signal -> {
                                runContext.setPhase("model");
                                runContext.event("model.end", "模型响应完成");
                            });
        }

        /**
         * 响应Action事件。
         *
         * @param trace trace 参数。
         * @param exchanger 工具交换对象。
         */
        @Override
        public void onAction(ReActTrace trace, ToolExchanger exchanger) {
            String toolName = exchanger == null ? null : exchanger.getToolName();
            Map<String, Object> args = exchanger == null ? null : exchanger.getArgs();
            runContext.setPhase("tool");
            Map<String, Object> metadata = new java.util.LinkedHashMap<String, Object>();
            metadata.put("tool", toolName);
            metadata.put("args", args);
            runContext.event("tool.start", "调用工具：" + toolName, metadata);
            ToolCallRecord record = new ToolCallRecord();
            record.setToolCallId(IdSupport.newId());
            record.setRunId(runContext.getRunId());
            record.setSessionId(runContext.getSessionId());
            record.setSourceKey(runContext.getSourceKey());
            record.setToolName(toolName);
            record.setStatus("running");
            record.setArgsPreview(AgentRunContext.safe(String.valueOf(args), previewLength));
            record.setInterruptible(true);
            record.setSideEffecting(isSideEffectingTool(toolName, args));
            record.setReadOnly(!record.isSideEffecting());
            record.setResultIndexable(true);
            record.setOutputLimitBytes(inlineLimitBytes);
            record.setExecutionPolicy(record.isSideEffecting() ? "serial" : "parallel_readonly");
            record.setStartedAt(System.currentTimeMillis());
            activeToolCalls.put(toolName, record);
            runContext.saveToolCall(record);
        }

        /**
         * 响应观察结果事件。
         *
         * @param trace trace 参数。
         * @param exchanger 工具交换对象。
         * @param message 工具消息。
         * @param error 工具异常。
         * @param durationMs durationMs 参数。
         */
        @Override
        public void onObservation(
                ReActTrace trace,
                ToolExchanger exchanger,
                ChatMessage message,
                Throwable error,
                long durationMs) {
            String toolName = exchanger == null ? null : exchanger.getToolName();
            String result = ReActToolObservationSupport.get(trace, exchanger);
            runContext.setPhase("tool");
            Map<String, Object> metadata = new java.util.LinkedHashMap<String, Object>();
            metadata.put("tool", toolName);
            metadata.put("durationMs", durationMs);
            String observation = result;
            ToolResultStorageService.StoredResult output =
                    ToolResultStorageService.describeObservation(
                            StrUtil.blankToDefault(observation, result));
            boolean policyDenied = isPolicyDeniedObservation(observation);
            boolean failed = error != null || structuredToolError(null, observation) != null;
            metadata.put("preview", output.getPreview());
            metadata.put("result_ref", output.getResultRef());
            runContext.event(
                    policyDenied ? "tool.policy.end" : "tool.end",
                    (policyDenied ? "工具策略拒绝：" : "工具完成：") + toolName + "（" + durationMs + "ms）",
                    metadata);
            ToolCallRecord record = activeToolCalls.remove(toolName);
            if (record == null) {
                record = new ToolCallRecord();
                record.setToolCallId(IdSupport.newId());
                record.setRunId(runContext.getRunId());
                record.setSessionId(runContext.getSessionId());
                record.setSourceKey(runContext.getSourceKey());
                record.setToolName(toolName);
                record.setStartedAt(System.currentTimeMillis());
                record.setInterruptible(true);
                record.setSideEffecting(isSideEffectingTool(toolName, null));
                record.setReadOnly(!record.isSideEffecting());
                record.setResultIndexable(true);
                record.setOutputLimitBytes(inlineLimitBytes);
                record.setExecutionPolicy(
                        record.isSideEffecting() ? "serial" : "parallel_readonly");
            }
            record.setStatus(policyDenied ? "denied" : failed ? "failed" : "completed");
            if (failed) {
                record.setError(
                        error == null
                                ? structuredToolError(null, observation)
                                : ErrorTextSupport.safeError(error));
            }
            record.setResultPreview(output.getPreview());
            record.setResultRef(output.getResultRef());
            record.setResultSizeBytes(output.getSizeBytes());
            record.setFinishedAt(System.currentTimeMillis());
            record.setDurationMs(durationMs);
            runContext.saveToolCall(record);
        }

        /**
         * Agent 本轮结束时关闭没有 observation 的工具轨迹，避免审批暂停后长期残留 running。
         *
         * @param trace 当前 ReAct 运行轨迹。
         */
        @Override
        public void onAgentEnd(ReActTrace trace) {
            if (activeToolCalls.isEmpty()) {
                return;
            }
            boolean approvalRequired = hasPendingApproval(trace);
            long finishedAt = System.currentTimeMillis();
            for (ToolCallRecord record : activeToolCalls.values()) {
                record.setStatus(approvalRequired ? "approval_required" : "interrupted");
                record.setError(approvalRequired ? "工具等待人工审批，未在本次运行中执行" : "Agent 本轮结束前未收到工具执行结果");
                record.setFinishedAt(finishedAt);
                record.setDurationMs(Math.max(0L, finishedAt - record.getStartedAt()));
                runContext.saveToolCall(record);
            }
            activeToolCalls.clear();
        }

        /** 判断当前 ReAct 会话是否仍有待处理的人工审批。 */
        private boolean hasPendingApproval(ReActTrace trace) {
            if (approvalService == null || trace == null || trace.getSession() == null) {
                return false;
            }
            try {
                return approvalService.getPendingApproval(trace.getSession()) != null;
            } catch (RuntimeException e) {
                log.debug(
                        "Pending approval lookup failed while closing tool trace: {}",
                        SecretRedactor.redact(
                                StrUtil.blankToDefault(
                                        e.getMessage(), e.getClass().getSimpleName()),
                                500));
                return false;
            }
        }

        /**
         * 判断 observation 是否来自本轮工具策略硬拒绝，用于避免把未执行工具记为 completed。
         *
         * @param observation 工具 observation 文本。
         * @return 如果是策略拒绝 observation 则返回 true。
         */
        private boolean isPolicyDeniedObservation(String observation) {
            return StrUtil.isNotBlank(observation)
                    && observation.startsWith(AgentRunContext.TOOL_POLICY_REJECTION_PREFIX)
                    && observation.contains("已被拒绝执行");
        }

        /**
         * 判断是否Side Effecting工具。
         *
         * @param toolName 工具名称。
         * @return 如果Side Effecting工具满足条件则返回 true，否则返回 false。
         */
        private boolean isSideEffectingTool(String toolName, Map<String, Object> args) {
            if (toolName == null) {
                return false;
            }
            String value = toolName.toLowerCase(java.util.Locale.ROOT);
            if ("todo".equals(value)) {
                return args != null && args.containsKey("todos") && args.get("todos") != null;
            }
            if ("cronjob".equals(value)) {
                return isCronjobMutationAction(args == null ? null : args.get("action"));
            }
            if ("workspace_manage".equals(value)) {
                return isWorkspaceMutationAction(args == null ? null : args.get("action"));
            }
            return value.contains("write")
                    || value.contains("delete")
                    || value.contains("shell")
                    || value.contains("python")
                    || value.contains("js")
                    || value.contains("send")
                    || value.contains("cron")
                    || value.contains("skill_manage")
                    || value.contains("delegate");
        }

        /**
         * 判断 cronjob 动作是否会改变任务或触发执行；inspect/list/history 属于诊断读取。
         *
         * @param action action 参数。
         * @return 如果该动作会改变任务状态或触发任务执行则返回 true。
         */
        private boolean isCronjobMutationAction(Object action) {
            String normalized =
                    action == null
                            ? "list"
                            : String.valueOf(action).trim().toLowerCase(Locale.ROOT);
            return "create".equals(normalized)
                    || "add".equals(normalized)
                    || "update".equals(normalized)
                    || "edit".equals(normalized)
                    || "pause".equals(normalized)
                    || "disable".equals(normalized)
                    || "stop".equals(normalized)
                    || "resume".equals(normalized)
                    || "enable".equals(normalized)
                    || "start".equals(normalized)
                    || "run".equals(normalized)
                    || "rerun".equals(normalized)
                    || "retry".equals(normalized)
                    || "remove".equals(normalized)
                    || "delete".equals(normalized);
        }

        /**
         * 判断工作区管理动作是否会写入文件、触发归档或恢复记忆。
         *
         * @param action action 参数。
         * @return 写动作返回 true，查询动作返回 false。
         */
        private boolean isWorkspaceMutationAction(Object action) {
            String normalized =
                    action == null
                            ? "files"
                            : String.valueOf(action).trim().toLowerCase(Locale.ROOT);
            return "save_file".equals(normalized)
                    || "save".equals(normalized)
                    || "upsert_note".equals(normalized)
                    || "upsert".equals(normalized)
                    || "remove_note".equals(normalized)
                    || "remove".equals(normalized)
                    || "restore_file".equals(normalized)
                    || "restore".equals(normalized)
                    || "archive_run".equals(normalized)
                    || "archive_restore".equals(normalized);
        }
    }
}
