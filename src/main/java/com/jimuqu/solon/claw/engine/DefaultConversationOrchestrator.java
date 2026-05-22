package com.jimuqu.solon.claw.engine;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.agent.AgentRuntimePolicy;
import com.jimuqu.solon.claw.agent.AgentRuntimeScope;
import com.jimuqu.solon.claw.agent.AgentRuntimeService;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.AgentRunOutcome;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.LlmResult;
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
import com.jimuqu.solon.claw.plugin.AgentHookName;
import com.jimuqu.solon.claw.plugin.AgentHookRegistry;
import com.jimuqu.solon.claw.support.DisplaySettingsService;
import com.jimuqu.solon.claw.support.MessageAttachmentSupport;
import com.jimuqu.solon.claw.support.MessageSupport;
import com.jimuqu.solon.claw.support.RuntimeFooterService;
import com.jimuqu.solon.claw.support.RuntimeSettingsService;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.SourceKeySupport;
import com.jimuqu.solon.claw.support.constants.CompressionConstants;
import com.jimuqu.solon.claw.storage.session.SqliteAgentSession;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import com.jimuqu.solon.claw.tool.runtime.MessageDeliveryTracker;
import java.io.File;
import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** DefaultConversationOrchestrator 实现。 */
public class DefaultConversationOrchestrator implements ConversationOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(DefaultConversationOrchestrator.class);

    /** 当模型只完成工具调用却未生成最终文字答复时，补发的恢复提示。 */
    private static final String EMPTY_REPLY_RECOVERY_PROMPT =
            "你刚刚已经完成了工具调用，但没有输出最终答复。请基于当前会话中的最新工具结果，直接用中文给出简洁最终答复，不要再次调用工具。";

    /** 当恢复仍失败时返回给用户的兜底文案。 */
    private static final String EMPTY_REPLY_FALLBACK =
            "本轮已完成工具调用，但模型没有返回可读结论。请使用 /retry 重试，或继续给出下一步指令。";

    /** 当 ReAct 步数耗尽时，要求模型基于现有轨迹做一次无工具收敛总结。 */
    private static final String MAX_STEPS_RECOVERY_PROMPT =
            "你刚刚因为最大推理步数限制而停止。不要再次调用工具。请基于当前会话中已经完成的分析、工具结果、文件修改和观察，直接输出中文收敛答复：优先给出已经完成的结果；若任务仍未彻底完成，明确说明还差什么、最推荐的下一步是什么。";

    /** 当步数耗尽后的收敛恢复仍失败时，返回给用户的兜底文案。 */
    private static final String MAX_STEPS_RECOVERY_FALLBACK =
            "本轮执行已达到最大步骤限制，已保留当前进展。请继续给出更聚焦的下一步，或使用 /retry 继续。";

    private final SessionRepository sessionRepository;
    private final ContextService contextService;
    private final ContextCompressionService contextCompressionService;
    private final LlmGateway llmGateway;
    private final ToolRegistry toolRegistry;
    private final DeliveryService deliveryService;
    private final DisplaySettingsService displaySettingsService;
    private final RuntimeSettingsService runtimeSettingsService;
    private final DangerousCommandApprovalService dangerousCommandApprovalService;
    private final AgentRunSupervisor agentRunSupervisor;
    private final RuntimeFooterService runtimeFooterService;
    private final AgentRuntimeService agentRuntimeService;
    private final MemoryManager memoryManager;
    private final GoalService goalService;
    private final ConcurrentMap<String, Object> sourceLocks =
            new ConcurrentHashMap<String, Object>();
    private AgentHookRegistry hookRegistry;

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
                null);
    }

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
                null);
    }

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
                null);
    }

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
    }

    public GatewayReply handleIncoming(GatewayMessage message) throws Exception {
        return handleIncoming(message, ConversationEventSink.noop());
    }

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
                    GatewayReply.ok(StrUtil.blankToDefault(decision.getMessage(), decision.getStatus()));
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
        return runOnSession(session, message, eventSink);
    }

    public GatewayReply runScheduled(GatewayMessage syntheticMessage) throws Exception {
        return runScheduled(syntheticMessage, ConversationEventSink.noop());
    }

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

    public GatewayReply resumePending(String sourceKey) throws Exception {
        return resumePending(sourceKey, ConversationEventSink.noop());
    }

    public GatewayReply resumePending(String sourceKey, ConversationEventSink eventSink)
            throws Exception {
        synchronized (lockFor(sourceKey)) {
            SessionRecord session = sessionRepository.getBoundSession(sourceKey);
            if (session == null) {
                return GatewayReply.error("当前来源键没有可恢复的会话。");
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
                            agentScope);
            String finalReply =
                    StrUtil.blankToDefault(outcome.getFinalReply(), EMPTY_REPLY_FALLBACK);
            finalReply = decorateFinalReply(finalReply, feedbackTarget.getPlatform(), outcome);
            feedbackSink.onFinalReply(finalReply);
            eventSink.onRunCompleted(session.getSessionId(), finalReply, outcome.getResult());
            clearAgentPending(session);
            syncMemory(session.getSourceKey(), resumedUserMessage, finalReply);
            GatewayReply reply = GatewayReply.ok(finalReply);
            reply.setSessionId(session.getSessionId());
            reply.setBranchName(session.getBranchName());
            applyRuntimeMetadata(reply, outcome);
            return reply;
        }
    }

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
                    safeError(e));
            return systemPrompt;
        }
    }

    private void clearAgentPending(SessionRecord session) {
        if (session == null) {
            return;
        }
        try {
            SqliteAgentSession agentSession =
                    new SqliteAgentSession(session, sessionRepository);
            if (agentSession.isPending()) {
                agentSession.pending(false, null);
                agentSession.updateSnapshot();
            }
        } catch (Exception e) {
            log.warn(
                    "clear agent pending failed: sessionId={}, error={}",
                    session.getSessionId(),
                    safeError(e));
        }
    }

    public void setHookRegistry(AgentHookRegistry hookRegistry) {
        this.hookRegistry = hookRegistry;
    }

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

    private AgentRuntimeScope resolveAgentScope(SessionRecord session) throws Exception {
        if (agentRuntimeService != null) {
            return agentRuntimeService.resolve(session);
        }
        AgentRuntimeScope scope = new AgentRuntimeScope();
        scope.setAgentName(
                AgentRuntimeScope.normalizeName(
                        session == null ? null : session.getActiveAgentName()));
        scope.setWorkspaceDir(System.getProperty("user.dir"));
        return scope;
    }

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
            session.setSystemPromptSnapshot(systemPrompt);

            ConversationFeedbackSink feedbackSink = feedbackSinkFor(message);
            invokeHook(AgentHookName.PRE_LLM_CALL, session.getSessionId(), effectiveUserText);
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
                            MessageAttachmentSupport.safeAttachments(message));
            shouldDrainQueue = true;
            String finalReply = StrUtil.blankToDefault(outcome.getFinalReply(), EMPTY_REPLY_FALLBACK);
            if (MessageDeliveryTracker.consumeDuplicateFinalReply(message.sourceKey(), finalReply)) {
                finalReply = "";
            } else {
                finalReply = decorateFinalReply(finalReply, message.getPlatform(), outcome);
            }
            feedbackSink.onFinalReply(finalReply);
            eventSink.onRunCompleted(session.getSessionId(), finalReply, outcome.getResult());
            invokeHook(AgentHookName.POST_LLM_CALL, session.getSessionId(), finalReply);
            invokeHook(AgentHookName.ON_SESSION_END, session.getSessionId(), null);
            syncMemory(message.sourceKey(), effectiveUserText, finalReply);
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
            model = second > first ? override.substring(first + 1, second).trim() : override.substring(first + 1).trim();
            if (second > first) {
                session.setTransientBaseUrlOverride(override.substring(second + 1).trim());
            }
        }
        session.setTransientProviderOverride(provider);
        session.setTransientModelOverride(model);
    }

    private void applyWorkspaceOverride(AgentRuntimeScope agentScope, GatewayMessage message) {
        if (agentScope == null || message == null || StrUtil.isBlank(message.getWorkspaceDirOverride())) {
            return;
        }
        File dir = new File(message.getWorkspaceDirOverride().trim());
        if (!dir.exists() || !dir.isDirectory()) {
            log.warn("Ignoring missing scheduled workspace override: {}", message.getWorkspaceDirOverride());
            return;
        }
        agentScope.setWorkspaceDir(dir.getAbsolutePath());
        agentScope.setWorkspaceDirOverride(true);
    }

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
        agentScope.setAllowedToolsJson(org.noear.snack4.ONode.serialize(new ArrayList<String>(allowed)));
    }

    private String decorateFinalReply(
            String finalReply, PlatformType platform, AgentRunOutcome outcome) {
        String result = StrUtil.nullToEmpty(finalReply);
        if (outcome != null && StrUtil.isNotBlank(outcome.getCompressionWarning())) {
            result = result.trim() + "\n\n提示：" + outcome.getCompressionWarning();
        }
        return runtimeFooterService.appendFooter(result, platform, outcome);
    }

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
            if (decision.isShouldContinue() && StrUtil.isNotBlank(decision.getContinuationPrompt())) {
                reply.getRuntimeMetadata().put("goal_should_continue", Boolean.TRUE);
                reply.getRuntimeMetadata().put("goal_continuation_prompt", decision.getContinuationPrompt());
            }
        } catch (Exception e) {
            log.warn(
                    "Goal continuation hook failed: sessionId={}, error={}",
                    session.getSessionId(),
                    safeError(e));
        }
    }

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

    private GatewayMessage messageFromSourceKey(String sourceKey) {
        String[] parts = SourceKeySupport.split(sourceKey);
        PlatformType platform = PlatformType.fromName(parts[0]);
        GatewayMessage message = new GatewayMessage(platform, parts[1], parts[2], "");
        message.setSourceKeyOverride(sourceKey);
        return message;
    }

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

    private void syncMemory(String sourceKey, String userMessage, String finalReply) {
        if (memoryManager == null) {
            return;
        }
        try {
            memoryManager.syncTurn(sourceKey, userMessage, finalReply);
        } catch (Exception e) {
            log.warn("Memory sync failed: sourceKey={}, error={}", sourceKey, safeError(e));
        }
    }

    private String extractText(AssistantMessage assistantMessage) {
        if (assistantMessage == null) {
            return "";
        }

        if (StrUtil.isNotBlank(assistantMessage.getResultContent())) {
            return assistantMessage.getResultContent();
        }

        if (StrUtil.isNotBlank(assistantMessage.getContent())) {
            return assistantMessage.getContent();
        }

        log.warn(
                "Assistant message has no visible content in orchestrator; suppressing message object fallback: role={}, contentRawType={}, toolCalls={}",
                assistantMessage.getRole(),
                assistantMessage.getContentRaw() == null
                        ? ""
                        : assistantMessage.getContentRaw().getClass().getName(),
                assistantMessage.getToolCalls() == null ? 0 : assistantMessage.getToolCalls().size());
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
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    /** 针对“工具执行成功但文字为空”的情况做一次无工具恢复调用。 */
    private LlmResult tryRecoverEmptyReply(SessionRecord session, String systemPrompt) {
        try {
            return llmGateway.chat(
                    session, systemPrompt, EMPTY_REPLY_RECOVERY_PROMPT, Collections.emptyList());
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 针对“达到最大步数上限”场景，再做一次无工具收敛总结。 */
    private LlmResult tryRecoverMaxStepsReply(SessionRecord session, String systemPrompt) {
        try {
            return llmGateway.chat(
                    session, systemPrompt, MAX_STEPS_RECOVERY_PROMPT, Collections.emptyList());
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean hasUsableRecoveryReply(LlmResult recovered) {
        if (recovered == null) {
            return false;
        }
        String text = extractText(recovered.getAssistantMessage());
        return StrUtil.isNotBlank(text) && !isMaxStepsReply(text);
    }

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
        if (session == null || result == null) {
            return;
        }

        session.setLastInputTokens(result.getInputTokens());
        session.setLastOutputTokens(result.getOutputTokens());
        session.setLastReasoningTokens(result.getReasoningTokens());
        session.setLastCacheReadTokens(result.getCacheReadTokens());
        session.setLastCacheWriteTokens(result.getCacheWriteTokens());
        session.setLastTotalTokens(result.getTotalTokens());

        session.setCumulativeInputTokens(
                session.getCumulativeInputTokens() + Math.max(0L, result.getInputTokens()));
        session.setCumulativeOutputTokens(
                session.getCumulativeOutputTokens() + Math.max(0L, result.getOutputTokens()));
        session.setCumulativeReasoningTokens(
                session.getCumulativeReasoningTokens() + Math.max(0L, result.getReasoningTokens()));
        session.setCumulativeCacheReadTokens(
                session.getCumulativeCacheReadTokens() + Math.max(0L, result.getCacheReadTokens()));
        session.setCumulativeCacheWriteTokens(
                session.getCumulativeCacheWriteTokens()
                        + Math.max(0L, result.getCacheWriteTokens()));
        session.setCumulativeTotalTokens(
                session.getCumulativeTotalTokens() + Math.max(0L, result.getTotalTokens()));

        if (result.getTotalTokens() > 0
                || result.getInputTokens() > 0
                || result.getOutputTokens() > 0
                || result.getCacheReadTokens() > 0
                || result.getCacheWriteTokens() > 0) {
            session.setLastUsageAt(System.currentTimeMillis());
        }

        if (StrUtil.isNotBlank(result.getProvider())) {
            session.setLastResolvedProvider(result.getProvider());
        }
        if (StrUtil.isNotBlank(result.getModel())) {
            session.setLastResolvedModel(result.getModel());
        }
    }

    /** 将恢复调用前已消耗的 usage 合并进最终结果。 */
    private void mergeUsage(LlmResult base, LlmResult extra) {
        if (base == null || extra == null) {
            return;
        }
        extra.setInputTokens(
                Math.max(0L, extra.getInputTokens()) + Math.max(0L, base.getInputTokens()));
        extra.setOutputTokens(
                Math.max(0L, extra.getOutputTokens()) + Math.max(0L, base.getOutputTokens()));
        extra.setReasoningTokens(
                Math.max(0L, extra.getReasoningTokens()) + Math.max(0L, base.getReasoningTokens()));
        extra.setCacheReadTokens(
                Math.max(0L, extra.getCacheReadTokens()) + Math.max(0L, base.getCacheReadTokens()));
        extra.setCacheWriteTokens(
                Math.max(0L, extra.getCacheWriteTokens())
                        + Math.max(0L, base.getCacheWriteTokens()));
        extra.setTotalTokens(
                Math.max(0L, extra.getTotalTokens()) + Math.max(0L, base.getTotalTokens()));
    }

    private String safeError(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        String message = error.getMessage();
        String value = StrUtil.isBlank(message) ? error.getClass().getSimpleName() : message;
        return SecretRedactor.redact(value, 1000);
    }
}
