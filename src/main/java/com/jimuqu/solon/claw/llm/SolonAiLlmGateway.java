package com.jimuqu.solon.claw.llm;

import cn.hutool.core.util.StrUtil;
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
import com.jimuqu.solon.claw.plugin.HookBridgeInterceptor;
import com.jimuqu.solon.claw.storage.session.SqliteAgentSession;
import com.jimuqu.solon.claw.support.IdSupport;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.support.MessageAttachmentSupport;
import com.jimuqu.solon.claw.support.MessageSupport;
import com.jimuqu.solon.claw.support.ModelMetadataService;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.SecretValueGuard;
import com.jimuqu.solon.claw.support.constants.LlmConstants;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.noear.snack4.ONode;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgentConfig;
import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActOptions;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.ToolRetryInterceptor;
import org.noear.solon.ai.agent.react.intercept.ToolSanitizerInterceptor;
import org.noear.solon.ai.agent.trace.Metrics;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatRequestDesc;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.Contents;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.dialect.ChatDialectManager;
import org.noear.solon.ai.chat.interceptor.ToolChain;
import org.noear.solon.ai.chat.interceptor.ToolRequest;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.Skill;
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
import org.noear.solon.ai.skills.pdf.PdfSkill;
import org.noear.solon.core.util.RankEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** SolonAiLlmGateway 实现。 */
public class SolonAiLlmGateway implements LlmGateway {
    /** LLM 网关日志器。 */
    private static final Logger log = LoggerFactory.getLogger(SolonAiLlmGateway.class);

    /** CUSTOMDIALECTSREGISTERED的统一常量值。 */
    private static final AtomicBoolean CUSTOM_DIALECTS_REGISTERED = new AtomicBoolean(false);

    /** 注入应用配置，用于SolonAi大模型消息网关。 */
    private final AppConfig appConfig;

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
    private volatile PdfSkill pdfSkill;

    /** 记录SolonAi大模型消息网关中的钩子BridgeInterceptor。 */
    private HookBridgeInterceptor hookBridgeInterceptor;

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
     * 写入钩子Bridge Interceptor。
     *
     * @param hookBridgeInterceptor 钩子BridgeInterceptor标识或键值。
     */
    public void setHookBridgeInterceptor(HookBridgeInterceptor hookBridgeInterceptor) {
        this.hookBridgeInterceptor = hookBridgeInterceptor;
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
     * @param runContext 运行上下文上下文。
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

        for (AppConfig.LlmConfig resolved : candidates) {
            int maxAttempts = 2;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    LlmResult result =
                            executeSingle(
                                    session,
                                    systemPrompt,
                                    userMessage,
                                    toolObjects,
                                    feedbackSink,
                                    eventSink,
                                    resume,
                                    resolved);
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
                        continue;
                    }
                    lastError = new IllegalStateException("LLM returned empty assistant content");
                } catch (Exception e) {
                    lastError = e;
                    LlmErrorClassifier.ClassifiedError classified = LlmErrorClassifier.classify(e);
                    if (classified.isRetryable() && attempt < maxAttempts) {
                        log.warn(
                                "LLM request failed, retrying same provider: provider={}, dialect={}, model={}, attempt={}, message={}",
                                resolved.getProvider(),
                                resolved.getDialect(),
                                resolved.getModel(),
                                attempt,
                                e.getMessage());
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
            primary = false;
        }

        if (lastError instanceof Exception) {
            throw (Exception) lastError;
        }
        throw new IllegalStateException(
                lastError == null ? "LLM execution failed" : lastError.getMessage(), lastError);
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
            AppConfig.LlmConfig resolved)
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
                null);
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
     * @param runContext 运行上下文上下文。
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
        SqliteAgentSession agentSession = new SqliteAgentSession(session, sessionRepository);
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
                runContext);
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
     * @param runContext 运行上下文上下文。
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
            AgentRunContext runContext)
            throws Exception {
        ChatModel chatModel = buildChatModel(resolved);
        UsageCollector usageCollector = new UsageCollector();
        ChatOptions options =
                buildOwnedLoopOptions(
                        toolObjects,
                        userMessage,
                        feedbackSink,
                        runContext,
                        usageCollector);
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
                    ChatRequestDesc requestDesc =
                            buildOwnedLoopRequest(
                                    chatModel,
                                    agentSession,
                                    systemPrompt,
                                    options,
                                    resume || step > 1 ? null : userPrompt(userMessage, runContext, resolved));
                    for (ReActInterceptor interceptor : interceptors) {
                        interceptor.onModelStart(trace, requestDesc);
                    }
                    OwnedModelResponse modelResponse =
                            executeOwnedModelRequest(requestDesc, feedbackSink, eventSink);
                    for (ReActInterceptor interceptor : interceptors) {
                        interceptor.onModelEnd(trace, modelResponse.response);
                    }
                    rawResponse = modelResponse.rawResponse;
                    assistantMessage = modelResponse.assistantMessage;
                    streamed = streamed || modelResponse.streamed;
                    streamedReasoningText =
                            StrUtil.blankToDefault(
                                    modelResponse.reasoningText, streamedReasoningText);
                    if (assistantMessage == null) {
                        assistantMessage = ChatMessage.ofAssistant(rawResponse);
                    }
                    trace.setLastReasonMessage(assistantMessage);
                    for (ReActInterceptor interceptor : interceptors) {
                        interceptor.onReason(trace, assistantMessage);
                    }
                } else {
                    trace.setLastReasonMessage(assistantMessage);
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
                            streamedReasoningText);
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
                            streamedReasoningText);
                }
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
                            interceptors);
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
                                streamedReasoningText);
                    }
                }
                resume = true;
                assistantMessage = null;
            }
        } finally {
            for (ReActInterceptor interceptor : interceptors) {
                interceptor.onAgentEnd(trace);
            }
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
                streamedReasoningText);
    }

    /**
     * 执行自有Loop模型请求，并在可用时转发流式文本事件。
     *
     * @param requestDesc 请求描述。
     * @param feedbackSink feedbackSink参数。
     * @param eventSink eventSink参数。
     * @return 返回模型响应。
     */
    private OwnedModelResponse executeOwnedModelRequest(
            ChatRequestDesc requestDesc,
            ConversationFeedbackSink feedbackSink,
            ConversationEventSink eventSink)
            throws Exception {
        if (eventSink == null || eventSink == ConversationEventSink.noop()) {
            ChatResponse response = requestDesc.call();
            return new OwnedModelResponse(
                    response,
                    response == null ? null : response.getMessage(),
                    response == null ? "" : StrUtil.nullToEmpty(response.getContent()),
                    false,
                    null);
        }

        final StringBuilder emittedText = new StringBuilder();
        final ChatResponse[] finalResponse = new ChatResponse[1];
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
                                            thinkingSplitter,
                                            eventSink,
                                            feedbackSink,
                                            finalResponse,
                                            memoryScrubber))
                    .blockLast();
        } catch (Throwable e) {
            if (e instanceof Exception) {
                throw (Exception) e;
            }
            throw new IllegalStateException("Owned ReAct stream failed", e);
        }

        emitThinking(
                thinkingSplitter.flushPending(),
                emittedText,
                eventSink,
                feedbackSink,
                memoryScrubber);
        String remainingVisible = memoryScrubber.flush();
        if (StrUtil.isNotBlank(remainingVisible)) {
            emittedText.append(remainingVisible);
            eventSink.onAssistantDelta(remainingVisible);
        }

        ChatResponse response = finalResponse[0];
        AssistantMessage assistantMessage = null;
        String rawResponse = emittedText.toString();
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

        String finalText = MemoryContextBoundary.scrubVisibleText(extractText(assistantMessage));
        String emitted = emittedText.toString();
        if (StrUtil.isNotBlank(finalText) && finalText.startsWith(emitted)) {
            String tail =
                    MemoryContextBoundary.scrubVisibleText(finalText.substring(emitted.length()));
            if (StrUtil.isNotBlank(tail)) {
                eventSink.onAssistantDelta(tail);
            }
        } else if (StrUtil.isNotBlank(finalText) && !StrUtil.equals(finalText, emitted)) {
            eventSink.onAssistantDelta(finalText);
        }

        return new OwnedModelResponse(
                response,
                assistantMessage,
                StrUtil.blankToDefault(finalText, rawResponse),
                true,
                thinkingSplitter.reasoningText());
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
     * @param memoryScrubber 记忆边界过滤器。
     */
    private void handleOwnedStreamChunk(
            ChatResponse chunk,
            StringBuilder emittedText,
            ThinkingStreamSplitter thinkingSplitter,
            ConversationEventSink eventSink,
            ConversationFeedbackSink feedbackSink,
            ChatResponse[] finalResponse,
            MemoryContextBoundary.StreamingScrubber memoryScrubber) {
        if (chunk == null) {
            return;
        }
        finalResponse[0] = chunk;
        AssistantMessage message = chunk.getMessage();
        if (message == null || message.isToolCalls()) {
            return;
        }
        String delta = message.getContent();
        if (StrUtil.isBlank(delta)) {
            return;
        }
        emitThinking(
                thinkingSplitter.accept(delta, message.isThinking()),
                emittedText,
                eventSink,
                feedbackSink,
                memoryScrubber);
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
            return lastUnresolvedAssistantToolCall(MessageSupport.loadMessages(session.getNdjson()));
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
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message instanceof ToolMessage) {
                return null;
            }
            if (message instanceof AssistantMessage) {
                AssistantMessage assistant = (AssistantMessage) message;
                List<ToolCall> calls = assistant.getToolCalls();
                return calls == null || calls.isEmpty() ? null : assistant;
            }
            if (message != null
                    && "user".equalsIgnoreCase(StrUtil.nullToEmpty(String.valueOf(message.getRole())))) {
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
                    && "user".equalsIgnoreCase(StrUtil.nullToEmpty(String.valueOf(message.getRole())))) {
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
                prompt == null ? chatModel.prompt(agentSession) : chatModel.prompt(prompt);
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
            SqliteAgentSession agentSession, AssistantMessage assistantMessage, ChatOptions options) {
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
    private ToolCall parseOwnedTextActionCall(AssistantMessage assistantMessage, ChatOptions options) {
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

        ParsedTextAction parsed = parseOwnedJsonAction(actionText);
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
        String argumentsJson = ONode.serialize(arguments);
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
            if (index == 0 || content.charAt(index - 1) == '\n' || content.charAt(index - 1) == '\r') {
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
            Map<String, Object> arguments = toArgumentsMap(firstPresent(map, "arguments", "args", "input"));
            return new ParsedTextAction(name, arguments);
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
        Map<String, Object> arguments = parseOwnedActionInput(content);
        return new ParsedTextAction(name, arguments);
    }

    /**
     * 解析 Action Input 参数。
     *
     * @param content 完整内容。
     * @return 返回参数。
     */
    private Map<String, Object> parseOwnedActionInput(String content) {
        int inputIndex = content.indexOf("Action Input:");
        int labelLength = "Action Input:".length();
        if (inputIndex < 0) {
            inputIndex = content.indexOf("ActionInput:");
            labelLength = "ActionInput:".length();
        }
        if (inputIndex < 0) {
            return new LinkedHashMap<String, Object>();
        }
        String input = content.substring(inputIndex + labelLength).trim();
        String json = extractFirstJsonObject(input);
        if (StrUtil.isBlank(json)) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            return toArgumentsMap(ONode.deserialize(json, Object.class));
        } catch (Throwable e) {
            return new LinkedHashMap<String, Object>();
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
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (value instanceof Map) {
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
            } catch (Throwable ignored) {
                result.put("input", value);
                return result;
            }
        }
        if (value != null) {
            result.put("input", value);
        }
        return result;
    }

    /**
     * 复制assistant消息并设置工具调用。
     *
     * @param source 原消息。
     * @param calls 工具调用。
     * @return 返回复制消息。
     */
    private AssistantMessage copyAssistantWithToolCalls(AssistantMessage source, List<ToolCall> calls) {
        AssistantMessage replacement =
                new AssistantMessage(
                        source == null ? "" : source.getContent(),
                        source != null && source.isThinking(),
                        source == null ? null : source.getContentRaw(),
                        Collections.<Map>emptyList(),
                        calls,
                        source == null ? Collections.<Map>emptyList() : source.getSearchResultsRaw());
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
     * @param runContext 运行上下文上下文。
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
        options.interceptorAdd(new ToolSanitizerInterceptor());
        if (dangerousCommandApprovalService != null) {
            options.interceptorAdd(dangerousCommandApprovalService.buildInterceptor());
        }
        options.interceptorAdd(toolCallLoopGuardrailService.buildInterceptor());
        options.interceptorAdd(toolResultTransformService.buildInterceptor());
        ToolResultStorageService toolResultStorageService =
                new ToolResultStorageService(appConfig, resolveWorkspace(runContext));
        options.interceptorAdd(
                new ToolResultStorageInterceptor(
                        toolResultStorageService,
                        runContext == null ? null : runContext.getRunId()));
        if (feedbackSink != null && feedbackSink != ConversationFeedbackSink.noop()) {
            options.interceptorAdd(new FeedbackInterceptor(feedbackSink, dangerousCommandApprovalService));
        }
        if (runContext != null) {
            options.interceptorAdd(
                    new TracingReActInterceptor(
                            runContext,
                            appConfig.getTrace().getToolPreviewLength(),
                            appConfig.getTask().getToolOutputInlineLimit()));
        }
        if (usageCollector != null) {
            options.interceptorAdd(new UsageCollectingInterceptor(usageCollector));
        }
        if (hookBridgeInterceptor != null) {
            options.interceptorAdd(hookBridgeInterceptor);
        }
        if (toolObjects != null) {
            for (Object toolObject : toolObjects) {
                addOwnedLoopTool(options, sanitizeToolObject(toolObject), Prompt.of(StrUtil.nullToEmpty(userMessage)));
            }
        }
        addOwnedLoopTool(options, sanitizeToolObject(pdfSkill()), Prompt.of(StrUtil.nullToEmpty(userMessage)));
        return options;
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
        } else if (toolObject instanceof Skill) {
            Skill skill = (Skill) toolObject;
            if (skill.isSupported(prompt)) {
                skill.onAttach(prompt);
                options.toolAdd(skill.getTools(prompt));
                String instruction = skill.getInstruction(prompt);
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
     * @param runContext 运行上下文上下文。
     * @param interceptors 拦截器集合。
     */
    private void executeOwnedToolCall(
            OwnedReActTrace trace,
            ChatOptions options,
            ToolCall call,
            ConversationFeedbackSink feedbackSink,
            ConversationEventSink eventSink,
            AgentRunContext runContext,
            List<ReActInterceptor> interceptors)
            throws Exception {
        String toolName = call.getName();
        Map<String, Object> args =
                call.getArguments() == null
                        ? Collections.<String, Object>emptyMap()
                        : call.getArguments();
        FunctionTool tool = options.tool(toolName);
        long startedAt = System.currentTimeMillis();
        trace.setLastObservation(null);
        for (ReActInterceptor interceptor : interceptors) {
            interceptor.onAction(trace, toolName, args);
        }
        if (trace.getSession().isPending() || isTraceEnded(trace)) {
            trace.getSession().updateSnapshot();
            return;
        }
        if (StrUtil.isNotBlank(trace.getLastObservation())) {
            String skipped = trace.getLastObservation();
            long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
            for (ReActInterceptor interceptor : interceptors) {
                interceptor.onObservation(trace, toolName, skipped, durationMs);
            }
            appendOwnedToolMessage(
                    trace,
                    StrUtil.blankToDefault(trace.getLastObservation(), skipped),
                    toolName,
                    call,
                    false);
            trace.incrementToolCallCount();
            return;
        }
        if (tool == null) {
            String missing = "Tool call not found: " + toolName;
            trace.setLastObservation(missing);
            appendOwnedToolMessage(trace, missing, toolName, call, false);
            return;
        }
        if (eventSink != null) {
            eventSink.onToolStarted(toolName, args);
        }

        ToolResult toolResult;
        try {
            ToolRequest toolRequest = new ToolRequest(null, options.toolContext(), args);
            ToolChain<ReActInterceptor> chain =
                    new ToolChain<ReActInterceptor>(rankedInterceptors(interceptors), tool);
            toolResult = chain.doIntercept(toolRequest);
        } catch (Throwable e) {
            toolResult = ToolResult.error("Execution error in tool [" + toolName + "]: " + safeError(e));
        }
        long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
        String observation = toolResult == null ? "" : StrUtil.nullToEmpty(toolResult.getContent());
        trace.setLastObservation(observation);
        for (ReActInterceptor interceptor : interceptors) {
            interceptor.onObservation(trace, toolName, observation, durationMs);
        }
        String finalObservation = StrUtil.blankToDefault(trace.getLastObservation(), observation);
        appendOwnedToolMessage(trace, finalObservation, toolName, call, tool.returnDirect());
        trace.incrementToolCallCount();
        if (eventSink != null) {
            eventSink.onToolCompleted(toolName, finalObservation, durationMs);
        }
    }

    /**
     * 追加自有Loop工具消息。
     *
     * @param trace trace参数。
     * @param observation 观察结果。
     * @param toolName 工具名。
     * @param call 工具调用。
     * @param returnDirect 是否直接返回。
     */
    private void appendOwnedToolMessage(
            OwnedReActTrace trace,
            String observation,
            String toolName,
            ToolCall call,
            boolean returnDirect) {
        trace.getSession()
                .addMessage(
                        ChatMessage.ofTool(
                                ToolResult.success(StrUtil.nullToEmpty(observation)),
                                toolName,
                                StrUtil.blankToDefault(call == null ? null : call.getId(), toolName),
                                returnDirect));
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
     * @param reasoningText 流式解析出的推理文本。
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
            String reasoningText)
            throws Exception {
        LlmResult result = new LlmResult();
        result.setAssistantMessage(assistantMessage);
        result.setNdjson(ChatMessage.toNdjson(agentSession.getMessages()));
        result.setStreamed(streamed);
        result.setRawResponse(rawResponse);
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
    private List<RankEntity<ReActInterceptor>> rankedInterceptors(List<ReActInterceptor> interceptors) {
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
                emittedText.append(visible);
                eventSink.onAssistantDelta(visible);
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
        return StrUtil.isNotBlank(extractText(assistantMessage)) || StrUtil.isNotBlank(rawResponse);
    }

    /**
     * 提取Text。
     *
     * @param assistantMessage assistant消息参数。
     * @return 返回Text结果。
     */
    private String extractText(AssistantMessage assistantMessage) {
        if (assistantMessage == null) {
            return "";
        }
        if (StrUtil.isNotBlank(assistantMessage.getResultContent())) {
            return assistantMessage.getResultContent().trim();
        }
        if (StrUtil.isNotBlank(assistantMessage.getContent())) {
            return assistantMessage.getContent().trim();
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

    /** 将流式 <think>...</think> 内容拆成 reasoning 和可见答复。 */
    private static class ThinkingStreamSplitter {
        /** THINKOPEN的统一常量值。 */
        private static final String THINK_OPEN = "<think>";

        /** THINK关闭的统一常量值。 */
        private static final String THINK_CLOSE = "</think>";

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
                reasoning.append(text);
                return new Delta("", text);
            }

            StringBuilder visibleDelta = new StringBuilder();
            StringBuilder reasoningDelta = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (pendingTag.length() > 0 || ch == '<') {
                    pendingTag.append(ch);
                    String pending = pendingTag.toString();
                    if (THINK_OPEN.equals(pending)) {
                        thinking = true;
                        pendingTag.setLength(0);
                        continue;
                    }
                    if (THINK_CLOSE.equals(pending)) {
                        thinking = false;
                        pendingTag.setLength(0);
                        continue;
                    }
                    if (THINK_OPEN.startsWith(pending) || THINK_CLOSE.startsWith(pending)) {
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
        SecurityPolicyService.UrlVerdict apiUrlVerdict =
                LlmConstants.PROVIDER_OLLAMA.equals(dialect)
                        ? securityPolicyService.checkUrlAllowingPrivate(resolved.getApiUrl())
                        : securityPolicyService.checkUrl(resolved.getApiUrl());
        if (!apiUrlVerdict.isAllowed()) {
            throw new IllegalStateException("LLM apiUrl 被安全策略阻断：" + apiUrlVerdict.getMessage());
        }
        if (StrUtil.isBlank(resolved.getModel())) {
            throw new IllegalStateException("LLM model 不能为空。");
        }
        if (LlmConstants.PROVIDER_OPENAI_RESPONSES.equals(dialect)
                && !StrUtil.containsIgnoreCase(resolved.getApiUrl(), "/responses")) {
            throw new IllegalStateException("openai-responses 的 apiUrl 必须直接指向 /responses 接口。");
        }
        if (!LlmConstants.PROVIDER_OLLAMA.equals(dialect)
                && SecretValueGuard.isPlaceholderSecret(resolved.getApiKey())) {
            throw new IllegalStateException("LLM apiKey 不能使用示例或占位符密钥。");
        }
    }

    /**
     * 执行用户提示词相关逻辑。
     *
     * @param userMessage 用户消息参数。
     * @param runContext 运行上下文上下文。
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
     * @param runContext 运行上下文上下文。
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

        chatConfig.getModelOptions().temperature(resolved.getTemperature());
        chatConfig.getModelOptions().max_tokens(resolved.getMaxTokens());
        String reasoningEffort =
                session != null && StrUtil.isNotBlank(session.getReasoningEffortOverride())
                        ? session.getReasoningEffortOverride().trim()
                        : resolved.getReasoningEffort();
        if (LlmConstants.PROVIDER_OPENAI_RESPONSES.equals(dialect)
                && StrUtil.isNotBlank(reasoningEffort)
                && !"none".equalsIgnoreCase(reasoningEffort.trim())) {
            chatConfig
                    .getModelOptions()
                    .optionSet(
                            "reasoning",
                            Collections.<String, Object>singletonMap(
                                    "effort", reasoningEffort.trim()));
        }
        if (session != null
                && "priority"
                        .equalsIgnoreCase(
                                StrUtil.nullToEmpty(session.getServiceTierOverride()).trim())) {
            chatConfig.getModelOptions().optionSet("service_tier", "priority");
        }

        return chatConfig;
    }

    /**
     * 构建Chat模型。
     *
     * @param resolved resolved 参数。
     * @return 返回创建好的Chat模型。
     */
    protected ChatModel buildChatModel(AppConfig.LlmConfig resolved) {
        return buildChatConfig(resolved).toChatModel();
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
        if (toolObject instanceof Skill) {
            final Skill skill = (Skill) toolObject;
            return new Skill() {
                /**
                 * 执行名称相关逻辑。
                 *
                 * @return 返回名称结果。
                 */
                @Override
                public String name() {
                    return skill.name();
                }

                /**
                 * 执行description相关逻辑。
                 *
                 * @return 返回description结果。
                 */
                @Override
                public String description() {
                    return skill.description();
                }

                /**
                 * 执行元数据相关逻辑。
                 *
                 * @return 返回元数据结果。
                 */
                @Override
                public org.noear.solon.ai.chat.skill.SkillMetadata metadata() {
                    return skill.metadata();
                }

                /**
                 * 判断是否Supported。
                 *
                 * @param prompt 提示词参数。
                 * @return 如果Supported满足条件则返回 true，否则返回 false。
                 */
                @Override
                public boolean isSupported(Prompt prompt) {
                    return skill.isSupported(prompt);
                }

                /**
                 * 响应Attach事件。
                 *
                 * @param prompt 提示词参数。
                 */
                @Override
                public void onAttach(Prompt prompt) {
                    skill.onAttach(prompt);
                }

                /**
                 * 读取Instruction。
                 *
                 * @param prompt 提示词参数。
                 * @return 返回读取到的Instruction。
                 */
                @Override
                public String getInstruction(Prompt prompt) {
                    return skill.getInstruction(prompt);
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
                    java.util.Collection<FunctionTool> tools = skill.getTools(prompt);
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
     * @param runContext 运行上下文上下文。
     * @return 返回解析后的工作区。
     */
    private String resolveWorkspace(AgentRunContext runContext) {
        if (runContext != null && StrUtil.isNotBlank(runContext.getWorkspaceDir())) {
            return runContext.getWorkspaceDir();
        }
        return appConfig.getRuntime().getHome();
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
            super(prompt == null ? Prompt.of("") : prompt);
            ReActAgentConfig config = new ReActAgentConfig(chatModel);
            OwnedReActOptions reactOptions = new OwnedReActOptions(chatModel);
            if (interceptors != null) {
                for (ReActInterceptor interceptor : interceptors) {
                    if (interceptor != null) {
                        reactOptions.getModelOptions().interceptorAdd(interceptor);
                    }
                }
            }
            prepare(config, reactOptions, session, null);
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

        /**
         * 创建自有Loop模型响应。
         *
         * @param response 模型响应。
         * @param assistantMessage assistant消息。
         * @param rawResponse 原始响应文本。
         * @param streamed 是否流式。
         * @param reasoningText 推理文本。
         */
        private OwnedModelResponse(
                ChatResponse response,
                AssistantMessage assistantMessage,
                String rawResponse,
                boolean streamed,
                String reasoningText) {
            this.response = response;
            this.assistantMessage = assistantMessage;
            this.rawResponse = StrUtil.nullToEmpty(rawResponse);
            this.streamed = streamed;
            this.reasoningText = reasoningText;
        }
    }

    /** 文本 Action 解析结果。 */
    private static class ParsedTextAction {
        /** 工具名。 */
        private final String name;
        /** 工具参数。 */
        private final Map<String, Object> arguments;

        /**
         * 创建文本 Action 解析结果。
         *
         * @param name 工具名。
         * @param arguments 工具参数。
         */
        private ParsedTextAction(String name, Map<String, Object> arguments) {
            this.name = name;
            this.arguments = arguments;
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

    /** 懒加载 PDF 技能，统一复用 runtime/cache/pdf 目录。 */
    PdfSkill pdfSkill() {
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
    private PdfSkill buildPdfSkill() {
        File pdfWorkDir = new File(appConfig.getRuntime().getCacheDir(), "pdf");
        if (!pdfWorkDir.exists() && !pdfWorkDir.mkdirs()) {
            log.warn("Failed to create pdf work directory: {}", safePathRef(pdfWorkDir));
        }

        final File fontFile = resolvePdfFontFile();
        if (fontFile != null) {
            log.info("PDF skill font detected: {}", safePathRef(fontFile));
            return new PdfSkill(
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
        return new PdfSkill(pdfWorkDir.getAbsolutePath());
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
                AppConfig.LlmConfig resolved = buildCandidateConfigs(null).get(0);
                validate(resolved);
                ChatModel chatModel = buildChatModel(resolved);
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
                ChatResponse response =
                        chatModel
                                .prompt(
                                        ChatMessage.ofSystem(
                                                "You are a strict command risk classifier."),
                                        ChatMessage.ofUser(prompt))
                                .call();
                return parseSmartApprovalResponse(response == null ? null : response.getContent());
            } catch (Exception e) {
                log.warn(
                        "Smart approval judge failed, escalating to manual approval: {}",
                        e.getMessage());
                return SmartApprovalDecision.escalate("smart approval failed");
            }
        }
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
        } catch (Exception ignored) {
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
         */
        @Override
        public void onThought(ReActTrace trace, String thought) {
            feedbackSink.onReasoning(thought);
        }

        /**
         * 响应Action事件。
         *
         * @param trace trace 参数。
         * @param toolName 工具名称。
         * @param args 工具或命令参数。
         */
        @Override
        public void onAction(ReActTrace trace, String toolName, Map<String, Object> args) {
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
            feedbackSink.onToolStarted(toolName, args);
        }

        /**
         * 响应观察结果事件。
         *
         * @param trace trace 参数。
         * @param toolName 工具名称。
         * @param result 结果响应或执行结果。
         * @param durationMs durationMs 参数。
         */
        @Override
        public void onObservation(
                ReActTrace trace, String toolName, String result, long durationMs) {
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
         * @param trace trace 参数。
         * @param resp resp 参数。
         */
        @Override
        public void onModelEnd(ReActTrace trace, ChatResponse resp) {
            if (resp != null) {
                usageCollector.add(resp.getUsage());
            }
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

        /** 保存active工具Calls映射，便于按键快速查询。 */
        private final ConcurrentMap<String, ToolCallRecord> activeToolCalls =
                new ConcurrentHashMap<String, ToolCallRecord>();

        /**
         * 创建Tracing Re Act Interceptor实例，并注入运行所需依赖。
         *
         * @param runContext 运行上下文上下文。
         * @param previewLength 预览Length参数。
         * @param inlineLimitBytes 内联Limit字节参数。
         */
        private TracingReActInterceptor(
                AgentRunContext runContext, int previewLength, int inlineLimitBytes) {
            this.runContext = runContext;
            this.previewLength = Math.max(200, previewLength);
            this.inlineLimitBytes = Math.max(256, inlineLimitBytes);
        }

        /**
         * 响应模型Start事件。
         *
         * @param trace trace 参数。
         * @param req req 参数。
         */
        @Override
        public void onModelStart(ReActTrace trace, ChatRequestDesc req) {
            runContext.setPhase("model");
            runContext.event("model.start", "开始请求模型");
        }

        /**
         * 响应模型End事件。
         *
         * @param trace trace 参数。
         * @param resp resp 参数。
         */
        @Override
        public void onModelEnd(ReActTrace trace, ChatResponse resp) {
            runContext.setPhase("model");
            runContext.event("model.end", "模型响应完成");
        }

        /**
         * 响应Action事件。
         *
         * @param trace trace 参数。
         * @param toolName 工具名称。
         * @param args 工具或命令参数。
         */
        @Override
        public void onAction(ReActTrace trace, String toolName, Map<String, Object> args) {
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
            record.setSideEffecting(isSideEffectingTool(toolName));
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
         * @param toolName 工具名称。
         * @param result 结果响应或执行结果。
         * @param durationMs durationMs 参数。
         */
        @Override
        public void onObservation(
                ReActTrace trace, String toolName, String result, long durationMs) {
            runContext.setPhase("tool");
            Map<String, Object> metadata = new java.util.LinkedHashMap<String, Object>();
            metadata.put("tool", toolName);
            metadata.put("durationMs", durationMs);
            String observation = trace == null ? result : trace.getLastObservation();
            ToolResultStorageService.StoredResult output =
                    ToolResultStorageService.describeObservation(
                            StrUtil.blankToDefault(observation, result));
            metadata.put("preview", output.getPreview());
            metadata.put("result_ref", output.getResultRef());
            runContext.event("tool.end", "工具完成：" + toolName + "（" + durationMs + "ms）", metadata);
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
                record.setSideEffecting(isSideEffectingTool(toolName));
                record.setReadOnly(!record.isSideEffecting());
                record.setResultIndexable(true);
                record.setOutputLimitBytes(inlineLimitBytes);
                record.setExecutionPolicy(
                        record.isSideEffecting() ? "serial" : "parallel_readonly");
            }
            record.setStatus("completed");
            record.setResultPreview(output.getPreview());
            record.setResultRef(output.getResultRef());
            record.setResultSizeBytes(output.getSizeBytes());
            record.setFinishedAt(System.currentTimeMillis());
            record.setDurationMs(durationMs);
            runContext.saveToolCall(record);
        }

        /**
         * 判断是否Side Effecting工具。
         *
         * @param toolName 工具名称。
         * @return 如果Side Effecting工具满足条件则返回 true，否则返回 false。
         */
        private boolean isSideEffectingTool(String toolName) {
            if (toolName == null) {
                return false;
            }
            String value = toolName.toLowerCase(java.util.Locale.ROOT);
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
    }
}
