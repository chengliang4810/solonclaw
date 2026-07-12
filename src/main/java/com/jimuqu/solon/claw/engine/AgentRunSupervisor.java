package com.jimuqu.solon.claw.engine;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.agent.AgentRuntimeScope;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.context.MemoryContextBoundary;
import com.jimuqu.solon.claw.core.model.AgentRunContext;
import com.jimuqu.solon.claw.core.model.AgentRunEventRecord;
import com.jimuqu.solon.claw.core.model.AgentRunOutcome;
import com.jimuqu.solon.claw.core.model.AgentRunRecord;
import com.jimuqu.solon.claw.core.model.AgentRunStopResult;
import com.jimuqu.solon.claw.core.model.CompressionOutcome;
import com.jimuqu.solon.claw.core.model.ContextBudgetDecision;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import com.jimuqu.solon.claw.core.model.QueuedRunMessage;
import com.jimuqu.solon.claw.core.model.RunBusyDecision;
import com.jimuqu.solon.claw.core.model.RunControlCommand;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.AgentRunCancelledException;
import com.jimuqu.solon.claw.core.service.AgentRunControlService;
import com.jimuqu.solon.claw.core.service.ContextBudgetService;
import com.jimuqu.solon.claw.core.service.ContextCompressionService;
import com.jimuqu.solon.claw.core.service.ConversationEventSink;
import com.jimuqu.solon.claw.core.service.LlmGateway;
import com.jimuqu.solon.claw.gateway.feedback.ConversationFeedbackSink;
import com.jimuqu.solon.claw.llm.LlmErrorClassifier;
import com.jimuqu.solon.claw.pricing.UsageCostCalculator;
import com.jimuqu.solon.claw.profile.ProfileRuntimeScope;
import com.jimuqu.solon.claw.storage.session.SqliteAgentSession;
import com.jimuqu.solon.claw.support.IdSupport;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.support.MessageSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.SourceKeySupport;
import com.jimuqu.solon.claw.support.StructuredMetadataSupport;
import com.jimuqu.solon.claw.tool.runtime.SubprocessEnvironmentSanitizer;
import com.jimuqu.solon.claw.usage.UsageEventCostSupport;
import com.jimuqu.solon.claw.usage.UsageEventRecord;
import com.jimuqu.solon.claw.usage.UsageEventRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;
import java.util.function.Function;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** solonclaw 风格的外层 Agent run 状态机。 */
public class AgentRunSupervisor implements AgentRunControlService {
    /** 日志的统一常量值。 */
    private static final Logger log = LoggerFactory.getLogger(AgentRunSupervisor.class);

    /** 排队运行标识键的统一常量值。 */
    private static final String QUEUED_RUN_ID_KEY = "__queuedRunId";

    /** 队列标识键的统一常量值。 */
    private static final String QUEUE_ID_KEY = "__queueId";

    /** 注入应用配置，用于Agent运行Supervisor。 */
    private final AppConfig appConfig;

    /** 保存会话仓储依赖，用于访问持久化数据。 */
    private final SessionRepository sessionRepository;

    /** 保存Agent运行仓储依赖，用于访问持久化数据。 */
    private final AgentRunRepository agentRunRepository;

    /** 注入上下文压缩服务，用于调用对应业务能力。 */
    private final ContextCompressionService contextCompressionService;

    /** 注入上下文预算服务，用于调用对应业务能力。 */
    private final ContextBudgetService contextBudgetService;

    /** 记录Agent运行Supervisor中的大模型消息网关。 */
    private final LlmGateway llmGateway;

    /** 注入大模型提供方服务，用于调用对应业务能力。 */
    private final LlmProviderService llmProviderService;

    /** 保存用量事件仓储依赖，用于访问持久化数据。 */
    private final UsageEventRepository usageEventRepository;

    /** 记录Agent运行Supervisor中的用量成本Calculator。 */
    private final UsageCostCalculator usageCostCalculator;

    /** 保存running运行映射，便于按键快速查询。 */
    private final ConcurrentMap<String, RunHandle> runningRuns =
            new ConcurrentHashMap<String, RunHandle>();

    /** 保存drainingQueues映射，便于按键快速查询。 */
    private final ConcurrentMap<String, AtomicBoolean> drainingQueues =
            new ConcurrentHashMap<String, AtomicBoolean>();

    /** Dashboard 手工恢复 pending 会话的回调，由启动配置在依赖就绪后接入。 */
    private volatile BiPredicate<String, String> pendingSessionResumer;

    /** 记录Agent运行Supervisor中的最近一次运行Finished时间。 */
    private volatile long lastRunFinishedAt;

    /**
     * 创建Agent运行Supervisor实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param sessionRepository 会话仓储依赖。
     * @param agentRunRepository Agent运行仓储依赖。
     * @param contextCompressionService 上下文CompressionService上下文。
     * @param contextBudgetService 上下文预算Service上下文。
     * @param llmGateway LLM网关参数。
     * @param llmProviderService LLM提供方Service标识或键值。
     */
    public AgentRunSupervisor(
            AppConfig appConfig,
            SessionRepository sessionRepository,
            AgentRunRepository agentRunRepository,
            ContextCompressionService contextCompressionService,
            ContextBudgetService contextBudgetService,
            LlmGateway llmGateway,
            LlmProviderService llmProviderService) {
        this(
                appConfig,
                sessionRepository,
                agentRunRepository,
                contextCompressionService,
                contextBudgetService,
                llmGateway,
                llmProviderService,
                null,
                null);
    }

    /**
     * 创建Agent运行Supervisor实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param sessionRepository 会话仓储依赖。
     * @param agentRunRepository Agent运行仓储依赖。
     * @param contextCompressionService 上下文CompressionService上下文。
     * @param contextBudgetService 上下文预算Service上下文。
     * @param llmGateway LLM网关参数。
     * @param llmProviderService LLM提供方Service标识或键值。
     * @param usageEventRepository 用量事件仓储依赖。
     * @param usageCostCalculator 用量成本Calculator参数。
     */
    public AgentRunSupervisor(
            AppConfig appConfig,
            SessionRepository sessionRepository,
            AgentRunRepository agentRunRepository,
            ContextCompressionService contextCompressionService,
            ContextBudgetService contextBudgetService,
            LlmGateway llmGateway,
            LlmProviderService llmProviderService,
            UsageEventRepository usageEventRepository,
            UsageCostCalculator usageCostCalculator) {
        this.appConfig = appConfig;
        this.sessionRepository = sessionRepository;
        this.agentRunRepository = agentRunRepository;
        this.contextCompressionService = contextCompressionService;
        this.contextBudgetService = contextBudgetService;
        this.llmGateway = llmGateway;
        this.llmProviderService = llmProviderService;
        this.usageEventRepository = usageEventRepository;
        this.usageCostCalculator = usageCostCalculator;
    }

    /**
     * 停止当前组件并释放运行状态。
     *
     * @param sourceKey 渠道来源键。
     * @return 返回stop结果。
     */
    @Override
    public AgentRunStopResult stop(String sourceKey) {
        RunHandle handle = runningRuns.get(normalizeSourceKey(sourceKey));
        if (handle == null) {
            return AgentRunStopResult.none();
        }
        return stopHandle(handle);
    }

    /**
     * 停止指定运行句柄，避免并发换主后误中断新 run。
     *
     * @param handle 当前需要停止的运行句柄。
     * @return 停止结果。
     */
    private AgentRunStopResult stopHandle(RunHandle handle) {
        handle.cancelled.set(true);
        Thread thread = handle.thread;
        boolean interruptSent = false;
        if (thread != null && thread.isAlive()) {
            thread.interrupt();
            interruptSent = true;
        }
        return AgentRunStopResult.stopped(
                handle.runId, handle.sessionId, interruptSent, handle.startedAt);
    }

    /**
     * 停止Sibling Thread运行。
     *
     * @param message 平台消息或错误消息。
     * @param ownSourceKey own来源键标识或键值。
     * @return 返回Sibling Thread运行结果。
     */
    @Override
    public AgentRunStopResult stopSiblingThreadRun(GatewayMessage message, String ownSourceKey) {
        if (message == null || StrUtil.isBlank(message.getThreadId())) {
            return AgentRunStopResult.none();
        }
        String ownKey = normalizeSourceKey(ownSourceKey);
        String prefix =
                SourceKeySupport.threadPrefix(
                        message.getPlatform(), message.getChatId(), message.getThreadId());
        if (StrUtil.isBlank(prefix)) {
            return AgentRunStopResult.none();
        }
        for (String sourceKey : new ArrayList<String>(runningRuns.keySet())) {
            if (StrUtil.equals(sourceKey, ownKey)) {
                continue;
            }
            if (!sourceKey.startsWith(prefix)) {
                continue;
            }
            AgentRunStopResult result = stop(sourceKey);
            if (result.isActiveRun()) {
                return result;
            }
        }
        return AgentRunStopResult.none();
    }

    /**
     * 执行active运行摘要相关逻辑。
     *
     * @param sourceKey 渠道来源键。
     * @return 返回active运行Summary结果。
     */
    @Override
    public Map<String, Object> activeRunSummary(String sourceKey) {
        String key = normalizeSourceKey(sourceKey);
        RunHandle handle = runningRuns.get(key);
        if (handle == null) {
            return null;
        }
        try {
            AgentRunRecord record = agentRunRepository.findRun(handle.runId);
            if (record == null) {
                return null;
            }
            long now = System.currentTimeMillis();
            long lastActivityAt =
                    record.getLastActivityAt() > 0
                            ? record.getLastActivityAt()
                            : record.getStartedAt();
            Map<String, Object> summary = new java.util.LinkedHashMap<String, Object>();
            summary.put("run_id", record.getRunId());
            summary.put("session_id", record.getSessionId());
            summary.put("source_key", record.getSourceKey());
            summary.put("status", record.getStatus());
            summary.put("phase", record.getPhase());
            summary.put("last_activity_at", Long.valueOf(lastActivityAt));
            summary.put(
                    "seconds_since_activity",
                    Long.valueOf(Math.max(0L, (now - lastActivityAt) / 1000L)));
            summary.put("last_activity_desc", lastActivityDescription(record));
            summary.put("tool_call_count", Integer.valueOf(record.getToolCallCount()));
            summary.put("attempts", Integer.valueOf(record.getAttempts()));
            return summary;
        } catch (Exception e) {
            log.debug("activeRunSummary failed: sourceKey={}, error={}", sourceKey, safeError(e));
            return null;
        }
    }

    /**
     * 停止全部Running运行。
     *
     * @return 返回全部Running运行结果。
     */
    @Override
    public int stopAllRunningRuns() {
        return stopAllRunningRuns(null);
    }

    /**
     * 停止全部Running运行。
     *
     * @param resumeReason resume原因参数。
     * @return 返回全部Running运行结果。
     */
    @Override
    public int stopAllRunningRuns(String resumeReason) {
        int stopped = 0;
        for (String sourceKey : new ArrayList<String>(runningRuns.keySet())) {
            if (StrUtil.isNotBlank(resumeReason)) {
                markSessionResumePending(sourceKey, resumeReason);
            }
            if (stop(sourceKey).isActiveRun()) {
                stopped++;
            }
        }
        return stopped;
    }

    /**
     * 执行lastActivityDescription相关逻辑。
     *
     * @param record 记录参数。
     * @return 返回last Activity Description结果。
     */
    private String lastActivityDescription(AgentRunRecord record) {
        if (record == null) {
            return "unknown";
        }
        String phase = StrUtil.blankToDefault(record.getPhase(), "running");
        if (StrUtil.isNotBlank(record.getModel())) {
            return phase + " " + record.getProvider() + "/" + record.getModel();
        }
        return phase;
    }

    /**
     * 执行coordinate入站消息相关逻辑。
     *
     * @param sourceKey 渠道来源键。
     * @param sessionId 当前会话标识。
     * @param message 平台消息或错误消息。
     * @return 返回coordinate Incoming结果。
     */
    @Override
    public RunBusyDecision coordinateIncoming(
            String sourceKey, String sessionId, GatewayMessage message) throws Exception {
        String key = normalizeSourceKey(sourceKey);
        String policy = normalizeBusyPolicy(appConfig.getTask().getBusyPolicy());
        RunHandle handle;
        AgentRunRecord runningRecord;
        while (true) {
            handle = runningRuns.get(key);
            runningRecord =
                    handle == null || StrUtil.isBlank(handle.runId)
                            ? null
                            : agentRunRepository.findRun(handle.runId);
            if (handle != null
                    && StrUtil.isBlank(handle.runId)
                    && handle.thread == Thread.currentThread()) {
                return RunBusyDecision.runNow(policy);
            }
            if (handle == null
                    || handle.cancelled.get()
                    || (runningRecord != null && runningRecord.isBackgrounded())) {
                RunHandle reservation =
                        new RunHandle(
                                "", sessionId, Thread.currentThread(), System.currentTimeMillis());
                boolean claimed =
                        handle == null
                                ? runningRuns.putIfAbsent(key, reservation) == null
                                : runningRuns.replace(key, handle, reservation);
                if (claimed) {
                    return RunBusyDecision.runNow(policy);
                }
                continue;
            }
            break;
        }
        if (message != null && message.isHeartbeat()) {
            if (runningRecord != null) {
                heartbeat(runningRecord);
                agentRunRepository.saveRun(runningRecord);
                appendRunEvent(
                        runningRecord,
                        "run.heartbeat",
                        "收到 heartbeat，当前 run 保持活跃，不按 busy 策略打断或排队",
                        null);
            }
            RunBusyDecision decision = new RunBusyDecision();
            decision.setPolicy(policy);
            decision.setStatus("heartbeat");
            decision.setRunId(handle.runId);
            decision.setMessage("HEARTBEAT_OK");
            return decision;
        }
        if ("interrupt".equals(policy)) {
            AgentRunRecord active = runningRecord;
            if (active != null) {
                active.setStatus("interrupting");
                active.setPhase("interrupting");
                active.setLastActivityAt(System.currentTimeMillis());
                active.setExitReason("busy_interrupt");
                agentRunRepository.saveRun(active);
                appendRunEvent(active, "run.interrupting", "收到新消息，按 interrupt 策略打断当前 run", null);
            }
            if (StrUtil.isNotBlank(handle.runId)) {
                recordCommand(
                        handle.runId, key, "interrupt", "{\"reason\":\"busy_policy\"}", "handled");
            }
            stopHandle(handle);
            RunHandle reservation =
                    new RunHandle(
                            "", sessionId, Thread.currentThread(), System.currentTimeMillis());
            if (runningRuns.replace(key, handle, reservation)) {
                return RunBusyDecision.runNow(policy);
            }
            return coordinateIncoming(sourceKey, sessionId, message);
        }
        if ("steer".equals(policy)) {
            if (StrUtil.isBlank(handle.runId)) {
                RunBusyDecision queued = queueIncoming(key, sessionId, message);
                queued.setPolicy(policy);
                return queued;
            }
            String text = message == null ? "" : message.getText();
            recordCommand(
                    handle.runId,
                    key,
                    "steer",
                    "{\"instruction\":\"" + escapeJson(AgentRunContext.safe(text, 2000)) + "\"}",
                    "pending");
            AgentRunRecord active = agentRunRepository.findRun(handle.runId);
            if (active != null) {
                appendRunEvent(active, "run.steer", "收到运行中 steer 指令，下一轮模型调用前注入", null);
            }
            RunBusyDecision decision = new RunBusyDecision();
            decision.setPolicy(policy);
            decision.setStatus("steered");
            decision.setRunId(handle.runId);
            decision.setMessage("已将新消息注入当前长任务。");
            return decision;
        }
        if ("reject".equals(policy)) {
            AgentRunRecord active = agentRunRepository.findRun(handle.runId);
            if (active != null) {
                appendRunEvent(active, "run.rejected", "同一会话已有运行中任务，按 reject 策略拒绝新消息", null);
            }
            RunBusyDecision decision = new RunBusyDecision();
            decision.setPolicy(policy);
            decision.setStatus("rejected");
            decision.setRunId(handle.runId);
            decision.setRejected(true);
            decision.setMessage("当前会话已有任务在运行，请稍后再试，或先停止当前任务。");
            return decision;
        }
        QueuedRunMessage queued = queueMessage(key, sessionId, message, policy);
        RunBusyDecision decision = new RunBusyDecision();
        decision.setPolicy(policy);
        decision.setStatus("queued");
        decision.setRunId(queued.getRunId());
        decision.setQueueId(queued.getQueueId());
        decision.setQueued(true);
        decision.setMessage("当前会话已有任务在运行，新消息已排队。");
        return decision;
    }

    /**
     * 加入队列入站消息。
     *
     * @param sourceKey 渠道来源键。
     * @param sessionId 当前会话标识。
     * @param message 平台消息或错误消息。
     * @return 返回queue Incoming结果。
     */
    @Override
    public RunBusyDecision queueIncoming(String sourceKey, String sessionId, GatewayMessage message)
            throws Exception {
        String key = normalizeSourceKey(sourceKey);
        QueuedRunMessage queued = queueMessage(key, sessionId, message, "queue");
        RunBusyDecision decision = new RunBusyDecision();
        decision.setPolicy("queue");
        decision.setStatus("queued");
        decision.setRunId(queued.getRunId());
        decision.setQueueId(queued.getQueueId());
        decision.setQueued(true);
        decision.setMessage("已加入下一轮队列。");
        return decision;
    }

    /**
     * 执行steer入站消息相关逻辑。
     *
     * @param sourceKey 渠道来源键。
     * @param sessionId 当前会话标识。
     * @param message 平台消息或错误消息。
     * @return 返回steer Incoming结果。
     */
    @Override
    public RunBusyDecision steerIncoming(String sourceKey, String sessionId, GatewayMessage message)
            throws Exception {
        String key = normalizeSourceKey(sourceKey);
        RunHandle handle = runningRuns.get(key);
        if (handle == null || handle.cancelled.get()) {
            return RunBusyDecision.runNow("steer");
        }
        AgentRunRecord runningRecord = agentRunRepository.findRun(handle.runId);
        if (runningRecord != null && runningRecord.isBackgrounded()) {
            return RunBusyDecision.runNow("steer");
        }
        String text = message == null ? "" : message.getText();
        recordCommand(
                handle.runId,
                key,
                "steer",
                "{\"instruction\":\"" + escapeJson(AgentRunContext.safe(text, 2000)) + "\"}",
                "pending");
        AgentRunRecord active = agentRunRepository.findRun(handle.runId);
        if (active != null) {
            appendRunEvent(active, "run.steer", "收到 /steer 指令，下一轮模型调用前注入", null);
        }
        RunBusyDecision decision = new RunBusyDecision();
        decision.setPolicy("steer");
        decision.setStatus("steered");
        decision.setRunId(handle.runId);
        decision.setMessage("已将 steer 指令注入当前任务。");
        return decision;
    }

    /**
     * 执行控制运行相关逻辑。
     *
     * @param runId 运行标识。
     * @param command 待执行或解析的命令文本。
     * @param payload 待签名或解析的载荷内容。
     * @return 返回control运行结果。
     */
    @Override
    public Map<String, Object> controlRun(String runId, String command, Map<String, Object> payload)
            throws Exception {
        AgentRunRecord record = agentRunRepository.findRun(runId);
        if (record == null) {
            throw new IllegalArgumentException("Run not found: " + runId);
        }
        String normalized = StrUtil.blankToDefault(command, "").trim().toLowerCase(Locale.ROOT);
        String payloadJson = payload == null ? null : org.noear.snack4.ONode.serialize(payload);
        recordCommand(runId, record.getSourceKey(), normalized, payloadJson, "handled");
        Map<String, Object> result = new java.util.LinkedHashMap<String, Object>();
        result.put("run_id", runId);
        result.put("command", normalized);
        if ("cancel".equals(normalized)
                || "interrupt".equals(normalized)
                || "stop".equals(normalized)) {
            record.setStatus("interrupting");
            record.setPhase("interrupting");
            record.setLastActivityAt(System.currentTimeMillis());
            agentRunRepository.saveRun(record);
            appendRunEvent(
                    record, "run.control." + normalized, "收到控制命令：" + normalized, payloadJson);
            result.put("result", stop(record.getSourceKey()));
            result.put("ok", true);
            result.put("status", "interrupting");
            return result;
        }
        if ("background".equals(normalized)) {
            record.setStatus("backgrounded");
            record.setPhase("backgrounded");
            record.setBackgrounded(true);
            record.setLastActivityAt(System.currentTimeMillis());
            agentRunRepository.saveRun(record);
            appendRunEvent(record, "run.backgrounded", "run 已转入后台继续执行", payloadJson);
            result.put("ok", true);
            result.put("status", "backgrounded");
            return result;
        }
        if ("resume".equals(normalized)) {
            boolean resumed =
                    pendingSessionResumer != null
                            && pendingSessionResumer.test(
                                    record.getSourceKey(), record.getSessionId());
            appendRunEvent(
                    record,
                    "run.resume",
                    resumed ? "Dashboard 已恢复 pending 会话" : "Dashboard 恢复 pending 会话失败",
                    payloadJson);
            result.put("ok", Boolean.valueOf(resumed));
            result.put("status", resumed ? "resumed" : "resume_failed");
            return result;
        }
        if ("steer".equals(normalized)) {
            recordCommand(runId, record.getSourceKey(), "steer", payloadJson, "pending");
            appendRunEvent(record, "run.steer", "Dashboard 注入 steer 指令", payloadJson);
            result.put("ok", true);
            result.put("status", "steered");
            return result;
        }
        result.put("ok", false);
        result.put("status", "unsupported_command");
        return result;
    }

    /**
     * 消费Steer指令。
     *
     * @param runId 运行标识。
     * @return 返回consume Steer Instruction结果。
     */
    @Override
    public String consumeSteerInstruction(String runId) {
        try {
            RunControlCommand command = agentRunRepository.findLatestPendingCommand(runId, "steer");
            if (command == null) {
                return null;
            }
            agentRunRepository.markRunControlCommandHandled(
                    command.getCommandId(), "handled", System.currentTimeMillis());
            String payload = command.getPayloadJson();
            if (StrUtil.isBlank(payload)) {
                return null;
            }
            Object parsed = org.noear.snack4.ONode.deserialize(payload, Object.class);
            if (parsed instanceof Map) {
                Object instruction = ((Map<?, ?>) parsed).get("instruction");
                return instruction == null ? payload : String.valueOf(instruction);
            }
            return payload;
        } catch (Exception e) {
            log.warn("consume steer instruction failed: runId={}, error={}", runId, safeError(e));
            return null;
        }
    }

    /**
     * 响应运行Finished事件。
     *
     * @param sourceKey 渠道来源键。
     * @param sessionId 当前会话标识。
     * @param runner runner 参数。
     */
    @Override
    public void onRunFinished(
            String sourceKey, String sessionId, Function<GatewayMessage, GatewayReply> runner) {
        String key = normalizeSourceKey(sourceKey);
        if (isRunning(key)) {
            return;
        }
        AtomicBoolean draining =
                drainingQueues.computeIfAbsent(key, ignored -> new AtomicBoolean(false));
        if (!draining.compareAndSet(false, true)) {
            return;
        }
        Thread thread =
                new Thread(
                        ProfileRuntimeScope.capture(
                                () -> {
                                    try {
                                        drainQueue(key, sessionId, runner);
                                    } finally {
                                        draining.set(false);
                                        if (!isRunning(key) && hasQueuedMessage(key, sessionId)) {
                                            onRunFinished(key, sessionId, runner);
                                        }
                                    }
                                }),
                        "jimuqu-run-queue-" + Math.abs(key.hashCode()));
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 判断是否Running。
     *
     * @param sourceKey 渠道来源键。
     * @return 如果Running满足条件则返回 true，否则返回 false。
     */
    @Override
    public boolean isRunning(String sourceKey) {
        RunHandle handle = runningRuns.get(normalizeSourceKey(sourceKey));
        return handle != null && !handle.cancelled.get();
    }

    /**
     * 判断是否存在Running运行。
     *
     * @return 如果Running运行满足条件则返回 true，否则返回 false。
     */
    @Override
    public boolean hasRunningRuns() {
        return !runningRuns.isEmpty();
    }

    /**
     * 执行running运行次数相关逻辑。
     *
     * @return 返回running运行次数结果。
     */
    @Override
    public int runningRunCount() {
        return runningRuns.size();
    }

    /**
     * 执行last运行Finished时间相关逻辑。
     *
     * @return 返回last运行Finished时间结果。
     */
    @Override
    public long lastRunFinishedAt() {
        return lastRunFinishedAt;
    }

    /**
     * 执行异步任务主体；未显式传入 Web 工具策略时保持历史默认行为。
     *
     * @param session 会话参数。
     * @param systemPrompt 系统提示词参数。
     * @param userMessage 用户消息参数。
     * @param tools tools 参数。
     * @param feedbackSink 反馈Sink参数。
     * @param eventSink 事件Sink参数。
     * @param resume resume 参数。
     * @param agentScope 当前运行冻结后的 Agent 范围。
     * @param userAttachments 用户Attachments参数。
     * @param memoryPrefetchContext 本轮预取的临时记忆上下文。
     * @return 返回运行结果。
     */
    public AgentRunOutcome run(
            SessionRecord session,
            String systemPrompt,
            String userMessage,
            List<Object> tools,
            ConversationFeedbackSink feedbackSink,
            ConversationEventSink eventSink,
            boolean resume,
            AgentRuntimeScope agentScope,
            List<MessageAttachment> userAttachments,
            String memoryPrefetchContext)
            throws Exception {
        return run(
                session,
                systemPrompt,
                userMessage,
                tools,
                feedbackSink,
                eventSink,
                resume,
                agentScope,
                userAttachments,
                memoryPrefetchContext,
                java.util.Collections.<String>emptyList(),
                java.util.Collections.<String>emptyList(),
                null);
    }

    /**
     * 执行异步任务主体，并携带本轮临时记忆召回上下文。
     *
     * @param session 会话参数。
     * @param systemPrompt 系统提示词参数。
     * @param userMessage 用户消息参数。
     * @param tools tools 参数。
     * @param feedbackSink 反馈Sink参数。
     * @param eventSink 事件Sink参数。
     * @param resume resume 参数。
     * @param agentScope 当前运行冻结后的 Agent 范围。
     * @param userAttachments 用户Attachments参数。
     * @param memoryPrefetchContext 本轮预取的临时记忆上下文。
     * @param allowedToolNames 本轮允许调用的工具名称白名单。
     * @param requiredToolNames 本轮必须真实完成的工具名称列表。
     * @param maxToolCalls 本轮允许尝试的最大工具调用次数。
     * @return 返回运行结果。
     */
    public AgentRunOutcome run(
            SessionRecord session,
            String systemPrompt,
            String userMessage,
            List<Object> tools,
            ConversationFeedbackSink feedbackSink,
            ConversationEventSink eventSink,
            boolean resume,
            AgentRuntimeScope agentScope,
            List<MessageAttachment> userAttachments,
            String memoryPrefetchContext,
            List<String> allowedToolNames,
            List<String> requiredToolNames,
            Integer maxToolCalls)
            throws Exception {
        if (agentScope == null) {
            agentScope = new AgentRuntimeScope();
            agentScope.setAgentName(
                    AgentRuntimeScope.normalizeName(
                            session == null ? null : session.getActiveAgentName()));
            agentScope.setWorkspaceDir(appConfig.getWorkspace().getDir());
            agentScope.setSkillsDir(appConfig.getRuntime().getSkillsDir());
            agentScope.setCacheDir(appConfig.getRuntime().getCacheDir());
        }
        long now = System.currentTimeMillis();
        String queuedRunId = extractQueuedMarker(userMessage, QUEUED_RUN_ID_KEY);
        userMessage = stripQueuedMarkers(userMessage);
        AgentRunRecord runRecord =
                StrUtil.isBlank(queuedRunId) ? null : agentRunRepository.findRun(queuedRunId);
        if (runRecord == null) {
            runRecord = new AgentRunRecord();
            runRecord.setRunId(IdSupport.newId());
            runRecord.setSessionId(session.getSessionId());
            runRecord.setSourceKey(session.getSourceKey());
        }
        AgentRunContext parentContext = AgentRunContext.current();
        boolean subagentRun =
                parentContext != null
                        && !StrUtil.equals(parentContext.getSourceKey(), session.getSourceKey());
        runRecord.setRunKind(subagentRun ? "subagent" : (resume ? "resume" : "conversation"));
        runRecord.setParentRunId(subagentRun ? parentContext.getRunId() : null);
        runRecord.setAgentName(agentScope.getEffectiveName());
        runRecord.setAgentSnapshotJson(agentScope.getSnapshotJson());
        runRecord.setStatus("running");
        runRecord.setPhase("queued");
        runRecord.setBusyPolicy(normalizeBusyPolicy(appConfig.getTask().getBusyPolicy()));
        runRecord.setInputPreview(AgentRunContext.safe(userMessage, 1000));
        if (runRecord.getQueuedAt() <= 0) {
            runRecord.setQueuedAt(now);
        }
        runRecord.setStartedAt(now);
        runRecord.setHeartbeatAt(now);
        runRecord.setLastActivityAt(now);
        AgentRunContext runContext =
                new AgentRunContext(
                        agentRunRepository,
                        runRecord.getRunId(),
                        session.getSessionId(),
                        session.getSourceKey());
        runContext.setRunKind(runRecord.getRunKind());
        runContext.setParentRunId(runRecord.getParentRunId());
        runContext.setUserAttachments(userAttachments);
        runContext.setWorkspaceDir(agentScope.getWorkspaceDir());
        runContext.setToolPolicy(allowedToolNames, maxToolCalls);
        if (!resume && StrUtil.isNotBlank(memoryPrefetchContext)) {
            runContext.setMemoryPrefetchContext(userMessage, memoryPrefetchContext);
        }
        RunHandle runHandle =
                registerRun(
                        session.getSourceKey(), runRecord.getRunId(), session.getSessionId(), now);
        AgentRunContext previousContext = AgentRunContext.current();
        AgentRunContext.setCurrent(runContext);
        try {
            agentRunRepository.saveRun(runRecord);
            pruneOldRuns();

            updateRunPhase(runRecord, "running");
            runContext.setPhase("running");
            runContext.event("run.start", resume ? "恢复挂起会话" : "开始执行用户请求");
            eventSink.onRunStarted(session.getSessionId());

            CandidatePlan candidatePlan = buildCandidateConfigs(session, agentScope);
            List<AppConfig.LlmConfig> candidates = candidatePlan.candidates;
            List<CandidateFailure> candidateFailures = candidatePlan.failures;
            for (CandidateFailure skipped : candidateFailures) {
                runContext.event(
                        "fallback.skipped", "跳过不可用模型候选：" + skipped.display(), skipped.metadata());
            }
            Throwable lastError = null;
            LlmResult finalResult = null;
            String replyText = "";
            String previousProvider = null;
            int attemptNo = 0;
            String compressionWarning = "";
            int contextEstimateTokens = 0;
            int contextWindowTokens = 0;
            boolean continueFromSnapshot = resume;

            for (int candidateIndex = 0; candidateIndex < candidates.size(); candidateIndex++) {
                checkCancellation(session.getSourceKey());
                AppConfig.LlmConfig resolved = candidates.get(candidateIndex);
                int maxAttempts = Math.max(1, appConfig.getTrace().getMaxAttempts());
                boolean allowFallback = true;
                boolean compressionRecoveryAttempted = false;
                boolean skipNextPreflightCompression = false;
                List<MessageAttachment> candidateAttachments = runContext.getUserAttachments();
                for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                    checkCancellation(session.getSourceKey());
                    attemptNo++;
                    runContext.setAttempt(attemptNo, resolved.getProvider(), resolved.getModel());
                    updateRunPhase(runRecord, "model");
                    runRecord.setAttempts(attemptNo);
                    runRecord.setProvider(resolved.getProvider());
                    runRecord.setModel(resolved.getModel());
                    heartbeat(runRecord);
                    agentRunRepository.saveRun(runRecord);
                    eventSink.onAttemptStarted(
                            runRecord.getRunId(),
                            attemptNo,
                            resolved.getProvider(),
                            resolved.getModel());
                    runContext.event(
                            "attempt.start",
                            "开始第 "
                                    + attemptNo
                                    + " 次尝试："
                                    + resolved.getProvider()
                                    + "/"
                                    + resolved.getModel(),
                            attemptMetadata(resolved, attemptNo, candidateIndex));

                    String previousNdjson = session.getNdjson();
                    try {
                        String steer = consumeSteerInstruction(runRecord.getRunId());
                        String effectiveUserMessage = userMessage;
                        if (StrUtil.isNotBlank(steer)) {
                            effectiveUserMessage =
                                    StrUtil.blankToDefault(userMessage, "")
                                            + "\n\n[运行中追加指令]\n"
                                            + steer;
                            runContext.event("run.steer.injected", "已将 steer 指令注入本轮模型调用");
                        }
                        CompressionOutcome compression;
                        if (skipNextPreflightCompression) {
                            // 已因上一轮上下文溢出强制压缩，本次重试直接验证结果，避免重复压缩同一会话。
                            compression = CompressionOutcome.skipped(session);
                            skipNextPreflightCompression = false;
                        } else {
                            compression =
                                    compressBeforeAttempt(
                                            session,
                                            systemPrompt,
                                            effectiveUserMessage,
                                            resolved,
                                            tools,
                                            runContext,
                                            eventSink,
                                            runRecord);
                        }
                        session = compression.getSession();
                        if (StrUtil.isBlank(compressionWarning)
                                && StrUtil.isNotBlank(compression.getWarning())) {
                            compressionWarning = compression.getWarning();
                        }
                        if (compression.getEstimatedTokens() > 0) {
                            contextEstimateTokens = compression.getEstimatedTokens();
                        }
                        runRecord.setContextEstimateTokens(contextEstimateTokens);
                        contextWindowTokens = Math.max(1024, resolved.getContextWindowTokens());
                        runRecord.setContextWindowTokens(contextWindowTokens);
                        heartbeat(runRecord);
                        agentRunRepository.saveRun(runRecord);
                        checkCancellation(session.getSourceKey());
                        previousNdjson = session.getNdjson();
                        LlmResult result =
                                llmGateway.executeOnce(
                                        session,
                                        systemPrompt,
                                        effectiveUserMessage,
                                        tools,
                                        feedbackSink,
                                        eventSink,
                                        resume || continueFromSnapshot,
                                        resolved,
                                        runContext);
                        checkCancellation(session.getSourceKey());
                        String currentReply = extractText(result.getAssistantMessage());
                        if (StrUtil.isBlank(currentReply)
                                && hasRecentToolActivity(previousNdjson, result.getNdjson())) {
                            session.setNdjson(result.getNdjson());
                            checkCancellation(session.getSourceKey());
                            updateRunPhase(runRecord, "recovery");
                            runContext.event("recovery.start", "工具调用后空回复，发起无工具恢复");
                            eventSink.onRecoveryStarted(runRecord.getRunId(), "empty_reply");
                            LlmResult recovered =
                                    recover(
                                            session,
                                            systemPrompt,
                                            AgentRecoveryPromptConstants
                                                    .EMPTY_REPLY_RECOVERY_PROMPT,
                                            resolved,
                                            feedbackSink,
                                            eventSink,
                                            runContext);
                            checkCancellation(session.getSourceKey());
                            if (hasUsableRecoveryReply(recovered)) {
                                correctContradictingRecoveryReply(result, recovered);
                                mergeUsage(result, recovered);
                                applyRecoveredTranscript(result, recovered);
                                result = recovered;
                                currentReply = extractText(recovered.getAssistantMessage());
                            }
                        }

                        if (isMaxStepsReply(currentReply)) {
                            session.setNdjson(result.getNdjson());
                            checkCancellation(session.getSourceKey());
                            updateRunPhase(runRecord, "recovery");
                            runContext.event("recovery.start", "达到最大步骤上限，发起收敛总结");
                            eventSink.onRecoveryStarted(runRecord.getRunId(), "max_steps");
                            LlmResult recovered =
                                    recover(
                                            session,
                                            systemPrompt,
                                            AgentRecoveryPromptConstants
                                                    .MAX_STEPS_TOOL_AWARE_RECOVERY_PROMPT,
                                            resolved,
                                            feedbackSink,
                                            eventSink,
                                            runContext);
                            checkCancellation(session.getSourceKey());
                            if (hasUsableRecoveryReply(recovered)) {
                                correctContradictingRecoveryReply(result, recovered);
                                mergeUsage(result, recovered);
                                applyRecoveredTranscript(result, recovered);
                                result = recovered;
                                currentReply = extractText(recovered.getAssistantMessage());
                            } else {
                                currentReply =
                                        AgentRecoveryPromptConstants.MAX_STEPS_RECOVERY_FALLBACK;
                            }
                        }

                        validateRequiredTools(runRecord, runContext, requiredToolNames);
                        if (StrUtil.isNotBlank(currentReply) || hasVisibleContent(result)) {
                            finalResult = result;
                            replyText =
                                    StrUtil.blankToDefault(
                                            currentReply,
                                            AgentRecoveryPromptConstants.EMPTY_REPLY_FALLBACK);
                            eventSink.onAttemptCompleted(
                                    runRecord.getRunId(), attemptNo, "success", "");
                            runContext.event("attempt.success", "第 " + attemptNo + " 次尝试成功");
                            break;
                        }

                        lastError =
                                new IllegalStateException("LLM returned empty assistant content");
                        eventSink.onAttemptCompleted(
                                runRecord.getRunId(),
                                attemptNo,
                                "empty",
                                "LLM returned empty assistant content");
                        runContext.event(
                                "attempt.empty",
                                "模型返回空内容",
                                errorMetadata(lastError, resolved, attemptNo, candidateIndex));
                    } catch (AgentRunCancelledException e) {
                        throw e;
                    } catch (Exception e) {
                        if (isCancellationRequested(session.getSourceKey())) {
                            throw new AgentRunCancelledException();
                        }
                        updateRunPhase(runRecord, "retry");
                        lastError = e;
                        String errorMessage = safeError(e);
                        eventSink.onAttemptCompleted(
                                runRecord.getRunId(), attemptNo, "error", errorMessage);
                        runContext.event(
                                "attempt.error",
                                "第 " + attemptNo + " 次尝试失败：" + errorMessage,
                                errorMetadata(e, resolved, attemptNo, candidateIndex));
                        if (isRequiredToolsMissing(e)) {
                            allowFallback = false;
                            break;
                        }
                        if (!StrUtil.equals(previousNdjson, session.getNdjson())) {
                            continueFromSnapshot = true;
                            if (hasRecentToolActivity(previousNdjson, session.getNdjson())) {
                                sessionRepository.save(session);
                            }
                        }
                        LlmErrorClassifier.ClassifiedError classified =
                                LlmErrorClassifier.classify(e);
                        allowFallback = classified.isShouldFallback();
                        boolean retrySameProvider =
                                classified.shouldRetrySameProvider(attempt, maxAttempts);
                        boolean overflowRetryFailed =
                                classified.getReason()
                                                == LlmErrorClassifier.FailoverReason
                                                        .CONTEXT_OVERFLOW
                                        && compressionRecoveryAttempted;
                        if (retrySameProvider
                                && classified.isShouldCompress()
                                && !compressionRecoveryAttempted) {
                            compressionRecoveryAttempted = true;
                            if (classified.getReason()
                                            == LlmErrorClassifier.FailoverReason.PAYLOAD_TOO_LARGE
                                    && !candidateAttachments.isEmpty()) {
                                runContext.setUserAttachments(
                                        Collections.<MessageAttachment>emptyList());
                                runContext.event(
                                        "attachment.retry.unloaded",
                                        "请求载荷过大，已移除本次重试的附件载荷",
                                        runContext.metadata(
                                                "attachmentCount",
                                                Integer.valueOf(candidateAttachments.size())));
                            } else {
                                CompressionOutcome recoveryCompression =
                                        compressAfterOverflow(
                                                session,
                                                systemPrompt,
                                                resolved,
                                                classified,
                                                runContext,
                                                eventSink,
                                                runRecord);
                                session = recoveryCompression.getSession();
                                skipNextPreflightCompression = true;
                                if (StrUtil.isBlank(compressionWarning)
                                        && StrUtil.isNotBlank(recoveryCompression.getWarning())) {
                                    compressionWarning = recoveryCompression.getWarning();
                                }
                            }
                        }
                        if (overflowRetryFailed) {
                            // 已完成一次压缩且同一提供方重试仍溢出时，交由后续候选模型尝试更大的上下文窗口。
                            allowFallback = true;
                            retrySameProvider = false;
                        }
                        if (retrySameProvider) {
                            continue;
                        }
                        break;
                    }
                }

                runContext.setUserAttachments(candidateAttachments);

                if (finalResult != null) {
                    break;
                }

                candidateFailures.add(
                        CandidateFailure.attempted(
                                resolved,
                                lastError == null ? "EMPTY_RESPONSE" : classifiedReason(lastError),
                                lastError == null ? "模型返回空内容" : safeError(lastError)));

                previousProvider = resolved.getProvider();
                if (isRequiredToolsMissing(lastError)) {
                    break;
                }
                if (!allowFallback) {
                    break;
                }
                if (candidateIndex + 1 < candidates.size()) {
                    AppConfig.LlmConfig next = candidates.get(candidateIndex + 1);
                    runRecord.setFallbackCount(runRecord.getFallbackCount() + 1);
                    updateRunPhase(runRecord, "fallback");
                    eventSink.onFallback(
                            runRecord.getRunId(),
                            previousProvider,
                            next.getProvider(),
                            lastError == null ? "empty response" : safeError(lastError));
                    runContext.event(
                            "fallback",
                            "切换 fallback provider："
                                    + previousProvider
                                    + " -> "
                                    + next.getProvider(),
                            fallbackMetadata(previousProvider, next, lastError));
                }
            }

            if (finalResult == null) {
                runRecord.setStatus("failed");
                runRecord.setPhase("failed");
                runRecord.setExitReason("failed");
                runRecord.setFinishedAt(System.currentTimeMillis());
                runRecord.setError(
                        isRequiredToolsMissing(lastError)
                                ? safeError(lastError)
                                : candidateFailureSummary(candidateFailures));
                agentRunRepository.saveRun(runRecord);
                runContext.event("run.failed", runRecord.getError());
                if (isRequiredToolsMissing(lastError) && lastError instanceof Exception) {
                    throw (Exception) lastError;
                }
                throw new IllegalStateException(runRecord.getError(), lastError);
            }

            checkCancellation(session.getSourceKey());
            session.setNdjson(finalResult.getNdjson());
            applyUsage(session, finalResult);
            session.setUpdatedAt(System.currentTimeMillis());
            sessionRepository.save(session);

            runRecord.setStatus("success");
            runRecord.setPhase("completed");
            runRecord.setFinalReplyPreview(AgentRunContext.safe(replyText, 1000));
            runRecord.setInputTokens(finalResult.getInputTokens());
            runRecord.setOutputTokens(finalResult.getOutputTokens());
            runRecord.setTotalTokens(finalResult.getTotalTokens());
            runRecord.setProvider(finalResult.getProvider());
            runRecord.setModel(finalResult.getModel());
            runRecord.setFinishedAt(System.currentTimeMillis());
            runRecord.setExitReason("success");
            heartbeat(runRecord);
            agentRunRepository.saveRun(runRecord);
            recordUsageEvent(runRecord, finalResult);
            runContext.event("run.success", "运行完成");

            AgentRunOutcome outcome = new AgentRunOutcome();
            outcome.setFinalReply(replyText);
            outcome.setResult(finalResult);
            outcome.setRunRecord(runRecord);
            outcome.setCompressionWarning(compressionWarning);
            outcome.setModel(finalResult.getModel());
            outcome.setProvider(finalResult.getProvider());
            outcome.setContextEstimateTokens(contextEstimateTokens);
            outcome.setContextWindowTokens(contextWindowTokens);
            outcome.setCwd(resolveOutcomeCwd(agentScope));
            return outcome;
        } catch (AgentRunCancelledException e) {
            runRecord.setStatus("cancelled");
            runRecord.setPhase("cancelled");
            runRecord.setExitReason("cancelled");
            runRecord.setFinishedAt(System.currentTimeMillis());
            runRecord.setError(safeError(e));
            heartbeat(runRecord);
            agentRunRepository.saveRun(runRecord);
            runContext.event("run.cancelled", safeError(e));
            throw e;
        } finally {
            SubprocessEnvironmentSanitizer.clearSkillEnvironmentPassthrough();
            unregisterRun(session.getSourceKey(), runHandle);
            AgentRunContext.setCurrent(previousContext);
            if (runHandle.cancelled.get()) {
                Thread.interrupted();
            }
            lastRunFinishedAt = System.currentTimeMillis();
        }
    }

    /** 解析运行结果工作区，命名 Profile 不允许回退主进程 user.dir。 */
    private String resolveOutcomeCwd(AgentRuntimeScope agentScope) {
        if (agentScope != null && StrUtil.isNotBlank(agentScope.getWorkspaceDir())) {
            return agentScope.getWorkspaceDir();
        }
        ProfileRuntimeScope.Context scoped = ProfileRuntimeScope.current();
        if (scoped != null && scoped.getHome() != null) {
            return scoped.getHome().toString();
        }
        if (appConfig != null
                && appConfig.getWorkspace() != null
                && StrUtil.isNotBlank(appConfig.getWorkspace().getDir())) {
            return appConfig.getWorkspace().getDir();
        }
        if (appConfig != null
                && appConfig.getRuntime() != null
                && StrUtil.isNotBlank(appConfig.getRuntime().getHome())) {
            return appConfig.getRuntime().getHome();
        }
        return System.getProperty("user.dir");
    }

    /**
     * 执行压缩BeforeAttempt相关逻辑。
     *
     * @param session 会话参数。
     * @param systemPrompt 系统提示词参数。
     * @param userMessage 用户消息参数。
     * @param resolved resolved 参数。
     * @param tools 本轮实际发送给模型的工具对象。
     * @param runContext 运行上下文。
     * @param eventSink 事件Sink参数。
     * @param runRecord 当前运行记录，压缩次数必须写回同一对象，避免后续保存覆盖。
     * @return 返回压缩Before Attempt结果。
     */
    private CompressionOutcome compressBeforeAttempt(
            SessionRecord session,
            String systemPrompt,
            String userMessage,
            AppConfig.LlmConfig resolved,
            List<Object> tools,
            AgentRunContext runContext,
            ConversationEventSink eventSink,
            AgentRunRecord runRecord)
            throws Exception {
        String runId = runRecord == null ? "" : runRecord.getRunId();
        String budgetUserMessage =
                MemoryContextBoundary.appendPrefetchedContext(
                        userMessage,
                        runContext == null ? null : runContext.getMemoryPrefetchUserMessage(),
                        runContext == null ? null : runContext.getMemoryPrefetchContext());
        ContextBudgetDecision decision =
                contextBudgetService.decide(
                        session, systemPrompt, budgetUserMessage, resolved, tools);
        if (!decision.isShouldCompress()) {
            runContext.setPhase("compression");
            eventSink.onCompressionDecision(
                    runId,
                    false,
                    decision.getReason(),
                    decision.getEstimatedTokens(),
                    decision.getThresholdTokens());
            runContext.event(
                    "compression.skip",
                    decision.getReason(),
                    runContext.metadata("estimatedTokens", decision.getEstimatedTokens()));
            CompressionOutcome skipped = CompressionOutcome.skipped(session);
            skipped.setEstimatedTokens(decision.getEstimatedTokens());
            skipped.setThresholdTokens(decision.getThresholdTokens());
            return skipped;
        }

        SessionRecord before = cloneSessionState(session);
        runContext.setPhase("compression");
        CompressionOutcome outcome =
                contextCompressionService.compressNowWithOutcome(
                        session, systemPrompt, userMessage);
        SessionRecord compressed = outcome.getSession();
        boolean changed = !StrUtil.equals(before.getNdjson(), compressed.getNdjson());
        eventSink.onCompressionDecision(
                runId,
                changed,
                decision.getReason(),
                decision.getEstimatedTokens(),
                decision.getThresholdTokens());
        String eventType =
                outcome.isFailed()
                        ? "compression.failed"
                        : (changed ? "compression.done" : "compression.unchanged");
        runContext.event(
                eventType,
                outcome.isFailed() ? outcome.getErrorMessage() : decision.getReason(),
                runContext.metadata("estimatedTokens", decision.getEstimatedTokens()));
        if (changed) {
            sessionRepository.save(compressed);
        }
        if (runRecord != null) {
            runRecord.setCompressionCount(runRecord.getCompressionCount() + 1);
            runRecord.setContextEstimateTokens(decision.getEstimatedTokens());
            runRecord.setContextWindowTokens(decision.getThresholdTokens());
            heartbeat(runRecord);
            agentRunRepository.saveRun(runRecord);
        }
        outcome.setEstimatedTokens(decision.getEstimatedTokens());
        outcome.setThresholdTokens(decision.getThresholdTokens());
        return outcome;
    }

    /**
     * 在提供方明确拒绝上下文或载荷后强制压缩一次，供同一提供方的下一次请求使用。
     *
     * @param session 当前会话。
     * @param systemPrompt 当前系统提示词。
     * @param resolved 当前模型配置。
     * @param classified 提供方错误分类。
     * @param runContext 当前运行上下文。
     * @param eventSink 运行事件接收器。
     * @param runRecord 当前运行记录。
     * @return 压缩结果。
     */
    private CompressionOutcome compressAfterOverflow(
            SessionRecord session,
            String systemPrompt,
            AppConfig.LlmConfig resolved,
            LlmErrorClassifier.ClassifiedError classified,
            AgentRunContext runContext,
            ConversationEventSink eventSink,
            AgentRunRecord runRecord)
            throws Exception {
        String runId = runRecord == null ? "" : runRecord.getRunId();
        SessionRecord before = cloneSessionState(session);
        runContext.setPhase("compression");
        CompressionOutcome outcome =
                contextCompressionService.compressNowWithOutcome(session, systemPrompt);
        SessionRecord compressed = outcome.getSession();
        boolean changed = !StrUtil.equals(before.getNdjson(), compressed.getNdjson());
        String reason = "provider_" + classified.getReason().name().toLowerCase(Locale.ROOT);
        eventSink.onCompressionDecision(runId, changed, reason, 0, 0);
        runContext.event(
                outcome.isFailed()
                        ? "compression.retry.failed"
                        : (changed ? "compression.retry.done" : "compression.retry.unchanged"),
                outcome.isFailed() ? outcome.getErrorMessage() : reason,
                runContext.metadata("provider", resolved.getProvider()));
        if (changed) {
            sessionRepository.save(compressed);
        }
        if (runRecord != null) {
            runRecord.setCompressionCount(runRecord.getCompressionCount() + 1);
            heartbeat(runRecord);
            agentRunRepository.saveRun(runRecord);
        }
        return outcome;
    }

    /**
     * 克隆会话状态。
     *
     * @param source 来源参数。
     * @return 返回clone会话状态。
     */
    private SessionRecord cloneSessionState(SessionRecord source) {
        SessionRecord clone = new SessionRecord();
        clone.setNdjson(source.getNdjson());
        return clone;
    }

    /**
     * 执行recover相关逻辑。
     *
     * @param session 会话参数。
     * @param systemPrompt 系统提示词参数。
     * @param prompt 提示词参数。
     * @param resolved resolved 参数。
     * @param feedbackSink 反馈Sink参数。
     * @param eventSink 事件Sink参数。
     * @param runContext 运行上下文。
     * @return 返回recover结果。
     */
    private LlmResult recover(
            SessionRecord session,
            String systemPrompt,
            String prompt,
            AppConfig.LlmConfig resolved,
            ConversationFeedbackSink feedbackSink,
            ConversationEventSink eventSink,
            AgentRunContext runContext) {
        java.util.List<MessageAttachment> previousAttachments =
                runContext == null
                        ? Collections.<MessageAttachment>emptyList()
                        : runContext.getUserAttachments();
        try {
            if (runContext != null) {
                runContext.setUserAttachments(Collections.<MessageAttachment>emptyList());
            }
            return llmGateway.executeOnce(
                    session,
                    systemPrompt,
                    prompt,
                    Collections.emptyList(),
                    feedbackSink,
                    eventSink,
                    false,
                    resolved,
                    runContext);
        } catch (Exception e) {
            String error = safeError(e);
            runContext.event("recovery.error", error);
            log.warn(
                    "Agent recovery failed: sessionId={}, error={}", session.getSessionId(), error);
            return null;
        } finally {
            if (runContext != null) {
                runContext.setUserAttachments(previousAttachments);
            }
        }
    }

    /**
     * 构建Candidate Configs。
     *
     * @param session 会话参数。
     * @param agentScope 当前运行冻结后的 Agent 范围。
     * @return 返回创建好的Candidate Configs。
     */
    private CandidatePlan buildCandidateConfigs(
            SessionRecord session, AgentRuntimeScope agentScope) {
        CandidatePlan plan = new CandidatePlan();
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        try {
            addCandidate(
                    plan,
                    seen,
                    llmProviderService.resolveEffectiveProvider(
                            session, agentScope == null ? null : agentScope.getDefaultModel()));
        } catch (Exception e) {
            plan.failures.add(
                    CandidateFailure.skipped(
                            StrUtil.blankToDefault(
                                    session == null ? "" : session.getTransientProviderOverride(),
                                    appConfig.getModel().getProviderKey()),
                            session == null ? "" : session.getTransientModelOverride(),
                            "CONFIGURATION",
                            safeError(e)));
        }
        List<AppConfig.FallbackProviderConfig> fallbackProviders = appConfig.getFallbackProviders();
        if (fallbackProviders == null) {
            fallbackProviders = Collections.emptyList();
        }
        for (AppConfig.FallbackProviderConfig fallback : fallbackProviders) {
            if (fallback == null || StrUtil.isBlank(fallback.getProvider())) {
                continue;
            }
            try {
                addCandidate(
                        plan,
                        seen,
                        llmProviderService.resolveProvider(
                                fallback.getProvider().trim(), fallback.getModel()));
            } catch (Exception e) {
                plan.failures.add(
                        CandidateFailure.skipped(
                                fallback.getProvider(),
                                fallback.getModel(),
                                "CONFIGURATION",
                                safeError(e)));
            }
        }
        return plan;
    }

    /** 将通过本地预检且未重复的模型候选加入执行计划。 */
    private void addCandidate(
            CandidatePlan plan,
            LinkedHashSet<String> seen,
            LlmProviderService.ResolvedProvider resolved) {
        String failure = llmProviderService.preflightFailure(resolved);
        if (StrUtil.isNotBlank(failure)) {
            plan.failures.add(
                    CandidateFailure.skipped(
                            resolved.getProviderKey(), resolved.getModel(), "PREFLIGHT", failure));
            return;
        }
        AppConfig.LlmConfig candidate = toLlmConfig(resolved);
        if (seen.add(providerSignature(candidate))) {
            plan.candidates.add(candidate);
        }
    }

    /** 返回分类器的稳定原因名，供最终错误逐候选展示。 */
    private String classifiedReason(Throwable error) {
        return LlmErrorClassifier.classify(error).getReason().name();
    }

    /** 汇总所有候选的安全失败原因，避免最终界面只显示最后一次异常。 */
    private String candidateFailureSummary(List<CandidateFailure> failures) {
        if (failures == null || failures.isEmpty()) {
            return "所有模型候选均不可用；请检查 provider、模型、凭据与协议配置。";
        }
        StringBuilder summary = new StringBuilder("所有模型候选均失败：");
        for (int i = 0; i < failures.size(); i++) {
            if (i > 0) {
                summary.append("；");
            }
            summary.append(failures.get(i).display());
        }
        summary.append("。请检查 provider 凭据、模型名、协议配置和服务状态。");
        return SecretRedactor.redact(summary.toString());
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
        return copy;
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
                "Assistant message has no visible content in agent run; suppressing message object"
                        + " fallback: role={}, contentRawType={}, toolCalls={}",
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
     * 判断是否存在Visible Content。
     *
     * @param result 结果响应或执行结果。
     * @return 如果Visible Content满足条件则返回 true，否则返回 false。
     */
    private boolean hasVisibleContent(LlmResult result) {
        return result != null
                && (StrUtil.isNotBlank(extractText(result.getAssistantMessage()))
                        || StrUtil.isNotBlank(MessageSupport.visibleText(result.getRawResponse())));
    }

    /**
     * 校验受控 Web 回归声明的必需工具是否真实完成，避免模型在未调用工具时编造执行结果。
     *
     * @param runRecord 当前运行记录。
     * @param runContext 当前运行上下文。
     * @param requiredToolNames 本轮必须真实完成的工具名称列表。
     */
    private void validateRequiredTools(
            AgentRunRecord runRecord, AgentRunContext runContext, List<String> requiredToolNames)
            throws Exception {
        List<String> required = normalizeRequiredToolNames(requiredToolNames);
        if (required.isEmpty()) {
            return;
        }
        List<com.jimuqu.solon.claw.core.model.ToolCallRecord> calls =
                agentRunRepository.listToolCalls(runRecord.getRunId());
        List<String> completed = new ArrayList<String>();
        for (com.jimuqu.solon.claw.core.model.ToolCallRecord call : calls) {
            if (call != null
                    && StrUtil.isNotBlank(call.getToolName())
                    && "completed".equals(call.getStatus())) {
                completed.add(call.getToolName().trim());
            }
        }
        List<String> missing = new ArrayList<String>();
        for (String toolName : required) {
            if (!completed.contains(toolName)) {
                missing.add(toolName);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        Map<String, Object> metadata = new java.util.LinkedHashMap<String, Object>();
        metadata.put("required_tools", required);
        metadata.put("completed_tools", completed);
        metadata.put("missing_tools", missing);
        metadata.put("tool_call_count", Integer.valueOf(calls.size()));
        String message = "必需工具未真实完成：" + missing + "；模型不能在未调用工具时报告工具执行结果。";
        if (runContext != null) {
            runContext.event("tool.required.missing", message, metadata);
        }
        throw new RequiredToolsMissingException(message);
    }

    /**
     * 归一化必需工具列表，保留调用顺序并去重，空值表示不启用必需工具后验收。
     *
     * @param requiredToolNames 原始工具名称列表。
     * @return 返回可用于后验收的工具名称列表。
     */
    private List<String> normalizeRequiredToolNames(List<String> requiredToolNames) {
        if (requiredToolNames == null || requiredToolNames.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> names = new LinkedHashSet<String>();
        for (String toolName : requiredToolNames) {
            if (StrUtil.isNotBlank(toolName)) {
                names.add(toolName.trim());
            }
        }
        return new ArrayList<String>(names);
    }

    /**
     * 判断是否存在Recent工具Activity。
     *
     * @param previousNdjson previousNdjson 参数。
     * @param currentNdjson currentNdjson 参数。
     * @return 如果Recent工具Activity满足条件则返回 true，否则返回 false。
     */
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
            log.warn("detect recent tool activity failed: error={}", safeError(e));
            return false;
        }
        return false;
    }

    /**
     * 执行次数工具相关逻辑。
     *
     * @param messages messages 参数。
     * @return 返回次数工具结果。
     */
    private int countTools(List<ChatMessage> messages) {
        int count = 0;
        for (ChatMessage message : messages) {
            if (message.getRole() == ChatRole.TOOL) {
                count++;
            }
        }
        return count;
    }

    /**
     * 判断是否存在Usable Recovery Reply。
     *
     * @param recovered recovered 参数。
     * @return 如果Usable Recovery Reply满足条件则返回 true，否则返回 false。
     */
    private boolean hasUsableRecoveryReply(LlmResult recovered) {
        String text = recovered == null ? "" : extractText(recovered.getAssistantMessage());
        return StrUtil.isNotBlank(text) && !isMaxStepsReply(text);
    }

    /**
     * 修正与工具结果矛盾的恢复答复，避免最大步数收敛阶段否认已完成的真实副作用。
     *
     * @param base 最大步数前已经产生工具结果的基础结果。
     * @param recovered 恢复模型返回的收敛结果。
     */
    private void correctContradictingRecoveryReply(LlmResult base, LlmResult recovered) {
        if (base == null || recovered == null || recovered.getAssistantMessage() == null) {
            return;
        }
        String recoveryText = extractText(recovered.getAssistantMessage());
        if (!deniesCompletedToolActivity(recoveryText)) {
            return;
        }
        ToolMessage latestTool = latestToolMessage(base.getNdjson());
        if (latestTool == null || StrUtil.isBlank(latestTool.getContent())) {
            return;
        }
        String toolName = StrUtil.blankToDefault(latestTool.getName(), "工具");
        String content = truncateForRecovery(latestTool.getContent(), 600);
        recovered.setAssistantMessage(
                ChatMessage.ofAssistant(
                        "已执行工具调用，最新工具 "
                                + toolName
                                + " 返回："
                                + content
                                + "\n\n本轮在生成最终总结前达到最大步骤上限；以上述工具返回为准，请继续给出下一步指令或检查任务结果。"));
    }

    /**
     * 判断恢复文本是否否认已经发生的工具调用。
     *
     * @param text 恢复模型返回的文本。
     * @return 如果文本与已完成工具事实矛盾则返回 true。
     */
    private boolean deniesCompletedToolActivity(String text) {
        if (StrUtil.isBlank(text)) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        return text.contains("没有成功执行工具")
                || text.contains("实际上没有成功执行工具")
                || text.contains("未成功执行工具")
                || text.contains("没有执行工具")
                || text.contains("没有调用工具")
                || text.contains("未调用工具")
                || text.contains("工具未执行")
                || normalized.contains("tool was not called")
                || normalized.contains("tool was not executed")
                || normalized.contains("did not call the tool")
                || normalized.contains("did not execute the tool");
    }

    /**
     * 提取最近一条工具消息，用于恢复阶段的事实兜底。
     *
     * @param ndjson 会话历史 NDJSON。
     * @return 返回最近的工具消息；无法解析时返回 null。
     */
    private ToolMessage latestToolMessage(String ndjson) {
        try {
            List<ChatMessage> messages = MessageSupport.loadMessages(ndjson);
            for (int i = messages.size() - 1; i >= 0; i--) {
                ChatMessage message = messages.get(i);
                if (message instanceof ToolMessage) {
                    return (ToolMessage) message;
                }
            }
        } catch (Exception e) {
            log.warn(
                    "Failed to inspect tool transcript for recovery correction: error={}",
                    safeError(e));
        }
        return null;
    }

    /**
     * 截断恢复阶段展示的工具结果，避免把长输出完整写入最终回复。
     *
     * @param text 工具结果文本。
     * @param maxChars 最大字符数。
     * @return 返回截断后的文本。
     */
    private String truncateForRecovery(String text, int maxChars) {
        String safe = StrUtil.nullToEmpty(text).trim();
        int safeMax = Math.max(32, maxChars);
        if (safe.length() <= safeMax) {
            return safe;
        }
        return safe.substring(0, safeMax) + "...";
    }

    /**
     * 应用Recovered记录文本。
     *
     * @param base 基础参数。
     * @param recovered recovered 参数。
     */
    private void applyRecoveredTranscript(LlmResult base, LlmResult recovered) {
        if (base == null || recovered == null || recovered.getAssistantMessage() == null) {
            return;
        }
        try {
            List<ChatMessage> messages = MessageSupport.loadMessages(base.getNdjson());
            dropTransientAssistantTail(messages);
            MessageSupport.repairMessageSequence(messages);
            messages.add(ChatMessage.ofAssistant(extractText(recovered.getAssistantMessage())));
            recovered.setNdjson(MessageSupport.toNdjson(messages));
        } catch (Exception e) {
            log.warn(
                    "Failed to sanitize recovery transcript; using model transcript: error={}",
                    safeError(e));
        }
    }

    /**
     * 执行dropTransientAssistantTail相关逻辑。
     *
     * @param messages messages 参数。
     */
    private void dropTransientAssistantTail(List<ChatMessage> messages) {
        while (!messages.isEmpty()) {
            ChatMessage message = messages.get(messages.size() - 1);
            if (message.getRole() != ChatRole.ASSISTANT) {
                return;
            }
            String content = StrUtil.nullToEmpty(message.getContent());
            if (StrUtil.isBlank(content) || isMaxStepsReply(content)) {
                messages.remove(messages.size() - 1);
                continue;
            }
            return;
        }
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
        String normalized = replyText.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("agent error: maximum steps reached")
                || normalized.contains("maximum steps reached")
                || replyText.contains("已达到硬性步数上限");
    }

    /**
     * 判断错误是否为必需工具后验收失败，这类错误是回归策略失败，不应重试或切换模型。
     *
     * @param error 错误参数。
     * @return 如果错误来自必需工具缺失则返回 true。
     */
    private boolean isRequiredToolsMissing(Throwable error) {
        return error instanceof RequiredToolsMissingException;
    }

    /**
     * 执行attempt元数据相关逻辑。
     *
     * @param resolved resolved 参数。
     * @param attemptNo attemptNo 参数。
     * @param candidateIndex candidate索引标识或键值。
     * @return 返回attempt元数据结果。
     */
    private Map<String, Object> attemptMetadata(
            AppConfig.LlmConfig resolved, int attemptNo, int candidateIndex) {
        Map<String, Object> metadata = new java.util.LinkedHashMap<String, Object>();
        metadata.put("attempt", Integer.valueOf(attemptNo));
        metadata.put("candidate_index", Integer.valueOf(candidateIndex));
        metadata.put("provider", resolved.getProvider());
        metadata.put("model", resolved.getModel());
        metadata.put("dialect", resolved.getDialect());
        metadata.put("stream", Boolean.valueOf(resolved.isStream()));
        return metadata;
    }

    /**
     * 执行错误元数据相关逻辑。
     *
     * @param error 错误参数。
     * @param resolved resolved 参数。
     * @param attemptNo attemptNo 参数。
     * @param candidateIndex candidate索引标识或键值。
     * @return 返回error元数据结果。
     */
    private Map<String, Object> errorMetadata(
            Throwable error, AppConfig.LlmConfig resolved, int attemptNo, int candidateIndex) {
        LlmErrorClassifier.ClassifiedError classified = LlmErrorClassifier.classify(error);
        Map<String, Object> metadata = attemptMetadata(resolved, attemptNo, candidateIndex);
        metadata.put("reason", classified.getReason().name());
        metadata.put("status_code", Integer.valueOf(classified.getStatusCode()));
        metadata.put("retryable", Boolean.valueOf(classified.isRetryable()));
        metadata.put("should_fallback", Boolean.valueOf(classified.isShouldFallback()));
        metadata.put("should_compress", Boolean.valueOf(classified.isShouldCompress()));
        metadata.put("error", safeError(error));
        return metadata;
    }

    /**
     * 执行兜底元数据相关逻辑。
     *
     * @param previousProvider previous提供方标识或键值。
     * @param next next 参数。
     * @param lastError last错误参数。
     * @return 返回兜底元数据结果。
     */
    private Map<String, Object> fallbackMetadata(
            String previousProvider, AppConfig.LlmConfig next, Throwable lastError) {
        LlmErrorClassifier.ClassifiedError classified = LlmErrorClassifier.classify(lastError);
        Map<String, Object> metadata = new java.util.LinkedHashMap<String, Object>();
        metadata.put("from_provider", previousProvider);
        metadata.put("to_provider", next.getProvider());
        metadata.put("to_model", next.getModel());
        metadata.put("to_dialect", next.getDialect());
        metadata.put("reason", classified.getReason().name());
        metadata.put("status_code", Integer.valueOf(classified.getStatusCode()));
        metadata.put("retryable", Boolean.valueOf(classified.isRetryable()));
        metadata.put("should_compress", Boolean.valueOf(classified.isShouldCompress()));
        metadata.put("error", lastError == null ? "empty response" : safeError(lastError));
        return metadata;
    }

    /**
     * 应用用量。
     *
     * @param session 会话参数。
     * @param result 结果响应或执行结果。
     */
    private void applyUsage(SessionRecord session, LlmResult result) {
        LlmUsageSupport.applyUsage(session, result);
    }

    /**
     * 记录用量事件。
     *
     * @param runRecord 运行记录参数。
     * @param result 结果响应或执行结果。
     */
    private void recordUsageEvent(AgentRunRecord runRecord, LlmResult result) {
        if (usageEventRepository == null || runRecord == null || result == null) {
            return;
        }
        long input = Math.max(0L, result.getInputTokens());
        long output = Math.max(0L, result.getOutputTokens());
        long cacheRead = Math.max(0L, result.getCacheReadTokens());
        long cacheWrite = Math.max(0L, result.getCacheWriteTokens());
        long reasoning = Math.max(0L, result.getReasoningTokens());
        long requestCount = Math.max(0L, result.getRequestCount());
        long total = Math.max(Math.max(0L, result.getTotalTokens()), input + output + reasoning);
        if (requestCount <= 0L && total > 0L) {
            requestCount = 1L;
        }
        if (total <= 0
                && input <= 0
                && output <= 0
                && cacheRead <= 0
                && cacheWrite <= 0
                && reasoning <= 0) {
            return;
        }
        UsageEventRecord event = new UsageEventRecord();
        event.setEventId("run-usage-" + runRecord.getRunId());
        event.setRunId(runRecord.getRunId());
        event.setSessionId(runRecord.getSessionId());
        event.setSourceKey(runRecord.getSourceKey());
        event.setProvider(StrUtil.blankToDefault(result.getProvider(), runRecord.getProvider()));
        event.setModel(StrUtil.blankToDefault(result.getModel(), runRecord.getModel()));
        event.setInputTokens(input);
        event.setOutputTokens(output);
        event.setCacheReadTokens(cacheRead);
        event.setCacheWriteTokens(cacheWrite);
        event.setReasoningTokens(reasoning);
        event.setTotalTokens(total);
        event.setRequestCount(requestCount);
        event.setRawUsageJson(result.getRawUsageJson());
        event.setCreatedAt(
                runRecord.getFinishedAt() > 0
                        ? runRecord.getFinishedAt()
                        : System.currentTimeMillis());
        applyCost(event);
        try {
            usageEventRepository.insertIfAbsent(event);
        } catch (Exception e) {
            log.warn(
                    "Failed to record usage event: runId={}, error={}",
                    runRecord.getRunId(),
                    safeError(e));
        }
    }

    /**
     * 应用成本。
     *
     * @param event 事件参数。
     */
    private void applyCost(UsageEventRecord event) {
        if (event == null) {
            return;
        }
        if (usageCostCalculator == null) {
            UsageEventCostSupport.markUnpriced(event);
            return;
        }
        UsageEventCostSupport.apply(
                event, UsageEventCostSupport.calculate(usageCostCalculator, event));
    }

    /**
     * 合并用量。
     *
     * @param base 基础参数。
     * @param extra extra 参数。
     */
    private void mergeUsage(LlmResult base, LlmResult extra) {
        LlmUsageSupport.mergeUsage(base, extra);
    }

    /**
     * 注册运行。
     *
     * @param sourceKey 渠道来源键。
     * @param runId 运行标识。
     * @param sessionId 当前会话标识。
     * @param startedAt startedAt 参数。
     * @return 返回运行结果。
     */
    private RunHandle registerRun(
            String sourceKey, String runId, String sessionId, long startedAt) {
        String key = normalizeSourceKey(sourceKey);
        RunHandle handle = new RunHandle(runId, sessionId, Thread.currentThread(), startedAt);
        while (true) {
            RunHandle existing = runningRuns.get(key);
            if (existing == null) {
                if (runningRuns.putIfAbsent(key, handle) == null) {
                    return handle;
                }
                continue;
            }
            if (StrUtil.isBlank(existing.runId)
                    && existing.thread == Thread.currentThread()
                    && runningRuns.replace(key, existing, handle)) {
                return handle;
            }
            throw new AgentRunCancelledException();
        }
    }

    /**
     * 接入 Dashboard 手工恢复 pending 会话的实际执行器。
     *
     * @param pendingSessionResumer 接收 sourceKey、sessionId 并返回是否恢复成功的回调。
     */
    public void setPendingSessionResumer(BiPredicate<String, String> pendingSessionResumer) {
        this.pendingSessionResumer = pendingSessionResumer;
    }

    /**
     * 释放当前线程尚未升级为真实 run 的入站占位，避免前置处理失败后来源永久忙碌。
     *
     * @param sourceKey 渠道来源键。
     * @return 当前线程成功释放占位时返回 true。
     */
    public boolean releaseIncomingReservation(String sourceKey) {
        String key = normalizeSourceKey(sourceKey);
        RunHandle handle = runningRuns.get(key);
        if (handle != null
                && StrUtil.isBlank(handle.runId)
                && handle.thread == Thread.currentThread()) {
            return runningRuns.remove(key, handle);
        }
        return false;
    }

    /**
     * 取消注册运行。
     *
     * @param sourceKey 渠道来源键。
     * @param handle handle 参数。
     */
    private void unregisterRun(String sourceKey, RunHandle handle) {
        if (handle == null) {
            return;
        }
        runningRuns.remove(normalizeSourceKey(sourceKey), handle);
        lastRunFinishedAt = System.currentTimeMillis();
    }

    /**
     * 标记会话Resume Pending。
     *
     * @param sourceKey 渠道来源键。
     * @param resumeReason resume原因参数。
     */
    private void markSessionResumePending(String sourceKey, String resumeReason) {
        markSessionResumePending(sourceKey, null, resumeReason);
    }

    /**
     * 标记指定来源或精确会话为 pending，避免 stale run 污染同来源后来绑定的新会话。
     *
     * @param sourceKey 渠道来源键。
     * @param sessionId 精确会话标识；为空时使用当前绑定会话。
     * @param resumeReason 恢复原因。
     */
    private void markSessionResumePending(String sourceKey, String sessionId, String resumeReason) {
        if (StrUtil.isBlank(sourceKey) || StrUtil.isBlank(resumeReason)) {
            return;
        }
        try {
            SessionRecord session =
                    StrUtil.isBlank(sessionId)
                            ? sessionRepository.getBoundSession(sourceKey)
                            : sessionRepository.findById(sessionId);
            if (session == null || !StrUtil.equals(sourceKey, session.getSourceKey())) {
                return;
            }
            SqliteAgentSession agentSession = new SqliteAgentSession(session, sessionRepository);
            agentSession.pending(true, resumeReason);
            agentSession.updateSnapshot();
        } catch (Exception e) {
            log.warn("mark resume pending failed: sourceKey={}, error={}", sourceKey, safeError(e));
        }
    }

    /**
     * 恢复Stale运行。
     *
     * @param staleAfterMillis staleAfterMillis 参数。
     */
    public void recoverStaleRuns(long staleAfterMillis) {
        if (agentRunRepository == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long before = now - Math.max(60_000L, staleAfterMillis);
        try {
            agentRunRepository.requeueStaleRunningMessages(before);
            while (true) {
                List<AgentRunRecord> staleRuns = agentRunRepository.listActiveBefore(before, 200);
                if (staleRuns.isEmpty()) {
                    break;
                }
                agentRunRepository.markStaleRuns(before, now);
                for (AgentRunRecord staleRun : staleRuns) {
                    String kind = StrUtil.blankToDefault(staleRun.getRunKind(), "");
                    String status = StrUtil.blankToDefault(staleRun.getStatus(), "");
                    if (("conversation".equals(kind) || "resume".equals(kind))
                            && !"queued".equals(status)
                            && !"waiting_approval".equals(status)) {
                        markSessionResumePending(
                                staleRun.getSourceKey(),
                                staleRun.getSessionId(),
                                "restart_interrupted");
                    }
                }
                if (staleRuns.size() < 200) {
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("recoverStaleRuns failed: error={}", safeError(e));
        }
    }

    /**
     * 更新运行Phase。
     *
     * @param runRecord 运行记录参数。
     * @param phase phase 参数。
     */
    private void updateRunPhase(AgentRunRecord runRecord, String phase) throws Exception {
        runRecord.setPhase(phase);
        heartbeat(runRecord);
        agentRunRepository.saveRun(runRecord);
    }

    /**
     * 执行心跳相关逻辑。
     *
     * @param runRecord 运行记录参数。
     */
    private void heartbeat(AgentRunRecord runRecord) {
        long now = System.currentTimeMillis();
        runRecord.setHeartbeatAt(now);
        runRecord.setLastActivityAt(now);
    }

    /**
     * 检查Cancellation。
     *
     * @param sourceKey 渠道来源键。
     */
    private void checkCancellation(String sourceKey) {
        if (isCancellationRequested(sourceKey)) {
            throw new AgentRunCancelledException();
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new AgentRunCancelledException();
        }
    }

    /**
     * 判断是否Cancellation Requested。
     *
     * @param sourceKey 渠道来源键。
     * @return 如果Cancellation Requested满足条件则返回 true，否则返回 false。
     */
    private boolean isCancellationRequested(String sourceKey) {
        RunHandle handle = runningRuns.get(normalizeSourceKey(sourceKey));
        return handle != null && handle.cancelled.get();
    }

    /**
     * 查询某来源键是否有待处理的真实用户消息（非 heartbeat、非 goal 续轮）。
     *
     * <p>用于 goal 续轮抢占判定：若用户在续轮调度期间发送了真实消息，则跳过本轮续轮， 让真实消息接手对话。
     *
     * <p>实现：仅按 sourceKey 查询最早的 queued 消息（不限会话），反序列化其 messageJson， 读取已持久化的 {@code goalContinuation}
     * 与 {@code heartbeat} 标志。当该消息既非 heartbeat 又非 goal 续轮合成消息时，视为真实用户消息返回 true。该检查在续轮消息入队之前执行，
     * 因此此刻队列中只会是真实用户消息或 heartbeat，不会是尚未入队的续轮合成消息。
     *
     * @param sourceKey 会话来源键。
     * @return 有真实待处理消息返回 true；无待处理消息或仅有合成消息返回 false。
     */
    public boolean hasPendingRealMessage(String sourceKey) {
        if (StrUtil.isBlank(sourceKey)) {
            return false;
        }
        try {
            QueuedRunMessage queued =
                    agentRunRepository.findNextQueuedMessageBySourceKey(sourceKey);
            if (queued == null) {
                return false;
            }
            GatewayMessage message = deserializeMessage(queued);
            // heartbeat 与 goal 续轮合成消息都不算真实用户消息
            if (message.isHeartbeat() || message.isGoalContinuation()) {
                return false;
            }
            return true;
        } catch (Exception e) {
            // 查询/反序列化失败时 fail-safe 返回 false，避免误判抢占而吞掉续轮
            log.warn(
                    "hasPendingRealMessage query failed, fail-safe to false: sourceKey={},"
                            + " error={}",
                    sourceKey,
                    safeError(e));
            return false;
        }
    }

    /**
     * 加入队列消息。
     *
     * @param sourceKey 渠道来源键。
     * @param sessionId 当前会话标识。
     * @param message 平台消息或错误消息。
     * @param policy 策略参数。
     * @return 返回queue消息结果。
     */
    private QueuedRunMessage queueMessage(
            String sourceKey, String sessionId, GatewayMessage message, String policy)
            throws Exception {
        long now = System.currentTimeMillis();
        QueuedRunMessage queued = new QueuedRunMessage();
        queued.setQueueId(IdSupport.newId());
        queued.setRunId(IdSupport.newId());
        queued.setSessionId(sessionId);
        queued.setSourceKey(sourceKey);
        queued.setMessageText(AgentRunContext.safe(message == null ? "" : message.getText(), 4000));
        queued.setMessageJson(serializeMessage(message));
        queued.setStatus("queued");
        queued.setBusyPolicy(policy);
        queued.setCreatedAt(now);
        agentRunRepository.saveQueuedMessage(queued);

        AgentRunRecord runRecord = new AgentRunRecord();
        runRecord.setRunId(queued.getRunId());
        runRecord.setSessionId(sessionId);
        runRecord.setSourceKey(sourceKey);
        runRecord.setRunKind("conversation");
        runRecord.setStatus("queued");
        runRecord.setPhase("queued");
        runRecord.setBusyPolicy(policy);
        runRecord.setInputPreview(queued.getMessageText());
        runRecord.setQueuedAt(now);
        runRecord.setStartedAt(now);
        runRecord.setHeartbeatAt(now);
        runRecord.setLastActivityAt(now);
        agentRunRepository.saveRun(runRecord);
        appendRunEvent(runRecord, "run.queued", "busy 策略为 queue，新消息已进入队列", null);
        return queued;
    }

    /**
     * 执行serialize消息相关逻辑。
     *
     * @param message 平台消息或错误消息。
     * @return 返回serialize消息结果。
     */
    private String serializeMessage(GatewayMessage message) {
        if (message == null) {
            return "{}";
        }
        Map<String, Object> map = new java.util.LinkedHashMap<String, Object>();
        map.put("platform", message.getPlatform() == null ? null : message.getPlatform().name());
        map.put("chatId", message.getChatId());
        map.put("userId", message.getUserId());
        map.put("chatType", message.getChatType());
        map.put("chatName", message.getChatName());
        map.put("userName", message.getUserName());
        map.put("text", message.getText());
        map.put("threadId", message.getThreadId());
        map.put("replyToMessageId", message.getReplyToMessageId());
        map.put("sourceKeyOverride", message.getSourceKeyOverride());
        map.put("heartbeat", message.isHeartbeat());
        map.put("goalContinuation", message.isGoalContinuation());
        map.put("timestamp", message.getTimestamp());
        return org.noear.snack4.ONode.serialize(map);
    }

    /**
     * 执行deserialize消息相关逻辑。
     *
     * @param queued 排队参数。
     * @return 返回deserialize消息结果。
     */
    private GatewayMessage deserializeMessage(QueuedRunMessage queued) {
        GatewayMessage message = new GatewayMessage();
        try {
            Object parsed =
                    org.noear.snack4.ONode.deserialize(queued.getMessageJson(), Object.class);
            if (parsed instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) parsed;
                message.setPlatform(
                        com.jimuqu.solon.claw.core.enums.PlatformType.fromName(
                                stringValue(map.get("platform"))));
                message.setChatId(stringValue(map.get("chatId")));
                message.setUserId(stringValue(map.get("userId")));
                message.setChatType(stringValue(map.get("chatType")));
                message.setChatName(stringValue(map.get("chatName")));
                message.setUserName(stringValue(map.get("userName")));
                message.setText(stringValue(map.get("text")));
                message.setThreadId(stringValue(map.get("threadId")));
                message.setReplyToMessageId(stringValue(map.get("replyToMessageId")));
                message.setSourceKeyOverride(stringValue(map.get("sourceKeyOverride")));
                message.setHeartbeat(Boolean.parseBoolean(stringValue(map.get("heartbeat"))));
                message.setGoalContinuation(Boolean.TRUE.equals(map.get("goalContinuation")));
                Object timestamp = map.get("timestamp");
                if (timestamp instanceof Number) {
                    message.setTimestamp(((Number) timestamp).longValue());
                }
            }
        } catch (Exception e) {
            log.warn(
                    "deserialize queued message failed: queueId={}, error={}",
                    queued == null ? null : queued.getQueueId(),
                    safeError(e));
            message.setText(queued.getMessageText());
            message.setSourceKeyOverride(queued.getSourceKey());
        }
        if (StrUtil.isBlank(message.getSourceKeyOverride())) {
            message.setSourceKeyOverride(queued.getSourceKey());
        }
        String text = StrUtil.nullToEmpty(message.getText());
        message.setText(
                text
                        + "\n\n[queue-metadata:"
                        + QUEUED_RUN_ID_KEY
                        + "="
                        + queued.getRunId()
                        + ";"
                        + QUEUE_ID_KEY
                        + "="
                        + queued.getQueueId()
                        + "]");
        return message;
    }

    /**
     * 将输入对象转换为去除首尾空白的字符串。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回string Value结果。
     */
    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 清空队列。
     *
     * @param sourceKey 渠道来源键。
     * @param sessionId 当前会话标识。
     * @param runner runner 参数。
     */
    private void drainQueue(
            String sourceKey, String sessionId, Function<GatewayMessage, GatewayReply> runner) {
        if (runner == null) {
            return;
        }
        while (true) {
            if (!reserveQueueDrain(sourceKey, sessionId)) {
                return;
            }
            QueuedRunMessage queued;
            try {
                queued = agentRunRepository.findNextQueuedMessage(sourceKey, sessionId);
            } catch (Exception e) {
                log.warn("find queued run failed: sourceKey={}, error={}", sourceKey, safeError(e));
                releaseIncomingReservation(sourceKey);
                return;
            }
            if (queued == null) {
                releaseIncomingReservation(sourceKey);
                return;
            }
            boolean claimed = false;
            try {
                claimed =
                        agentRunRepository.markQueuedMessage(
                                queued.getQueueId(),
                                "queued",
                                "running",
                                System.currentTimeMillis(),
                                null);
                if (!claimed) {
                    continue;
                }
                markQueuedRunStarted(queued);
                GatewayReply reply = runner.apply(deserializeMessage(queued));
                if (agentRunRepository.markQueuedMessage(
                        queued.getQueueId(),
                        "running",
                        "success",
                        System.currentTimeMillis(),
                        null)) {
                    markQueuedRunFinished(
                            queued, "success", reply == null ? null : reply.getContent(), null);
                }
            } catch (Exception e) {
                try {
                    if (claimed
                            && agentRunRepository.markQueuedMessage(
                                    queued.getQueueId(),
                                    "running",
                                    "failed",
                                    System.currentTimeMillis(),
                                    safeError(e))) {
                        markQueuedRunFinished(queued, "failed", null, safeError(e));
                    }
                } catch (Exception markFailedError) {
                    log.warn(
                            "mark queued message failed status failed: queueId={}, error={}",
                            queued.getQueueId(),
                            safeError(markFailedError));
                }
                log.warn(
                        "queued run failed: queueId={}, error={}",
                        queued.getQueueId(),
                        safeError(e));
            } finally {
                releaseIncomingReservation(sourceKey);
            }
        }
    }

    /** 判断当前来源会话是否仍有 queued 消息，用于补偿 drain 结束窗口内丢失的唤醒。 */
    private boolean hasQueuedMessage(String sourceKey, String sessionId) {
        try {
            return agentRunRepository.findNextQueuedMessage(sourceKey, sessionId) != null;
        } catch (Exception e) {
            log.warn("check queued run failed: sourceKey={}, error={}", sourceKey, safeError(e));
            return false;
        }
    }

    /**
     * 为队列 drain 原子领取同来源占位，确保队列交接期间新入站只能走 busy 策略。
     *
     * @param sourceKey 渠道来源键。
     * @param sessionId 当前会话标识。
     * @return 成功领取占位时返回 true。
     */
    private boolean reserveQueueDrain(String sourceKey, String sessionId) {
        String key = normalizeSourceKey(sourceKey);
        while (true) {
            RunHandle existing = runningRuns.get(key);
            if (existing != null && !existing.cancelled.get()) {
                return false;
            }
            RunHandle reservation =
                    new RunHandle(
                            "", sessionId, Thread.currentThread(), System.currentTimeMillis());
            boolean claimed =
                    existing == null
                            ? runningRuns.putIfAbsent(key, reservation) == null
                            : runningRuns.replace(key, existing, reservation);
            if (claimed) {
                return true;
            }
        }
    }

    /**
     * 标记Queued运行Started。
     *
     * @param queued 排队参数。
     */
    private void markQueuedRunStarted(QueuedRunMessage queued) {
        try {
            AgentRunRecord record =
                    queued == null || StrUtil.isBlank(queued.getRunId())
                            ? null
                            : agentRunRepository.findRun(queued.getRunId());
            if (record == null) {
                return;
            }
            long now = System.currentTimeMillis();
            record.setStatus("running");
            record.setPhase("running");
            record.setStartedAt(now);
            record.setHeartbeatAt(now);
            record.setLastActivityAt(now);
            record.setExitReason(null);
            record.setError(null);
            agentRunRepository.saveRun(record);
            appendRunEvent(record, "run.queue.start", "开始执行 busy 队列消息", null);
        } catch (Exception e) {
            log.warn("mark queued run start failed: error={}", safeError(e));
        }
    }

    /**
     * 标记Queued运行Finished。
     *
     * @param queued 排队参数。
     * @param status 状态参数。
     * @param finalReply 最终回复参数。
     * @param error 错误参数。
     */
    private void markQueuedRunFinished(
            QueuedRunMessage queued, String status, String finalReply, String error) {
        try {
            AgentRunRecord record =
                    queued == null || StrUtil.isBlank(queued.getRunId())
                            ? null
                            : agentRunRepository.findRun(queued.getRunId());
            if (record == null) {
                return;
            }
            long now = System.currentTimeMillis();
            boolean success = "success".equals(status);
            record.setStatus(success ? "success" : "failed");
            record.setPhase(success ? "completed" : "failed");
            record.setFinishedAt(now);
            record.setLastActivityAt(now);
            record.setExitReason(status);
            record.setFinalReplyPreview(AgentRunContext.safe(finalReply, 1000));
            record.setError(error);
            agentRunRepository.saveRun(record);
            appendRunEvent(
                    record,
                    success ? "run.queue.success" : "run.queue.failed",
                    success ? "busy 队列消息执行完成" : "busy 队列消息执行失败",
                    null);
        } catch (Exception e) {
            log.warn("mark queued run finish failed: error={}", safeError(e));
        }
    }

    /**
     * 规范化Busy策略。
     *
     * @param policy 策略参数。
     * @return 返回Busy策略结果。
     */
    private String normalizeBusyPolicy(String policy) {
        String normalized = StrUtil.blankToDefault(policy, "queue").trim().toLowerCase(Locale.ROOT);
        if ("interrupt".equals(normalized)
                || "steer".equals(normalized)
                || "reject".equals(normalized)
                || "queue".equals(normalized)) {
            return normalized;
        }
        return "queue";
    }

    /**
     * 记录命令。
     *
     * @param runId 运行标识。
     * @param sourceKey 渠道来源键。
     * @param command 待执行或解析的命令文本。
     * @param payloadJson 载荷JSON请求载荷。
     * @param status 状态参数。
     */
    private void recordCommand(
            String runId, String sourceKey, String command, String payloadJson, String status)
            throws Exception {
        RunControlCommand record = new RunControlCommand();
        record.setCommandId(IdSupport.newId());
        record.setRunId(runId);
        record.setSourceKey(sourceKey);
        record.setCommand(command);
        record.setPayloadJson(payloadJson);
        record.setStatus(StrUtil.blankToDefault(status, "pending"));
        record.setCreatedAt(System.currentTimeMillis());
        if (!"pending".equals(record.getStatus())) {
            record.setHandledAt(record.getCreatedAt());
        }
        agentRunRepository.saveRunControlCommand(record);
    }

    /**
     * 追加运行事件。
     *
     * @param record 记录参数。
     * @param eventType 事件类型参数。
     * @param summary 摘要参数。
     * @param metadataJson 元数据JSON参数。
     */
    private void appendRunEvent(
            AgentRunRecord record, String eventType, String summary, String metadataJson) {
        if (record == null) {
            return;
        }
        try {
            AgentRunEventRecord event = new AgentRunEventRecord();
            event.setEventId(IdSupport.newId());
            event.setRunId(record.getRunId());
            event.setSessionId(record.getSessionId());
            event.setSourceKey(record.getSourceKey());
            event.setEventType(eventType);
            event.setPhase(record.getPhase());
            event.setSeverity(eventType != null && eventType.contains("reject") ? "warn" : "info");
            event.setSummary(safeText(summary));
            event.setMetadataJson(StructuredMetadataSupport.redactJson(metadataJson));
            event.setCreatedAt(System.currentTimeMillis());
            agentRunRepository.appendEvent(event);
        } catch (Exception e) {
            log.debug(
                    "append run event failed: runId={}, eventType={}, error={}",
                    record.getRunId(),
                    eventType,
                    safeError(e));
        }
    }

    /**
     * 转义JSON。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回escape JSON结果。
     */
    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 将异常转换为可展示且不泄漏敏感信息的错误文本。
     *
     * @param error 错误参数。
     * @return 返回safe Error结果。
     */
    private String safeError(Throwable error) {
        if (error == null) {
            return "";
        }
        return safeText(
                StrUtil.blankToDefault(error.getMessage(), error.getClass().getSimpleName()));
    }

    /**
     * 生成安全展示用的文本。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回safe Text结果。
     */
    private String safeText(String value) {
        return SecretRedactor.redact(AgentRunContext.safe(value, 1000), 1000);
    }

    /** 单次运行冻结后的候选执行计划，同时保留未进入远程调用的配置失败。 */
    private static final class CandidatePlan {
        /** 可实际调用的候选，保持用户配置顺序。 */
        private final List<AppConfig.LlmConfig> candidates = new ArrayList<AppConfig.LlmConfig>();

        /** 每个候选的失败或跳过原因，用于最终错误汇总。 */
        private final List<CandidateFailure> failures = new ArrayList<CandidateFailure>();
    }

    /** 不含密钥的单候选失败摘要。 */
    private static final class CandidateFailure {
        /** provider 配置键。 */
        private final String provider;

        /** 模型名。 */
        private final String model;

        /** 稳定失败分类。 */
        private final String reason;

        /** 可行动的安全错误说明。 */
        private final String detail;

        private CandidateFailure(String provider, String model, String reason, String detail) {
            this.provider = StrUtil.blankToDefault(provider, "unknown");
            this.model = StrUtil.blankToDefault(model, "unknown");
            this.reason = StrUtil.blankToDefault(reason, "UNKNOWN");
            this.detail = StrUtil.blankToDefault(detail, "未知错误");
        }

        /** 创建本地预检跳过记录。 */
        private static CandidateFailure skipped(
                String provider, String model, String reason, String detail) {
            return new CandidateFailure(provider, model, reason, detail);
        }

        /** 创建已发起远程调用的失败记录。 */
        private static CandidateFailure attempted(
                AppConfig.LlmConfig config, String reason, String detail) {
            return new CandidateFailure(
                    config == null ? "" : config.getProvider(),
                    config == null ? "" : config.getModel(),
                    reason,
                    detail);
        }

        /** 返回单行用户可见摘要。 */
        private String display() {
            return provider + "/" + model + " [" + reason + "] " + detail;
        }

        /** 返回事件元数据，不包含 API key 或完整请求地址。 */
        private Map<String, Object> metadata() {
            Map<String, Object> metadata = new java.util.LinkedHashMap<String, Object>();
            metadata.put("provider", provider);
            metadata.put("model", model);
            metadata.put("reason", reason);
            metadata.put("detail", detail);
            return metadata;
        }
    }

    /**
     * 提取Queued Marker。
     *
     * @param text 待处理文本。
     * @param key 配置键或映射键。
     * @return 返回Queued Marker结果。
     */
    private String extractQueuedMarker(String text, String key) {
        if (StrUtil.isBlank(text) || StrUtil.isBlank(key)) {
            return null;
        }
        String markerStart = "[queue-metadata:";
        int start = text.lastIndexOf(markerStart);
        if (start < 0) {
            return null;
        }
        int end = text.indexOf(']', start);
        if (end < 0) {
            return null;
        }
        String body = text.substring(start + markerStart.length(), end);
        String[] parts = body.split(";");
        for (String part : parts) {
            int equals = part.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String name = part.substring(0, equals).trim();
            if (key.equals(name)) {
                return part.substring(equals + 1).trim();
            }
        }
        return null;
    }

    /**
     * 剥离排队Markers。
     *
     * @param text 待处理文本。
     * @return 返回strip Queued Markers结果。
     */
    private String stripQueuedMarkers(String text) {
        if (StrUtil.isBlank(text)) {
            return text;
        }
        String markerStart = "\n\n[queue-metadata:";
        int start = text.lastIndexOf(markerStart);
        if (start < 0) {
            return text;
        }
        int end = text.indexOf(']', start);
        if (end < 0 || end != text.length() - 1) {
            return text;
        }
        return text.substring(0, start);
    }

    /**
     * 规范化来源键。
     *
     * @param sourceKey 渠道来源键。
     * @return 返回来源键结果。
     */
    private String normalizeSourceKey(String sourceKey) {
        return StrUtil.blankToDefault(sourceKey, "__default__");
    }

    /** 必需工具后验收失败，表示模型没有真实完成受控 Web 回归声明的工具调用链。 */
    private static class RequiredToolsMissingException extends IllegalStateException {
        /**
         * 创建必需工具缺失错误。
         *
         * @param message 可展示的失败原因。
         */
        private RequiredToolsMissingException(String message) {
            super(message);
        }
    }

    /** 承载运行Handle相关状态和辅助逻辑。 */
    private static class RunHandle {
        /** 记录运行Handle中的运行标识。 */
        private final String runId;

        /** 记录运行Handle中的会话标识。 */
        private final String sessionId;

        /** 记录运行Handle中的thread。 */
        private final Thread thread;

        /** 记录运行Handle中的started时间。 */
        private final long startedAt;

        /** 记录运行Handle中的cancelled。 */
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        /**
         * 创建运行Handle实例，并注入运行所需依赖。
         *
         * @param runId 运行标识。
         * @param sessionId 当前会话标识。
         * @param thread thread 参数。
         * @param startedAt startedAt 参数。
         */
        private RunHandle(String runId, String sessionId, Thread thread, long startedAt) {
            this.runId = runId;
            this.sessionId = sessionId;
            this.thread = thread;
            this.startedAt = startedAt;
        }
    }

    /** 执行pruneOld运行相关逻辑。 */
    private void pruneOldRuns() {
        int days = appConfig.getTrace().getRetentionDays();
        if (days <= 0) {
            return;
        }
        try {
            agentRunRepository.pruneBefore(
                    System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L);
        } catch (Exception e) {
            log.debug("prune old runs failed: error={}", safeError(e));
        }
    }
}
