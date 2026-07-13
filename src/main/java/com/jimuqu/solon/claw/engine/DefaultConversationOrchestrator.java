package com.jimuqu.solon.claw.engine;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.agent.AgentRuntimePolicy;
import com.jimuqu.solon.claw.agent.AgentRuntimeScope;
import com.jimuqu.solon.claw.agent.AgentRuntimeService;
import com.jimuqu.solon.claw.context.MemoryContextBoundary;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.AgentRunOutcome;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.MemoryTurnContext;
import com.jimuqu.solon.claw.core.model.RunBusyDecision;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.ContextCompressionService;
import com.jimuqu.solon.claw.core.service.ContextService;
import com.jimuqu.solon.claw.core.service.ConversationEventSink;
import com.jimuqu.solon.claw.core.service.ConversationOrchestrator;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.core.service.LlmGateway;
import com.jimuqu.solon.claw.core.service.MemoryManager;
import com.jimuqu.solon.claw.core.service.ToolRegistry;
import com.jimuqu.solon.claw.gateway.feedback.ConversationFeedbackSink;
import com.jimuqu.solon.claw.gateway.feedback.GatewayConversationFeedbackSink;
import com.jimuqu.solon.claw.goal.GoalDecision;
import com.jimuqu.solon.claw.goal.GoalService;
import com.jimuqu.solon.claw.media.SpeechService;
import com.jimuqu.solon.claw.plugin.AgentHookName;
import com.jimuqu.solon.claw.plugin.AgentHookRegistry;
import com.jimuqu.solon.claw.profile.ProfileRuntimeScope;
import com.jimuqu.solon.claw.storage.session.SqliteAgentSession;
import com.jimuqu.solon.claw.support.DisplaySettingsService;
import com.jimuqu.solon.claw.support.MessageAttachmentSupport;
import com.jimuqu.solon.claw.support.MessageSupport;
import com.jimuqu.solon.claw.support.RuntimeFooterService;
import com.jimuqu.solon.claw.support.RuntimeSettingsService;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.SourceKeySupport;
import com.jimuqu.solon.claw.support.constants.CompressionConstants;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import com.jimuqu.solon.claw.tool.runtime.MessageDeliveryTracker;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** DefaultConversationOrchestrator 实现。 */
public class DefaultConversationOrchestrator implements ConversationOrchestrator {
    /** 日志的统一常量值。 */
    private static final Logger log =
            LoggerFactory.getLogger(DefaultConversationOrchestrator.class);

    /** 保存会话仓储依赖，用于访问持久化数据。 */
    private final SessionRepository sessionRepository;

    /** 注入上下文服务，用于调用对应业务能力。 */
    private final ContextService contextService;

    /** 注入上下文压缩服务，用于调用对应业务能力。 */
    private final ContextCompressionService contextCompressionService;

    /** 记录默认对话编排器中的大模型消息网关。 */
    private final LlmGateway llmGateway;

    /** 记录默认对话编排器中的工具注册表。 */
    private final ToolRegistry toolRegistry;

    /** 注入投递服务，用于调用对应业务能力。 */
    private final DeliveryService deliveryService;

    /** 保存展示设置服务集合，维持调用顺序或去重语义。 */
    private final DisplaySettingsService displaySettingsService;

    /** 保存运行时设置服务集合，维持调用顺序或去重语义。 */
    private final RuntimeSettingsService runtimeSettingsService;

    /** 注入dangerous命令审批服务，用于调用对应业务能力。 */
    private final DangerousCommandApprovalService dangerousCommandApprovalService;

    /** 记录默认对话编排器中的Agent运行Supervisor。 */
    private final AgentRunSupervisor agentRunSupervisor;

    /** 注入运行时Footer服务，用于调用对应业务能力。 */
    private final RuntimeFooterService runtimeFooterService;

    /** 注入Agent运行时服务，用于调用对应业务能力。 */
    private final AgentRuntimeService agentRuntimeService;

    /** 记录默认对话编排器中的记忆管理器。 */
    private final MemoryManager memoryManager;

    /** 注入目标服务，用于调用对应业务能力。 */
    private final GoalService goalService;

    /** 注入语音服务，用于调用对应业务能力。 */
    private final SpeechService speechService;

    /** 保存来源Locks映射，便于按键快速查询。 */
    private final ConcurrentMap<String, Object> sourceLocks =
            new ConcurrentHashMap<String, Object>();

    /** 记录默认对话编排器中的钩子注册表。 */
    private AgentHookRegistry hookRegistry;

    /**
     * 创建默认对话编排器实例，并注入运行所需依赖。
     *
     * @param sessionRepository 会话仓储依赖。
     * @param contextService 上下文Service上下文。
     * @param contextCompressionService 上下文CompressionService上下文。
     * @param llmGateway LLM网关参数。
     * @param toolRegistry 工具注册表依赖组件。
     * @param deliveryService 投递服务依赖。
     * @param displaySettingsService 展示Settings服务依赖。
     * @param runtimeSettingsService 运行时Settings服务依赖。
     * @param dangerousCommandApprovalService dangerous命令审批服务依赖。
     * @param agentRunSupervisor Agent运行Supervisor参数。
     * @param runtimeFooterService 运行时Footer服务依赖。
     */
    public DefaultConversationOrchestrator(
            SessionRepository sessionRepository,
            ContextService contextService,
            ContextCompressionService contextCompressionService,
            LlmGateway llmGateway,
            ToolRegistry toolRegistry,
            DeliveryService deliveryService,
            DisplaySettingsService displaySettingsService,
            RuntimeSettingsService runtimeSettingsService,
            DangerousCommandApprovalService dangerousCommandApprovalService,
            AgentRunSupervisor agentRunSupervisor,
            RuntimeFooterService runtimeFooterService) {
        this(
                sessionRepository,
                contextService,
                contextCompressionService,
                llmGateway,
                toolRegistry,
                deliveryService,
                displaySettingsService,
                runtimeSettingsService,
                dangerousCommandApprovalService,
                agentRunSupervisor,
                runtimeFooterService,
                null,
                null,
                null,
                null);
    }

    /**
     * 创建默认对话编排器实例，并注入运行所需依赖。
     *
     * @param sessionRepository 会话仓储依赖。
     * @param contextService 上下文Service上下文。
     * @param contextCompressionService 上下文CompressionService上下文。
     * @param llmGateway LLM网关参数。
     * @param toolRegistry 工具注册表依赖组件。
     * @param deliveryService 投递服务依赖。
     * @param displaySettingsService 展示Settings服务依赖。
     * @param runtimeSettingsService 运行时Settings服务依赖。
     * @param dangerousCommandApprovalService dangerous命令审批服务依赖。
     * @param agentRunSupervisor Agent运行Supervisor参数。
     * @param runtimeFooterService 运行时Footer服务依赖。
     * @param agentRuntimeService Agent运行时服务依赖。
     */
    public DefaultConversationOrchestrator(
            SessionRepository sessionRepository,
            ContextService contextService,
            ContextCompressionService contextCompressionService,
            LlmGateway llmGateway,
            ToolRegistry toolRegistry,
            DeliveryService deliveryService,
            DisplaySettingsService displaySettingsService,
            RuntimeSettingsService runtimeSettingsService,
            DangerousCommandApprovalService dangerousCommandApprovalService,
            AgentRunSupervisor agentRunSupervisor,
            RuntimeFooterService runtimeFooterService,
            AgentRuntimeService agentRuntimeService) {
        this(
                sessionRepository,
                contextService,
                contextCompressionService,
                llmGateway,
                toolRegistry,
                deliveryService,
                displaySettingsService,
                runtimeSettingsService,
                dangerousCommandApprovalService,
                agentRunSupervisor,
                runtimeFooterService,
                agentRuntimeService,
                null,
                null,
                null);
    }

    /**
     * 创建默认对话编排器实例，并注入运行所需依赖。
     *
     * @param sessionRepository 会话仓储依赖。
     * @param contextService 上下文Service上下文。
     * @param contextCompressionService 上下文CompressionService上下文。
     * @param llmGateway LLM网关参数。
     * @param toolRegistry 工具注册表依赖组件。
     * @param deliveryService 投递服务依赖。
     * @param displaySettingsService 展示Settings服务依赖。
     * @param runtimeSettingsService 运行时Settings服务依赖。
     * @param dangerousCommandApprovalService dangerous命令审批服务依赖。
     * @param agentRunSupervisor Agent运行Supervisor参数。
     * @param runtimeFooterService 运行时Footer服务依赖。
     * @param agentRuntimeService Agent运行时服务依赖。
     * @param memoryManager 记忆Manager参数。
     */
    public DefaultConversationOrchestrator(
            SessionRepository sessionRepository,
            ContextService contextService,
            ContextCompressionService contextCompressionService,
            LlmGateway llmGateway,
            ToolRegistry toolRegistry,
            DeliveryService deliveryService,
            DisplaySettingsService displaySettingsService,
            RuntimeSettingsService runtimeSettingsService,
            DangerousCommandApprovalService dangerousCommandApprovalService,
            AgentRunSupervisor agentRunSupervisor,
            RuntimeFooterService runtimeFooterService,
            AgentRuntimeService agentRuntimeService,
            MemoryManager memoryManager) {
        this(
                sessionRepository,
                contextService,
                contextCompressionService,
                llmGateway,
                toolRegistry,
                deliveryService,
                displaySettingsService,
                runtimeSettingsService,
                dangerousCommandApprovalService,
                agentRunSupervisor,
                runtimeFooterService,
                agentRuntimeService,
                memoryManager,
                null,
                null);
    }

    /**
     * 创建默认对话编排器实例，并注入运行所需依赖。
     *
     * @param sessionRepository 会话仓储依赖。
     * @param contextService 上下文Service上下文。
     * @param contextCompressionService 上下文CompressionService上下文。
     * @param llmGateway LLM网关参数。
     * @param toolRegistry 工具注册表依赖组件。
     * @param deliveryService 投递服务依赖。
     * @param displaySettingsService 展示Settings服务依赖。
     * @param runtimeSettingsService 运行时Settings服务依赖。
     * @param dangerousCommandApprovalService dangerous命令审批服务依赖。
     * @param agentRunSupervisor Agent运行Supervisor参数。
     * @param runtimeFooterService 运行时Footer服务依赖。
     * @param agentRuntimeService Agent运行时服务依赖。
     * @param memoryManager 记忆Manager参数。
     * @param goalService 目标服务依赖。
     */
    public DefaultConversationOrchestrator(
            SessionRepository sessionRepository,
            ContextService contextService,
            ContextCompressionService contextCompressionService,
            LlmGateway llmGateway,
            ToolRegistry toolRegistry,
            DeliveryService deliveryService,
            DisplaySettingsService displaySettingsService,
            RuntimeSettingsService runtimeSettingsService,
            DangerousCommandApprovalService dangerousCommandApprovalService,
            AgentRunSupervisor agentRunSupervisor,
            RuntimeFooterService runtimeFooterService,
            AgentRuntimeService agentRuntimeService,
            MemoryManager memoryManager,
            GoalService goalService) {
        this(
                sessionRepository,
                contextService,
                contextCompressionService,
                llmGateway,
                toolRegistry,
                deliveryService,
                displaySettingsService,
                runtimeSettingsService,
                dangerousCommandApprovalService,
                agentRunSupervisor,
                runtimeFooterService,
                agentRuntimeService,
                memoryManager,
                goalService,
                null);
    }

    /**
     * 创建默认对话编排器实例，并注入运行所需依赖。
     *
     * @param sessionRepository 会话仓储依赖。
     * @param contextService 上下文Service上下文。
     * @param contextCompressionService 上下文CompressionService上下文。
     * @param llmGateway LLM网关参数。
     * @param toolRegistry 工具注册表依赖组件。
     * @param deliveryService 投递服务依赖。
     * @param displaySettingsService 展示Settings服务依赖。
     * @param runtimeSettingsService 运行时Settings服务依赖。
     * @param dangerousCommandApprovalService dangerous命令审批服务依赖。
     * @param agentRunSupervisor Agent运行Supervisor参数。
     * @param runtimeFooterService 运行时Footer服务依赖。
     * @param agentRuntimeService Agent运行时服务依赖。
     * @param memoryManager 记忆Manager参数。
     * @param goalService 目标服务依赖。
     * @param speechService 语音服务依赖。
     */
    public DefaultConversationOrchestrator(
            SessionRepository sessionRepository,
            ContextService contextService,
            ContextCompressionService contextCompressionService,
            LlmGateway llmGateway,
            ToolRegistry toolRegistry,
            DeliveryService deliveryService,
            DisplaySettingsService displaySettingsService,
            RuntimeSettingsService runtimeSettingsService,
            DangerousCommandApprovalService dangerousCommandApprovalService,
            AgentRunSupervisor agentRunSupervisor,
            RuntimeFooterService runtimeFooterService,
            AgentRuntimeService agentRuntimeService,
            MemoryManager memoryManager,
            GoalService goalService,
            SpeechService speechService) {
        this.sessionRepository = sessionRepository;
        this.contextService = contextService;
        this.contextCompressionService = contextCompressionService;
        this.llmGateway = llmGateway;
        this.toolRegistry = toolRegistry;
        this.deliveryService = deliveryService;
        this.displaySettingsService = displaySettingsService;
        this.runtimeSettingsService = runtimeSettingsService;
        this.dangerousCommandApprovalService = dangerousCommandApprovalService;
        this.agentRunSupervisor = agentRunSupervisor;
        this.runtimeFooterService = runtimeFooterService;
        this.agentRuntimeService = agentRuntimeService;
        this.memoryManager = memoryManager;
        this.goalService = goalService;
        this.speechService = speechService;
    }

    /**
     * 执行入站消息相关逻辑。
     *
     * @param message 平台消息或错误消息。
     * @return 返回Incoming结果。
     */
    public GatewayReply handleIncoming(GatewayMessage message) throws Exception {
        return handleIncoming(message, ConversationEventSink.noop());
    }

    /**
     * 执行入站消息相关逻辑。
     *
     * @param message 平台消息或错误消息。
     * @param eventSink 事件Sink参数。
     * @return 返回Incoming结果。
     */
    public GatewayReply handleIncoming(GatewayMessage message, ConversationEventSink eventSink)
            throws Exception {
        String sourceKey = message.sourceKey();
        SessionRecord session;
        synchronized (lockFor(sourceKey)) {
            session = sessionRepository.getBoundSession(sourceKey);
            if (session == null) {
                session = sessionRepository.bindNewSession(sourceKey);
            }
        }
        RunBusyDecision decision =
                agentRunSupervisor.coordinateIncoming(sourceKey, session.getSessionId(), message);
        if (!decision.isShouldRunNow()) {
            GatewayReply reply =
                    GatewayReply.ok(
                            StrUtil.blankToDefault(decision.getMessage(), decision.getStatus()));
            reply.setSessionId(session.getSessionId());
            reply.setBranchName(session.getBranchName());
            reply.getRuntimeMetadata().put("busy_policy", decision.getPolicy());
            reply.getRuntimeMetadata().put("busy_status", decision.getStatus());
            if (StrUtil.isNotBlank(decision.getRunId())) {
                reply.getRuntimeMetadata().put("run_id", decision.getRunId());
            }
            if (StrUtil.isNotBlank(decision.getQueueId())) {
                reply.getRuntimeMetadata().put("queue_id", decision.getQueueId());
            }
            if (decision.isRejected()) {
                reply.setError(true);
            }
            return reply;
        }
        try {
            return runOnSession(session, message, eventSink);
        } finally {
            if (agentRunSupervisor.releaseIncomingReservation(sourceKey)) {
                agentRunSupervisor.onRunFinished(
                        sourceKey,
                        session.getSessionId(),
                        queued -> {
                            try {
                                return handleIncoming(queued, eventSink);
                            } catch (Exception e) {
                                throw new IllegalStateException(e);
                            }
                        });
            }
        }
    }

    /**
     * 运行Scheduled。
     *
     * @param syntheticMessage synthetic消息参数。
     * @return 返回Scheduled结果。
     */
    public GatewayReply runScheduled(GatewayMessage syntheticMessage) throws Exception {
        return runScheduled(syntheticMessage, ConversationEventSink.noop());
    }

    /**
     * 运行Scheduled。
     *
     * @param syntheticMessage synthetic消息参数。
     * @param eventSink 事件Sink参数。
     * @return 返回Scheduled结果。
     */
    public GatewayReply runScheduled(
            GatewayMessage syntheticMessage, ConversationEventSink eventSink) throws Exception {
        String sourceKey = syntheticMessage.sourceKey();
        synchronized (lockFor(sourceKey)) {
            SessionRecord session = sessionRepository.getBoundSession(sourceKey);
            if (session == null) {
                session = sessionRepository.bindNewSession(sourceKey);
            }
            return runOnSession(session, syntheticMessage, eventSink);
        }
    }

    /**
     * 恢复待恢复。
     *
     * @param sourceKey 渠道来源键。
     * @return 返回resume Pending结果。
     */
    public GatewayReply resumePending(String sourceKey) throws Exception {
        return resumePending(sourceKey, null, ConversationEventSink.noop());
    }

    /**
     * 恢复待恢复。
     *
     * @param sourceKey 渠道来源键。
     * @param sessionId 当前会话标识。
     * @return 返回resume Pending结果。
     */
    public GatewayReply resumePending(String sourceKey, String sessionId) throws Exception {
        return resumePending(sourceKey, sessionId, ConversationEventSink.noop());
    }

    /**
     * 恢复待恢复。
     *
     * @param sourceKey 渠道来源键。
     * @param eventSink 事件Sink参数。
     * @return 返回resume Pending结果。
     */
    public GatewayReply resumePending(String sourceKey, ConversationEventSink eventSink)
            throws Exception {
        return resumePending(sourceKey, null, eventSink);
    }

    /**
     * 恢复待恢复。
     *
     * @param sourceKey 渠道来源键。
     * @param sessionId 当前会话标识。
     * @param eventSink 事件Sink参数。
     * @return 返回resume Pending结果。
     */
    public GatewayReply resumePending(
            String sourceKey, String sessionId, ConversationEventSink eventSink) throws Exception {
        synchronized (lockFor(sourceKey)) {
            SessionRecord session = findPendingSession(sourceKey, sessionId);
            if (session == null) {
                return missingPendingSessionReply(sessionId);
            }
            String resumedUserMessage = MessageSupport.getLastUserMessage(session.getNdjson());

            AgentRuntimeScope agentScope = resolveAgentScope(session);
            List<String> enabledToolNames =
                    toolRegistry.resolveEnabledToolNames(sourceKey, agentScope);
            List<Object> enabledTools = toolRegistry.resolveEnabledTools(sourceKey, agentScope);
            String systemPrompt =
                    contextService.buildSystemPrompt(sourceKey, agentScope)
                            + "\n\n"
                            + runtimeSettingsService.buildAgentRuntimePrompt(
                                    sourceKey, session, enabledToolNames, agentScope);
            systemPrompt = appendResumePendingSystemNote(systemPrompt, session);
            session.setSystemPromptSnapshot(systemPrompt);

            GatewayMessage feedbackTarget = messageFromSourceKey(sourceKey);
            ConversationFeedbackSink feedbackSink = feedbackSinkFor(feedbackTarget);
            AgentRunOutcome outcome =
                    agentRunSupervisor.run(
                            session,
                            systemPrompt,
                            null,
                            enabledTools,
                            feedbackSink,
                            eventSink,
                            true,
                            agentScope,
                            Collections.emptyList(),
                            null,
                            Collections.emptyList(),
                            Collections.emptyList(),
                            null);
            String finalReply =
                    sanitizeFinalReply(
                            StrUtil.blankToDefault(
                                    outcome.getFinalReply(),
                                    AgentRecoveryPromptConstants.EMPTY_REPLY_FALLBACK));
            finalReply = decorateFinalReply(finalReply, feedbackTarget.getPlatform(), outcome);
            feedbackSink.onFinalReply(finalReply);
            eventSink.onRunCompleted(session.getSessionId(), finalReply, outcome.getResult());
            clearAgentPending(session);
            syncMemory(session.getSourceKey(), resumedUserMessage, finalReply, session, outcome);
            GatewayReply reply = GatewayReply.ok(finalReply);
            reply.setSessionId(session.getSessionId());
            reply.setBranchName(session.getBranchName());
            applyRuntimeMetadata(reply, outcome);
            return reply;
        }
    }

    /**
     * 追加Resume Pending System Note。
     *
     * @param systemPrompt 系统提示词参数。
     * @param session 会话参数。
     * @return 返回Resume Pending System Note结果。
     */
    private String appendResumePendingSystemNote(String systemPrompt, SessionRecord session) {
        try {
            SqliteAgentSession agentSession = new SqliteAgentSession(session);
            if (!agentSession.isPending()) {
                return systemPrompt;
            }
            String note =
                    ResumePendingSupport.gatewayInterruptionSystemNote(
                            agentSession.getPendingReason());
            if (StrUtil.isBlank(note)) {
                return systemPrompt;
            }
            return StrUtil.blankToDefault(systemPrompt, "") + "\n\n" + note;
        } catch (Exception e) {
            log.debug(
                    "skip resume pending system note: sessionId={}, error={}",
                    session == null ? "" : session.getSessionId(),
                    EngineSupport.safeError(e));
            return systemPrompt;
        }
    }

    /**
     * 查找Pending会话。
     *
     * @param sourceKey 渠道来源键。
     * @param sessionId 当前会话标识。
     * @return 返回Pending会话结果。
     */
    private SessionRecord findPendingSession(String sourceKey, String sessionId) throws Exception {
        SessionRecord bound = sessionRepository.getBoundSession(sourceKey);
        if (StrUtil.isBlank(sessionId)) {
            return isPendingSession(bound) ? bound : null;
        }
        SessionRecord target = sessionRepository.findById(sessionId);
        if (!matchesSourceKey(sourceKey, target) || !isPendingSession(target)) {
            return null;
        }
        return target;
    }

    /**
     * 执行missing待恢复会话回复相关逻辑。
     *
     * @param sessionId 当前会话标识。
     * @return 返回missing Pending会话Reply结果。
     */
    private GatewayReply missingPendingSessionReply(String sessionId) {
        if (StrUtil.isBlank(sessionId)) {
            return GatewayReply.error("当前来源键没有可恢复的 pending 会话。");
        }
        return GatewayReply.error("指定会话不是当前来源键下可恢复的 pending 会话。");
    }

    /**
     * 判断是否匹配来源键。
     *
     * @param sourceKey 渠道来源键。
     * @param session 会话参数。
     * @return 返回matches来源键结果。
     */
    private boolean matchesSourceKey(String sourceKey, SessionRecord session) {
        return session != null && StrUtil.equals(sourceKey, session.getSourceKey());
    }

    /**
     * 判断是否Pending会话。
     *
     * @param session 会话参数。
     * @return 如果Pending会话满足条件则返回 true，否则返回 false。
     */
    private boolean isPendingSession(SessionRecord session) {
        if (session == null) {
            return false;
        }
        try {
            return new SqliteAgentSession(session).isPending();
        } catch (Exception e) {
            log.debug(
                    "skip invalid pending session snapshot: sessionId={}, error={}",
                    session.getSessionId(),
                    EngineSupport.safeError(e));
            return false;
        }
    }

    /**
     * 清理Agent Pending。
     *
     * @param session 会话参数。
     */
    private void clearAgentPending(SessionRecord session) {
        if (session == null) {
            return;
        }
        try {
            SqliteAgentSession agentSession = new SqliteAgentSession(session, sessionRepository);
            if (agentSession.isPending()) {
                agentSession.pending(false, null);
                agentSession.updateSnapshot();
            }
        } catch (Exception e) {
            log.warn(
                    "clear agent pending failed: sessionId={}, error={}",
                    session.getSessionId(),
                    EngineSupport.safeError(e));
        }
    }

    /**
     * 写入钩子注册表。
     *
     * @param hookRegistry 钩子注册表依赖组件。
     */
    public void setHookRegistry(AgentHookRegistry hookRegistry) {
        this.hookRegistry = hookRegistry;
    }

    /**
     * 调用钩子。
     *
     * @param hookName 钩子名称参数。
     * @param sessionId 当前会话标识。
     * @param message 平台消息或错误消息。
     */
    private void invokeHook(String hookName, String sessionId, String message) {
        if (hookRegistry == null) {
            return;
        }
        java.util.Map<String, Object> args = new java.util.HashMap<>();
        args.put("session_id", sessionId);
        if (message != null) {
            args.put("message", message);
        }
        hookRegistry.invoke(hookName, args);
    }

    /**
     * 执行lockFor相关逻辑。
     *
     * @param sourceKey 渠道来源键。
     * @return 返回lock For结果。
     */
    private Object lockFor(String sourceKey) {
        String key = StrUtil.blankToDefault(sourceKey, "__default__");
        Object existing = sourceLocks.get(key);
        if (existing != null) {
            return existing;
        }
        Object created = new Object();
        Object previous = sourceLocks.putIfAbsent(key, created);
        return previous == null ? created : previous;
    }

    /**
     * 解析Agent范围。
     *
     * @param session 会话参数。
     * @return 返回解析后的Agent范围。
     */
    private AgentRuntimeScope resolveAgentScope(SessionRecord session) throws Exception {
        if (agentRuntimeService != null) {
            return agentRuntimeService.resolve(session);
        }
        AgentRuntimeScope scope = new AgentRuntimeScope();
        scope.setAgentName("default");
        ProfileRuntimeScope.Context profileScope = ProfileRuntimeScope.current();
        scope.setWorkspaceDir(
                profileScope != null && profileScope.getHome() != null
                        ? profileScope.getHome().toString()
                        : System.getProperty("user.dir"));
        return scope;
    }

    /**
     * 运行On会话。
     *
     * @param session 会话参数。
     * @param message 平台消息或错误消息。
     * @param eventSink 事件Sink参数。
     * @return 返回On会话结果。
     */
    private GatewayReply runOnSession(
            SessionRecord session, GatewayMessage message, ConversationEventSink eventSink)
            throws Exception {
        boolean shouldDrainQueue = false;
        String previousTransientProvider = session.getTransientProviderOverride();
        String previousTransientModel = session.getTransientModelOverride();
        String previousTransientBaseUrl = session.getTransientBaseUrlOverride();
        DangerousCommandApprovalService.PendingApproval pendingApproval =
                dangerousCommandApprovalService == null
                        ? null
                        : dangerousCommandApprovalService.getPendingApproval(session);
        if (pendingApproval != null) {
            GatewayReply reply = GatewayReply.error(formatPendingApprovalBlock(pendingApproval));
            reply.setSessionId(session.getSessionId());
            reply.setBranchName(session.getBranchName());
            if (message != null && message.getPlatform() != null) {
                reply.getChannelExtras()
                        .putAll(
                                dangerousCommandApprovalService.buildDeliveryExtras(
                                        message.getPlatform(), pendingApproval));
            }
            return reply;
        }

        try {
            applyTransientModelOverride(session, message);
            transcribeVoiceAttachments(message);
            String effectiveUserText = MessageAttachmentSupport.composeEffectiveUserText(message);
            message.setText(effectiveUserText);
            if (!message.isHeartbeat()
                    && StrUtil.isBlank(session.getTitle())
                    && StrUtil.isNotBlank(effectiveUserText)) {
                session.setTitle(extractTitle(effectiveUserText));
            }
            AgentRuntimeScope agentScope = resolveAgentScope(session);
            applyWorkspaceOverride(agentScope, message);
            applyToolsetOverride(agentScope, message);
            List<String> enabledToolNames =
                    toolRegistry.resolveEnabledToolNames(message.sourceKey(), agentScope);
            List<Object> enabledTools =
                    toolRegistry.resolveEnabledTools(message.sourceKey(), agentScope);
            String systemPrompt =
                    contextService.buildSystemPrompt(message.sourceKey(), agentScope)
                            + "\n\n"
                            + runtimeSettingsService.buildAgentRuntimePrompt(
                                    message.sourceKey(), session, enabledToolNames, agentScope);
            systemPrompt =
                    appendToolPolicySystemNote(
                            systemPrompt,
                            message.getAllowedToolsOverride(),
                            message.getMaxToolCallsOverride());
            session.setSystemPromptSnapshot(systemPrompt);

            ConversationFeedbackSink feedbackSink = feedbackSinkFor(message);
            invokeHook(AgentHookName.PRE_LLM_CALL, session.getSessionId(), effectiveUserText);
            String memoryPrefetchContext = prefetchMemory(message.sourceKey(), effectiveUserText);
            MessageDeliveryTracker.clearDirectDelivery(message.sourceKey());
            AgentRunOutcome outcome =
                    agentRunSupervisor.run(
                            session,
                            systemPrompt,
                            effectiveUserText,
                            enabledTools,
                            feedbackSink,
                            eventSink,
                            false,
                            agentScope,
                            MessageAttachmentSupport.safeAttachments(message),
                            memoryPrefetchContext,
                            message.getAllowedToolsOverride(),
                            message.getRequiredToolsOverride(),
                            message.getMaxToolCallsOverride());
            shouldDrainQueue = true;
            String finalReply =
                    sanitizeFinalReply(
                            StrUtil.blankToDefault(
                                    outcome.getFinalReply(),
                                    AgentRecoveryPromptConstants.EMPTY_REPLY_FALLBACK));
            if (MessageDeliveryTracker.consumeDirectDelivery(message.sourceKey())) {
                finalReply = "";
            } else {
                finalReply = decorateFinalReply(finalReply, message.getPlatform(), outcome);
            }
            feedbackSink.onFinalReply(finalReply);
            eventSink.onRunCompleted(session.getSessionId(), finalReply, outcome.getResult());
            invokeHook(AgentHookName.POST_LLM_CALL, session.getSessionId(), finalReply);
            invokeHook(AgentHookName.ON_SESSION_END, session.getSessionId(), null);
            syncMemory(message.sourceKey(), effectiveUserText, finalReply, session, outcome);
            GatewayReply reply = GatewayReply.ok(finalReply);
            reply.setSessionId(session.getSessionId());
            reply.setBranchName(session.getBranchName());
            applyRuntimeMetadata(reply, outcome);
            applyApprovalCardIfNeeded(reply, message.getPlatform(), session);
            applyGoalDecision(reply, session, finalReply, message);
            return reply;
        } finally {
            session.setTransientProviderOverride(previousTransientProvider);
            session.setTransientModelOverride(previousTransientModel);
            session.setTransientBaseUrlOverride(previousTransientBaseUrl);
            if (shouldDrainQueue || !agentRunSupervisor.isRunning(message.sourceKey())) {
                agentRunSupervisor.onRunFinished(
                        message.sourceKey(),
                        session.getSessionId(),
                        queued -> {
                            try {
                                return handleIncoming(queued, eventSink);
                            } catch (Exception e) {
                                throw new IllegalStateException(e);
                            }
                        });
            }
        }
    }

    /**
     * 追加 Web 单轮工具策略提示，提示模型在受控回归场景中主动收敛工具使用。
     *
     * @param systemPrompt 原始系统提示词。
     * @param allowedTools 本轮允许工具白名单。
     * @param maxToolCalls 本轮最大工具调用次数。
     * @return 返回追加策略后的系统提示词。
     */
    private String appendToolPolicySystemNote(
            String systemPrompt, List<String> allowedTools, Integer maxToolCalls) {
        boolean hasAllowedTools = allowedTools != null && !allowedTools.isEmpty();
        boolean hasMaxToolCalls = maxToolCalls != null && maxToolCalls.intValue() > 0;
        if (!hasAllowedTools && !hasMaxToolCalls) {
            return systemPrompt;
        }
        StringBuilder note = new StringBuilder(StrUtil.nullToEmpty(systemPrompt));
        note.append("\n\n[本轮 Web 运行工具策略]\n");
        if (hasAllowedTools) {
            note.append("- 只允许调用这些工具：").append(allowedTools).append('\n');
        }
        if (hasMaxToolCalls) {
            note.append("- 最多允许尝试 ")
                    .append(maxToolCalls.intValue())
                    .append(" 次工具调用，超过后工具会被拒绝执行。\n");
        }
        note.append("- 如果策略不足以完成任务，停止继续调用工具，并在最终回复中说明受限原因。");
        return note.toString();
    }

    /**
     * 执行transcribe语音附件相关逻辑。
     *
     * @param message 平台消息或错误消息。
     */
    private void transcribeVoiceAttachments(GatewayMessage message) {
        if (speechService == null || message == null || message.getAttachments() == null) {
            return;
        }
        for (com.jimuqu.solon.claw.core.model.MessageAttachment attachment :
                message.getAttachments()) {
            if (attachment == null || StrUtil.isNotBlank(attachment.getTranscribedText())) {
                continue;
            }
            if (!isVoiceAttachment(attachment)) {
                continue;
            }
            SpeechService.TranscriptionOutcome outcome =
                    speechService.transcribe(attachment, Collections.<String, Object>emptyMap());
            if (outcome.isSuccess() && StrUtil.isNotBlank(outcome.getText())) {
                attachment.setTranscribedText(outcome.getText());
            } else if (!outcome.isSuccess()) {
                log.warn(
                        "Voice transcription skipped: source={}, error={}",
                        message.sourceKey(),
                        SecretRedactor.redact(outcome.getError(), 500));
            }
        }
    }

    /**
     * 判断是否Voice附件。
     *
     * @param attachment 附件参数。
     * @return 如果Voice附件满足条件则返回 true，否则返回 false。
     */
    private boolean isVoiceAttachment(
            com.jimuqu.solon.claw.core.model.MessageAttachment attachment) {
        String kind = StrUtil.nullToEmpty(attachment.getKind()).toLowerCase(Locale.ROOT);
        String mime = StrUtil.nullToEmpty(attachment.getMimeType()).toLowerCase(Locale.ROOT);
        return "voice".equals(kind) || mime.startsWith("audio/");
    }

    /**
     * 应用Transient模型Override。
     *
     * @param session 会话参数。
     * @param message 平台消息或错误消息。
     */
    private void applyTransientModelOverride(SessionRecord session, GatewayMessage message) {
        if (session == null || message == null || StrUtil.isBlank(message.getModelOverride())) {
            return;
        }
        String override = message.getModelOverride().trim();
        String provider = "";
        String model = override;
        int first = override.indexOf(':');
        int second = first < 0 ? -1 : override.indexOf(':', first + 1);
        if (first > 0) {
            provider = override.substring(0, first).trim();
            model =
                    second > first
                            ? override.substring(first + 1, second).trim()
                            : override.substring(first + 1).trim();
            if (second > first) {
                session.setTransientBaseUrlOverride(override.substring(second + 1).trim());
            }
        }
        session.setTransientProviderOverride(provider);
        session.setTransientModelOverride(model);
    }

    /**
     * 应用工作区Override。
     *
     * @param agentScope 当前运行冻结后的 Agent 范围。
     * @param message 平台消息或错误消息。
     */
    private void applyWorkspaceOverride(AgentRuntimeScope agentScope, GatewayMessage message) {
        if (agentScope == null
                || message == null
                || StrUtil.isBlank(message.getWorkspaceDirOverride())) {
            return;
        }
        File dir = new File(message.getWorkspaceDirOverride().trim());
        if (!dir.exists() || !dir.isDirectory()) {
            log.warn(
                    "Ignoring missing scheduled workspace override: {}",
                    message.getWorkspaceDirOverride());
            return;
        }
        agentScope.setWorkspaceDir(dir.getAbsolutePath());
        agentScope.setWorkspaceDirOverride(true);
    }

    /**
     * 应用工具集Override。
     *
     * @param agentScope 当前运行冻结后的 Agent 范围。
     * @param message 平台消息或错误消息。
     */
    private void applyToolsetOverride(AgentRuntimeScope agentScope, GatewayMessage message) {
        if (agentScope == null || message == null) {
            return;
        }
        List<String> enabled = message.getEnabledToolsetsOverride();
        List<String> disabled = message.getDisabledToolsetsOverride();
        boolean hasEnabled = enabled != null && !enabled.isEmpty();
        boolean hasDisabled = disabled != null && !disabled.isEmpty();
        if (!hasEnabled && !hasDisabled) {
            return;
        }
        LinkedHashSet<String> allowed;
        if (hasEnabled) {
            allowed = AgentRuntimePolicy.expandToolSelectors(enabled);
        } else {
            allowed = new LinkedHashSet<String>(toolRegistry.listToolNames());
        }
        if (hasDisabled) {
            allowed.removeAll(AgentRuntimePolicy.expandToolSelectors(disabled));
        }
        agentScope.setAllowedToolsJson(
                org.noear.snack4.ONode.serialize(new ArrayList<String>(allowed)));
    }

    /**
     * 清理Final Reply。
     *
     * @param finalReply 最终回复参数。
     * @return 返回Final Reply结果。
     */
    private String sanitizeFinalReply(String finalReply) {
        String sanitized =
                MessageSupport.visibleText(MemoryContextBoundary.scrubVisibleText(finalReply));
        return StrUtil.blankToDefault(sanitized, AgentRecoveryPromptConstants.EMPTY_REPLY_FALLBACK);
    }

    /**
     * 执行decorate最终回复相关逻辑。
     *
     * @param finalReply 最终回复参数。
     * @param platform 平台参数。
     * @param outcome outcome 参数。
     * @return 返回decorate Final Reply结果。
     */
    private String decorateFinalReply(
            String finalReply, PlatformType platform, AgentRunOutcome outcome) {
        String result = StrUtil.nullToEmpty(finalReply);
        if (outcome != null && StrUtil.isNotBlank(outcome.getCompressionWarning())) {
            result = result.trim() + "\n\n提示：" + outcome.getCompressionWarning();
        }
        return runtimeFooterService.appendFooter(result, platform, outcome);
    }

    /**
     * 应用运行时元数据。
     *
     * @param reply 回复参数。
     * @param outcome outcome 参数。
     */
    private void applyRuntimeMetadata(GatewayReply reply, AgentRunOutcome outcome) {
        if (reply == null || outcome == null || reply.isError() || reply.isCommandHandled()) {
            return;
        }
        if (StrUtil.isNotBlank(outcome.getProvider())) {
            reply.getRuntimeMetadata().put("provider", outcome.getProvider());
        }
        if (StrUtil.isNotBlank(outcome.getModel())) {
            reply.getRuntimeMetadata().put("model", outcome.getModel());
        }
        if (outcome.getContextEstimateTokens() > 0) {
            reply.getRuntimeMetadata()
                    .put("contextEstimateTokens", outcome.getContextEstimateTokens());
        }
        if (outcome.getContextWindowTokens() > 0) {
            reply.getRuntimeMetadata().put("contextWindowTokens", outcome.getContextWindowTokens());
        }
        if (StrUtil.isNotBlank(outcome.getCwd())) {
            reply.getRuntimeMetadata().put("cwd", outcome.getCwd());
        }
    }

    /**
     * 应用目标决策。
     *
     * @param reply 回复参数。
     * @param session 会话参数。
     * @param finalReply 最终回复参数。
     * @param message 平台消息或错误消息。
     */
    private void applyGoalDecision(
            GatewayReply reply, SessionRecord session, String finalReply, GatewayMessage message) {
        if (goalService == null
                || reply == null
                || reply.isError()
                || reply.isCommandHandled()
                || session == null
                || message == null
                || message.isHeartbeat()) {
            return;
        }
        try {
            GoalDecision decision = goalService.evaluateAfterTurn(session, finalReply);
            if (decision == null || StrUtil.isBlank(decision.getVerdict())) {
                return;
            }
            reply.getRuntimeMetadata().put("goal_status", decision.getStatus());
            reply.getRuntimeMetadata().put("goal_verdict", decision.getVerdict());
            reply.getRuntimeMetadata().put("goal_reason", decision.getReason());
            if (StrUtil.isNotBlank(decision.getMessage())) {
                reply.getRuntimeMetadata().put("goal_message", decision.getMessage());
            }
            if (decision.isShouldContinue()
                    && StrUtil.isNotBlank(decision.getContinuationPrompt())) {
                reply.getRuntimeMetadata().put("goal_should_continue", Boolean.TRUE);
                reply.getRuntimeMetadata()
                        .put("goal_continuation_prompt", decision.getContinuationPrompt());
            }
        } catch (Exception e) {
            log.warn(
                    "Goal continuation hook failed: sessionId={}, error={}",
                    session.getSessionId(),
                    EngineSupport.safeError(e));
        }
    }

    /**
     * 格式化Pending审批块。
     *
     * @param pending 待恢复参数。
     * @return 返回Pending审批块结果。
     */
    private String formatPendingApprovalBlock(
            DangerousCommandApprovalService.PendingApproval pending) {
        StringBuilder buffer = new StringBuilder();
        buffer.append("当前会话有待审批的危险命令，本次请求已跳过，避免覆盖审批状态。\n");
        buffer.append("工具：")
                .append(StrUtil.blankToDefault(pending.getToolName(), "unknown"))
                .append('\n');
        buffer.append("原因：")
                .append(StrUtil.blankToDefault(pending.getDescription(), "危险命令"))
                .append("\n\n");
        if (pending.isPermanentApprovalAllowed()) {
            buffer.append(
                    "请先回复 `/approve` 执行一次，`/approve session` 记住当前会话，`/approve always` 永久记住，或 `/deny` 取消。");
        } else {
            buffer.append(
                    "该安全扫描结果只支持本次或当前会话审批，不能永久记住。请先回复 `/approve` 执行一次，`/approve session` 记住当前会话，或 `/deny` 取消。");
        }
        return buffer.toString();
    }

    /**
     * 执行反馈接收端For相关逻辑。
     *
     * @param message 平台消息或错误消息。
     * @return 返回feedback接收端For结果。
     */
    private ConversationFeedbackSink feedbackSinkFor(GatewayMessage message) {
        if (message == null
                || message.isHeartbeat()
                || message.getPlatform() == null
                || message.getPlatform() == PlatformType.MEMORY) {
            return ConversationFeedbackSink.noop();
        }
        return new GatewayConversationFeedbackSink(
                message, deliveryService, displaySettingsService);
    }

    /**
     * 执行消息From来源键相关逻辑。
     *
     * @param sourceKey 渠道来源键。
     * @return 返回消息From来源键结果。
     */
    private GatewayMessage messageFromSourceKey(String sourceKey) {
        String[] parts = SourceKeySupport.split(sourceKey);
        PlatformType platform = PlatformType.fromName(parts[0]);
        GatewayMessage message = new GatewayMessage(platform, parts[1], parts[2], "");
        message.setThreadId(parts[3]);
        message.setSourceKeyOverride(sourceKey);
        return message;
    }

    /**
     * 应用审批卡片IfNeeded。
     *
     * @param reply 回复参数。
     * @param platform 平台参数。
     * @param session 会话参数。
     */
    private void applyApprovalCardIfNeeded(
            GatewayReply reply, PlatformType platform, SessionRecord session) {
        if (reply == null
                || platform == null
                || session == null
                || dangerousCommandApprovalService == null) {
            return;
        }

        DangerousCommandApprovalService.PendingApproval pending =
                dangerousCommandApprovalService.getPendingApproval(session);
        if (pending == null) {
            return;
        }

        reply.getChannelExtras()
                .putAll(dangerousCommandApprovalService.buildDeliveryExtras(platform, pending));
    }

    /**
     * 预取本轮用户输入相关的长期记忆，只作为模型请求期上下文使用。
     *
     * @param sourceKey 渠道来源键。
     * @param userMessage 用户消息参数。
     * @return 返回预取到的临时记忆上下文。
     */
    private String prefetchMemory(String sourceKey, String userMessage) {
        if (memoryManager == null || StrUtil.isBlank(userMessage)) {
            return "";
        }
        try {
            return StrUtil.nullToEmpty(memoryManager.prefetch(sourceKey, userMessage));
        } catch (Exception e) {
            log.warn(
                    "Memory prefetch failed: sourceKey={}, error={}",
                    sourceKey,
                    EngineSupport.safeError(e));
            return "";
        }
    }

    /**
     * 执行同步记忆相关逻辑。
     *
     * @param sourceKey 渠道来源键。
     * @param userMessage 用户消息参数。
     * @param finalReply 最终回复参数。
     */
    private void syncMemory(String sourceKey, String userMessage, String finalReply) {
        syncMemory(sourceKey, userMessage, finalReply, null, null);
    }

    /**
     * 执行同步记忆相关逻辑。
     *
     * @param sourceKey 渠道来源键。
     * @param userMessage 用户消息参数。
     * @param finalReply 最终回复参数。
     * @param session 会话参数。
     * @param outcome outcome 参数。
     */
    private void syncMemory(
            String sourceKey,
            String userMessage,
            String finalReply,
            SessionRecord session,
            AgentRunOutcome outcome) {
        if (memoryManager == null) {
            return;
        }
        try {
            memoryManager.syncTurn(
                    memoryTurnContext(sourceKey, userMessage, finalReply, session, outcome));
        } catch (Exception e) {
            log.warn(
                    "Memory sync failed: sourceKey={}, error={}",
                    sourceKey,
                    EngineSupport.safeError(e));
        }
    }

    /**
     * 执行记忆Turn上下文相关逻辑。
     *
     * @param sourceKey 渠道来源键。
     * @param userMessage 用户消息参数。
     * @param finalReply 最终回复参数。
     * @param session 会话参数。
     * @param outcome outcome 参数。
     * @return 返回记忆Turn上下文结果。
     */
    private MemoryTurnContext memoryTurnContext(
            String sourceKey,
            String userMessage,
            String finalReply,
            SessionRecord session,
            AgentRunOutcome outcome) {
        LlmResult result = outcome == null ? null : outcome.getResult();
        return MemoryTurnContext.builder()
                .sourceKey(sourceKey)
                .sessionId(session == null ? null : session.getSessionId())
                .userMessage(userMessage)
                .assistantMessage(finalReply)
                .conversationNdjson(
                        StrUtil.blankToDefault(
                                result == null ? null : result.getNdjson(),
                                session == null ? null : session.getNdjson()))
                .provider(
                        StrUtil.blankToDefault(
                                outcome == null ? null : outcome.getProvider(),
                                result == null ? null : result.getProvider()))
                .model(
                        StrUtil.blankToDefault(
                                outcome == null ? null : outcome.getModel(),
                                result == null ? null : result.getModel()))
                .streamed(result != null && result.isStreamed())
                .inputTokens(result == null ? 0L : result.getInputTokens())
                .outputTokens(result == null ? 0L : result.getOutputTokens())
                .reasoningTokens(result == null ? 0L : result.getReasoningTokens())
                .cacheReadTokens(result == null ? 0L : result.getCacheReadTokens())
                .cacheWriteTokens(result == null ? 0L : result.getCacheWriteTokens())
                .totalTokens(result == null ? 0L : result.getTotalTokens())
                .build();
    }

    /**
     * 提取Text。
     *
     * @param assistantMessage assistant消息参数。
     * @return 返回Text结果。
     */
    private String extractText(AssistantMessage assistantMessage) {
        String text = MessageSupport.assistantText(assistantMessage);
        if (StrUtil.isNotBlank(text)) {
            return text;
        }
        if (assistantMessage == null) {
            return "";
        }

        log.warn(
                "Assistant message has no visible content in orchestrator; suppressing message object fallback: role={}, contentRawType={}, toolCalls={}",
                assistantMessage.getRole(),
                assistantMessage.getContentRaw() == null
                        ? ""
                        : assistantMessage.getContentRaw().getClass().getName(),
                assistantMessage.getToolCalls() == null
                        ? 0
                        : assistantMessage.getToolCalls().size());
        return "";
    }

    /** 从第一条用户文本生成会话标题。 */
    private String extractTitle(String text) {
        String normalized = text.replace('\r', ' ').replace('\n', ' ').trim();
        if (normalized.length() <= CompressionConstants.MAX_TITLE_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, CompressionConstants.MAX_TITLE_LENGTH) + "...";
    }

    /** 判断本轮是否发生了有效工具调用，便于在空回复时做一次恢复。 */
    private boolean hasRecentToolActivity(String previousNdjson, String currentNdjson) {
        try {
            List<ChatMessage> previous = MessageSupport.loadMessages(previousNdjson);
            List<ChatMessage> current = MessageSupport.loadMessages(currentNdjson);
            if (countTools(current) > countTools(previous)) {
                return true;
            }
            for (int i = current.size() - 1; i >= 0; i--) {
                ChatMessage message = current.get(i);
                if (message.getRole() == ChatRole.TOOL) {
                    return true;
                }
                if (message.getRole() == ChatRole.ASSISTANT
                        && StrUtil.isNotBlank(message.getContent())) {
                    return false;
                }
            }
        } catch (Exception e) {
            log.debug(
                    "Failed to inspect recent tool activity for empty-reply recovery: {}",
                    EngineSupport.safeError(e));
            return false;
        }
        return false;
    }

    /** 针对“工具执行成功但文字为空”的情况做一次无工具恢复调用。 */
    private LlmResult tryRecoverEmptyReply(SessionRecord session, String systemPrompt) {
        try {
            return llmGateway.chat(
                    session,
                    systemPrompt,
                    AgentRecoveryPromptConstants.EMPTY_REPLY_RECOVERY_PROMPT,
                    Collections.emptyList());
        } catch (Exception e) {
            log.debug(
                    "Empty-reply recovery chat failed for session {}, error={}",
                    session == null ? "" : session.getSessionId(),
                    EngineSupport.safeError(e));
            return null;
        }
    }

    /** 针对“达到最大步数上限”场景，再做一次无工具收敛总结。 */
    private LlmResult tryRecoverMaxStepsReply(SessionRecord session, String systemPrompt) {
        try {
            return llmGateway.chat(
                    session,
                    systemPrompt,
                    AgentRecoveryPromptConstants.MAX_STEPS_RECOVERY_PROMPT,
                    Collections.emptyList());
        } catch (Exception e) {
            log.debug(
                    "Max-steps recovery chat failed for session {}, error={}",
                    session == null ? "" : session.getSessionId(),
                    EngineSupport.safeError(e));
            return null;
        }
    }

    /**
     * 判断是否存在Usable Recovery Reply。
     *
     * @param recovered recovered 参数。
     * @return 如果Usable Recovery Reply满足条件则返回 true，否则返回 false。
     */
    private boolean hasUsableRecoveryReply(LlmResult recovered) {
        if (recovered == null) {
            return false;
        }
        String text = extractText(recovered.getAssistantMessage());
        return StrUtil.isNotBlank(text) && !isMaxStepsReply(text);
    }

    /**
     * 判断是否Max Steps Reply。
     *
     * @param replyText 回复文本参数。
     * @return 如果Max Steps Reply满足条件则返回 true，否则返回 false。
     */
    private boolean isMaxStepsReply(String replyText) {
        if (StrUtil.isBlank(replyText)) {
            return false;
        }

        String normalized = replyText.trim().toLowerCase();
        return normalized.startsWith("agent error: maximum steps reached")
                || normalized.contains("maximum steps reached")
                || replyText.contains("已达到硬性步数上限");
    }

    /** 统计工具消息数量。 */
    private int countTools(List<ChatMessage> messages) {
        int count = 0;
        for (ChatMessage message : messages) {
            if (message.getRole() == ChatRole.TOOL) {
                count++;
            }
        }
        return count;
    }

    /** 将本轮 usage 汇总写入会话记录。 */
    private void applyUsage(SessionRecord session, LlmResult result) {
        LlmUsageSupport.applyUsage(session, result);
    }

    /** 将恢复调用前已消耗的 usage 合并进最终结果。 */
    private void mergeUsage(LlmResult base, LlmResult extra) {
        LlmUsageSupport.mergeUsage(base, extra);
    }
}
