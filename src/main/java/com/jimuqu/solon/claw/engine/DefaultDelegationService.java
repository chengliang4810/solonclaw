package com.jimuqu.solon.claw.engine;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.agent.AgentRuntimePolicy;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.AgentRunContext;
import com.jimuqu.solon.claw.core.model.AgentRunRecord;
import com.jimuqu.solon.claw.core.model.AgentRunStopResult;
import com.jimuqu.solon.claw.core.model.DelegationResult;
import com.jimuqu.solon.claw.core.model.DelegationTask;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.model.SubagentRunRecord;
import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.AgentRunCancelledException;
import com.jimuqu.solon.claw.core.service.AgentRunControlService;
import com.jimuqu.solon.claw.core.service.DelegationService;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.profile.ProfileRuntimeScope;
import com.jimuqu.solon.claw.storage.repository.SqlitePreferenceStore;
import com.jimuqu.solon.claw.support.BoundedExecutorFactory;
import com.jimuqu.solon.claw.support.ConversationOrchestratorHolder;
import com.jimuqu.solon.claw.support.IdSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.SourceKeySupport;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.noear.snack4.ONode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 默认子代理委托服务。 */
public class DefaultDelegationService implements DelegationService {
    /** 委托日志器。 */
    private static final Logger log = LoggerFactory.getLogger(DefaultDelegationService.class);

    /** 子代理固定禁用的工具。 */
    private static final List<String> BLOCKED_TOOLS =
            Arrays.asList(
                    ToolNameConstants.DELEGATE_TASK,
                    ToolNameConstants.MEMORY,
                    ToolNameConstants.SEND_MESSAGE,
                    ToolNameConstants.CRONJOB,
                    ToolNameConstants.EXECUTE_CODE,
                    ToolNameConstants.EXECUTE_PYTHON,
                    ToolNameConstants.EXECUTE_JS);

    /** 当前系统已知工具清单。 */
    private static final List<String> ALL_TOOLS = AgentRuntimePolicy.knownToolNames();

    /** 父运行链最大回溯层数，避免异常环或损坏数据造成无限遍历。 */
    private static final int MAX_DEPTH_LOOKUP = 64;

    /** interrupt 等待 child run 注册的最长时间，覆盖启动检查与运行句柄登记之间的短窗口。 */
    private static final long INTERRUPT_REGISTRATION_WAIT_MILLIS = 1000L;

    /** 对话编排器。 */
    private final ConversationOrchestratorHolder conversationHolder;

    /** 工具注册表。 */
    private final SqlitePreferenceStore preferenceStore;

    /** 会话仓储。 */
    private final SessionRepository sessionRepository;

    /** Agent run 轨迹仓储。 */
    private final AgentRunRepository agentRunRepository;

    /** 注入应用配置，用于默认委托。 */
    private final AppConfig appConfig;

    /** 注入Agent运行控制服务，用于调用对应业务能力。 */
    private final AgentRunControlService agentRunControlService;

    /** 渠道投递服务，用于把后台委派完成后的父会话新回复送回原入口。 */
    private final DeliveryService deliveryService;

    /** 保存active注册表映射，便于按键快速查询。 */
    private final ConcurrentMap<String, SubagentRunRecord> activeRegistry =
            new ConcurrentHashMap<String, SubagentRunRecord>();

    /** 按委派标识保存当前 Profile 的后台任务归属与取消句柄。 */
    private final ConcurrentMap<String, BackgroundDelegation> backgroundRegistry =
            new ConcurrentHashMap<String, BackgroundDelegation>();

    /** 记录默认委托中的concurrencyLimiter。 */
    private final Semaphore concurrencyLimiter;

    /** 顶层委派后台执行器；有界队列防止模型连续委派造成无上限内存增长。 */
    private final ExecutorService backgroundExecutor;

    /** Profile 运行时关闭后拒绝继续接收后台委派。 */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** 串行化后台任务提交与 Profile 关闭，避免关闭过程中遗漏新登记任务。 */
    private final Object backgroundLifecycleLock = new Object();

    /** 是否启用spawnPaused。 */
    private volatile boolean spawnPaused;

    /**
     * 创建默认委托服务实例，并注入运行所需依赖。
     *
     * @param conversationHolder conversationHolder 参数。
     * @param preferenceStore 本地偏好存储依赖。
     * @param sessionRepository 会话仓储依赖。
     */
    public DefaultDelegationService(
            ConversationOrchestratorHolder conversationHolder,
            SqlitePreferenceStore preferenceStore,
            SessionRepository sessionRepository) {
        this(conversationHolder, preferenceStore, sessionRepository, null, null, null);
    }

    /**
     * 创建默认委托服务实例，并注入运行所需依赖。
     *
     * @param conversationHolder conversationHolder 参数。
     * @param preferenceStore 本地偏好存储依赖。
     * @param sessionRepository 会话仓储依赖。
     * @param agentRunRepository Agent运行仓储依赖。
     * @param appConfig 应用运行配置。
     * @param agentRunControlService Agent运行控制服务依赖。
     */
    public DefaultDelegationService(
            ConversationOrchestratorHolder conversationHolder,
            SqlitePreferenceStore preferenceStore,
            SessionRepository sessionRepository,
            AgentRunRepository agentRunRepository,
            AppConfig appConfig,
            AgentRunControlService agentRunControlService) {
        this(
                conversationHolder,
                preferenceStore,
                sessionRepository,
                agentRunRepository,
                appConfig,
                agentRunControlService,
                null);
    }

    /** 创建支持后台结果回流的默认委派服务。 */
    public DefaultDelegationService(
            ConversationOrchestratorHolder conversationHolder,
            SqlitePreferenceStore preferenceStore,
            SessionRepository sessionRepository,
            AgentRunRepository agentRunRepository,
            AppConfig appConfig,
            AgentRunControlService agentRunControlService,
            DeliveryService deliveryService) {
        this.conversationHolder = conversationHolder;
        this.preferenceStore = preferenceStore;
        this.sessionRepository = sessionRepository;
        this.agentRunRepository = agentRunRepository;
        this.appConfig = appConfig;
        this.agentRunControlService = agentRunControlService;
        this.deliveryService = deliveryService;
        int maxConcurrency =
                appConfig == null
                        ? 3
                        : Math.max(1, appConfig.getTask().getSubagentMaxConcurrency());
        this.concurrencyLimiter = new Semaphore(maxConcurrency, true);
        this.backgroundExecutor =
                BoundedExecutorFactory.fixed(
                        "solonclaw-delegation", maxConcurrency, Math.max(1, maxConcurrency * 2));
    }

    /**
     * 收敛上一次进程退出后遗留的活动子 Agent 记录。
     *
     * <p>当前进程没有旧线程和控制句柄，因此这些记录只能标记为已中断，不能恢复到内存活动表。
     *
     * @return 被收敛的子 Agent 记录数量；仓储不可用或收敛失败时返回零。
     */
    public int reconcileStaleSubagents() {
        if (agentRunRepository == null) {
            return 0;
        }
        try {
            int reconciled =
                    agentRunRepository.markActiveSubagentsInterrupted(System.currentTimeMillis());
            if (reconciled > 0) {
                log.warn("Marked {} stale subagent run(s) as interrupted", reconciled);
            }
            return reconciled;
        } catch (Exception e) {
            log.warn(
                    "Failed to reconcile stale subagent runs: {}",
                    SecretRedactor.redact(e.getMessage(), 1000));
            return 0;
        }
    }

    /** 当前调用是否来自顶层会话；子 Agent 内的 orchestrator 需要同步拿到工作结果。 */
    @Override
    public boolean shouldRunInBackground() {
        AgentRunContext context = AgentRunContext.current();
        return context != null && !"subagent".equals(context.getRunKind());
    }

    /**
     * 在后台执行一组委派，并把汇总结果作为新消息回流到父会话。
     *
     * @param sourceKey 父会话来源键。
     * @param tasks 已完成参数校验的任务列表。
     * @return 可供模型和界面展示的后台任务句柄。
     */
    @Override
    public Map<String, Object> delegateInBackground(
            final String sourceKey, final List<DelegationTask> tasks) {
        rejectNestedDelegation();
        if (tasks == null || tasks.isEmpty()) {
            throw new IllegalArgumentException("No tasks provided");
        }
        final String delegationId = "dg-" + IdSupport.newId();
        final AgentRunContext parentContext = AgentRunContext.current();
        final String parentSessionId = parentContext == null ? null : parentContext.getSessionId();
        final BackgroundDelegation background =
                new BackgroundDelegation(delegationId, sourceKey, parentSessionId);
        Runnable scopedTask =
                ProfileRuntimeScope.capture(
                        new Runnable() {
                            /** 执行后台委派并在全部子任务结束后回流一次汇总消息。 */
                            @Override
                            public void run() {
                                List<DelegationResult> results;
                                AgentRunContext previous = AgentRunContext.current();
                                AgentRunContext.setCurrent(parentContext);
                                try {
                                    results = delegateBatch(sourceKey, tasks);
                                } catch (Exception e) {
                                    results =
                                            java.util.Collections.singletonList(
                                                    failureResult("delegate", e.getMessage()));
                                } finally {
                                    AgentRunContext.setCurrent(previous);
                                }
                                if (!background.cancelRequested.get()) {
                                    deliverBackgroundResult(background, results);
                                }
                            }
                        });
        FutureTask<Void> future = backgroundFuture(background, scopedTask);
        background.future = future;
        synchronized (backgroundLifecycleLock) {
            if (closed.get()) {
                throw new IllegalStateException("Background delegation service is closed");
            }
            backgroundRegistry.put(delegationId, background);
            try {
                backgroundExecutor.execute(future);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                future.cancel(false);
                throw new IllegalStateException("Background delegation capacity is full");
            }
        }
        Map<String, Object> handle = new LinkedHashMap<String, Object>();
        handle.put("status", "dispatched");
        handle.put("mode", "background");
        handle.put("count", Integer.valueOf(tasks.size()));
        handle.put("delegation_id", delegationId);
        handle.put("note", "委派已进入后台；全部子任务完成后，汇总结果会作为新消息回流当前会话。");
        return handle;
    }

    /** 把后台委派结果作为新一轮父会话输入，触发模型读取并继续回答。 */
    private void deliverBackgroundResult(
            BackgroundDelegation background, List<DelegationResult> results) {
        if (background.cancelRequested.get()) {
            return;
        }
        String sourceKey = background.sourceKey;
        if (conversationHolder.get() == null) {
            log.warn("Background delegation result dropped: orchestrator unavailable");
            return;
        }
        String payload = SecretRedactor.redact(ONode.serialize(results), 20000);
        String[] source = SourceKeySupport.split(sourceKey);
        GatewayMessage completion =
                new GatewayMessage(
                        PlatformType.fromName(source[0]),
                        source[1],
                        source[2],
                        "[后台委派完成]\ndelegation_id: "
                                + background.delegationId
                                + "\n请结合以下子任务结果继续处理原任务：\n"
                                + payload);
        completion.setThreadId(StrUtil.blankToDefault(source[3], null));
        completion.setSourceKeyOverride(sourceKey);
        completion.setRunKind(GatewayMessage.RUN_KIND_DELEGATION_COMPLETION);
        final AtomicBoolean terminalCommitted = new AtomicBoolean(false);
        final AtomicReference<GatewayReply> generatedReply = new AtomicReference<GatewayReply>();
        final boolean channelDeliveryRequired =
                deliveryService != null && completion.getPlatform() != PlatformType.MEMORY;
        completion.setReplyCommitter(
                reply -> {
                    generatedReply.compareAndSet(null, reply);
                    try {
                        if (channelDeliveryRequired) {
                            deliverBackgroundReply(sourceKey, reply);
                        }
                        terminalCommitted.set(true);
                        return Boolean.TRUE;
                    } catch (Exception e) {
                        throw new BackgroundReplyDeliveryException(e);
                    }
                });
        boolean completionProcessed = false;
        try {
            while (backgroundDeliveryActive(background)) {
                AgentRunControlService.IncomingReservation reservation = null;
                try {
                    if (agentRunControlService != null) {
                        reservation = reserveParentRun(background);
                        if (reservation == null) {
                            return;
                        }
                    }
                    if (!backgroundDeliveryActive(background)) {
                        return;
                    }
                    GatewayReply pendingDelivery = generatedReply.get();
                    if (pendingDelivery != null && channelDeliveryRequired) {
                        deliverBackgroundReply(sourceKey, pendingDelivery);
                        terminalCommitted.set(true);
                        return;
                    }
                    if (completionProcessed) {
                        return;
                    }
                    // 一旦进入父会话处理，任何失败都只能重试已捕获回复，禁止重放 completion。
                    completionProcessed = true;
                    GatewayReply reply = conversationHolder.get().handleIncoming(completion);
                    if (reply != null) {
                        generatedReply.compareAndSet(null, reply);
                    }
                    GatewayReply replyToCommit = generatedReply.get();
                    if (terminalCommitted.get()) {
                        return;
                    }
                    if (!channelDeliveryRequired && replyToCommit != null) {
                        terminalCommitted.set(true);
                        return;
                    }
                    if (replyToCommit == null) {
                        // 父模型已消费 completion；没有生成回复时也不能通过重跑模型补偿。
                        return;
                    }
                } catch (Exception e) {
                    if (isBackgroundReplyDeliveryFailure(e)) {
                        log.warn(
                                "Background delegation channel delivery failed, retrying: error={}",
                                exceptionLogSummary(e));
                    } else if (!isRunCancellation(e)) {
                        log.warn(
                                "Background delegation result processing failed: error={}",
                                exceptionLogSummary(e));
                        return;
                    } else if (generatedReply.get() == null) {
                        return;
                    }
                } finally {
                    if (reservation != null) {
                        reservation.close();
                    }
                }
                if (!waitForBackgroundDeliveryRetry(background)) {
                    return;
                }
            }
        } finally {
            completion.setReplyCommitter(null);
        }
    }

    /** 判断后台完成结果仍可继续等待父会话处理。 */
    private boolean backgroundDeliveryActive(BackgroundDelegation background) {
        return background != null
                && !background.cancelRequested.get()
                && !closed.get()
                && isParentSessionCurrent(background.sourceKey, background.parentSessionId);
    }

    /** 只对用户抢占和审批延后进行短暂重试，取消、关闭和会话切换会立即退出。 */
    private boolean waitForBackgroundDeliveryRetry(BackgroundDelegation background) {
        if (!backgroundDeliveryActive(background)) {
            return false;
        }
        if (Thread.currentThread().isInterrupted()) {
            if (background.cancelRequested.get() || closed.get()) {
                return false;
            }
            Thread.interrupted();
        }
        try {
            pauseBeforeBackgroundDeliveryRetry();
            return backgroundDeliveryActive(background);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 测试可覆盖的后台完成结果重试间隔。 */
    protected void pauseBeforeBackgroundDeliveryRetry() throws InterruptedException {
        Thread.sleep(50L);
    }

    /** 判断异常链是否表示运行被真实用户抢占。 */
    private boolean isRunCancellation(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof AgentRunCancelledException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /** 判断异常链是否来自后台完成回复的渠道投递失败。 */
    private boolean isBackgroundReplyDeliveryFailure(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof BackgroundReplyDeliveryException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /** 把后台委派继续运行生成的终态回复投递回原渠道。 */
    private void deliverBackgroundReply(String sourceKey, GatewayReply reply) throws Exception {
        if (deliveryService == null || reply == null) {
            return;
        }
        DeliveryRequest request = SourceKeySupport.toDeliveryRequest(sourceKey, reply.getContent());
        request.getChannelExtras().putAll(reply.getChannelExtras());
        deliveryService.deliver(request);
    }

    /** 标识模型已完成但渠道终态投递失败，允许后台完成载荷保留并重试。 */
    private static final class BackgroundReplyDeliveryException extends RuntimeException {
        /** 创建后台回复投递异常。 */
        private BackgroundReplyDeliveryException(Throwable cause) {
            super(cause);
        }
    }

    /** 取消属于指定父会话的后台委派；重复取消不会重复计数。 */
    @Override
    public int cancelBackgroundForSession(String parentSessionId) {
        if (StrUtil.isBlank(parentSessionId)) {
            return 0;
        }
        int cancelled = 0;
        for (BackgroundDelegation background : backgroundRegistry.values()) {
            if (!parentSessionId.equals(background.parentSessionId)) {
                continue;
            }
            if (!background.cancelRequested.compareAndSet(false, true)) {
                continue;
            }
            if (background.future.cancel(true)) {
                cancelled++;
                removeCancelledBackgroundFuture(background.future);
            } else {
                // 任务已自然结束时不把它误报为取消成功，也不遗留取消标记影响完成回流。
                background.cancelRequested.compareAndSet(true, false);
            }
        }
        return cancelled;
    }

    /** 关闭当前 Profile 的后台委派池并取消全部未完成任务。 */
    @Override
    public void shutdown() {
        synchronized (backgroundLifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            for (BackgroundDelegation background : backgroundRegistry.values()) {
                if (background.cancelRequested.compareAndSet(false, true)) {
                    if (background.future.cancel(true)) {
                        removeCancelledBackgroundFuture(background.future);
                    }
                }
            }
            backgroundExecutor.shutdownNow();
        }
    }

    /** 创建同时覆盖正常完成、运行中取消和排队取消的后台任务终结钩子。 */
    private FutureTask<Void> backgroundFuture(
            final BackgroundDelegation background, Runnable scopedTask) {
        return new FutureTask<Void>(scopedTask, null) {
            /** 标记任务已被执行器取出；运行结束后再释放 ownership。 */
            @Override
            public void run() {
                background.started.set(true);
                try {
                    super.run();
                } finally {
                    backgroundRegistry.remove(background.delegationId, background);
                }
            }

            /** 排队阶段取消的任务不会进入 run，由完成钩子负责释放 ownership。 */
            @Override
            protected void done() {
                if (!background.started.get()) {
                    backgroundRegistry.remove(background.delegationId, background);
                }
            }
        };
    }

    /** 从有界队列移除已取消任务，避免取消项继续占用后台委派容量。 */
    private void removeCancelledBackgroundFuture(FutureTask<Void> future) {
        if (backgroundExecutor instanceof ThreadPoolExecutor) {
            ThreadPoolExecutor executor = (ThreadPoolExecutor) backgroundExecutor;
            executor.remove(future);
        }
    }

    /** 核对后台委派仍归属于当前绑定会话；无法核实时拒绝回流。 */
    private boolean isParentSessionCurrent(String sourceKey, String parentSessionId) {
        if (sessionRepository == null || StrUtil.isBlank(parentSessionId)) {
            return true;
        }
        try {
            SessionRecord current = sessionRepository.getBoundSession(sourceKey);
            return current != null && parentSessionId.equals(current.getSessionId());
        } catch (Exception e) {
            log.warn(
                    "Background delegation parent session check failed: error={}",
                    exceptionLogSummary(e));
            return false;
        }
    }

    /**
     * 持续等待父来源键空闲并原子领取入站占位，避免空闲判断后被新消息抢占。
     *
     * @param background 当前后台委派归属。
     * @return 成功领取的占位；取消、关闭、会话切换或中断时返回 null。
     */
    private AgentRunControlService.IncomingReservation reserveParentRun(
            BackgroundDelegation background) {
        if (!agentRunControlService.supportsIncomingReservation()) {
            log.warn("Background delegation result dropped: atomic run reservation unavailable");
            return null;
        }
        while (!background.cancelRequested.get()
                && !closed.get()
                && !Thread.currentThread().isInterrupted()
                && isParentSessionCurrent(background.sourceKey, background.parentSessionId)) {
            AgentRunControlService.IncomingReservation reservation =
                    agentRunControlService.tryReserveIncoming(
                            background.sourceKey, background.parentSessionId);
            if (reservation != null) {
                return reservation;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /**
     * 执行委托Single相关逻辑。
     *
     * @param sourceKey 渠道来源键。
     * @param prompt 提示词参数。
     * @param context 当前请求或运行上下文。
     * @return 返回委托Single结果。
     */
    @Override
    public DelegationResult delegateSingle(String sourceKey, String prompt, String context)
            throws Exception {
        DelegationTask task = new DelegationTask();
        task.setName("delegate");
        task.setPrompt(prompt);
        task.setContext(context);
        return delegateSingle(sourceKey, task);
    }

    /**
     * 执行委托Single相关逻辑。
     *
     * @param sourceKey 渠道来源键。
     * @param task 任务参数。
     * @return 返回委托Single结果。
     */
    @Override
    public DelegationResult delegateSingle(String sourceKey, DelegationTask task) throws Exception {
        String prompt = task == null ? null : task.getPrompt();
        if (StrUtil.isBlank(prompt)) {
            return failureResult("delegate", "委托任务不能为空。");
        }
        if (spawnPaused) {
            return failureResult("delegate", "Subagent spawning is paused.");
        }
        AgentRunContext parentContext = AgentRunContext.current();
        if (parentContext != null && "subagent".equalsIgnoreCase(parentContext.getRunKind())) {
            return failureResult("delegate", "Subagents cannot create subagents.");
        }
        int depth = resolveDepth(parentContext);
        int maxDepth =
                appConfig == null ? 1 : Math.max(1, appConfig.getTask().getSubagentMaxDepth());
        if (depth > maxDepth) {
            if (parentContext != null) {
                parentContext.event(
                        "subagent.rejected", "子 Agent depth 超限：" + depth + "/" + maxDepth);
            }
            return failureResult("delegate", "Subagent depth limit exceeded.");
        }
        if (!concurrencyLimiter.tryAcquire()) {
            if (parentContext != null) {
                parentContext.event("subagent.rejected", "子 Agent 并发数已达上限");
            }
            return failureResult("delegate", "Subagent concurrency limit exceeded.");
        }

        SubagentRunRecord subagent = null;
        try {
            SessionRecord parentSession = sessionRepository.getBoundSession(sourceKey);
            String subagentId = "sa-" + IdSupport.newId();
            String childSourceKey = childSourceKey(sourceKey, subagentId);
            List<String> childAllowedTools =
                    applyAllowedTools(
                            sourceKey,
                            childSourceKey,
                            task == null ? null : task.getAllowedTools(),
                            task == null ? null : task.getToolsets());
            applyBlockedTools(childSourceKey);
            prepareChildSession(childSourceKey, parentSession);

            if (conversationHolder.get() == null) {
                return failureResult("delegate", "Conversation orchestrator is not ready");
            }
            GatewayMessage message =
                    new GatewayMessage(PlatformType.MEMORY, "", "", decoratePrompt(task));
            message.setSourceKeyOverride(childSourceKey);
            if (StrUtil.isNotBlank(task.getModel())) {
                message.setModelOverride(task.getModel().trim());
            }
            message.setRunKind(GatewayMessage.RUN_KIND_SUBAGENT);
            message.setAllowedToolsOverride(childAllowedTools);
            subagent =
                    startSubagent(
                            subagentId, sourceKey, childSourceKey, task, parentContext, depth);
            if (isInterrupted(subagentId)) {
                finishInterrupted(subagent, "Subagent interrupted before start.");
                return failureResult(subagent.getName(), "Subagent interrupted before start.");
            }
            GatewayReply reply = conversationHolder.get().handleIncoming(message);
            finishSubagent(subagent, reply);
            if ("interrupted".equals(subagent.getStatus())) {
                return failureResult(subagent.getName(), "Subagent interrupted.");
            }

            DelegationResult result = new DelegationResult();
            result.setSubagentId(subagentId);
            result.setName(
                    StrUtil.blankToDefault(task == null ? null : task.getName(), "delegate"));
            result.setSessionId(reply == null ? null : reply.getSessionId());
            result.setSourceKey(childSourceKey);
            result.setContent(reply == null ? "" : reply.getContent());
            result.setError(reply != null && reply.isError());
            if (subagent.getChildRunId() != null) {
                result.setRunId(subagent.getChildRunId());
            }
            return result;
        } catch (Exception e) {
            finishFailed(subagent, e.getMessage());
            log.warn(
                    "delegateSingle failed: sourceKey={}, prompt={}, error={}",
                    sourceKey,
                    SecretRedactor.redact(prompt, 1000),
                    EngineSupport.safeError(e));
            return failureResult("delegate", e.getMessage());
        } finally {
            concurrencyLimiter.release();
        }
    }

    /** 构造合法子来源键：复用父平台、会话和用户，把子 Agent 放入独立线程。 */
    private String childSourceKey(String parentSourceKey, String subagentId) {
        String[] parts = SourceKeySupport.split(parentSourceKey);
        String platform = StrUtil.blankToDefault(parts[0], PlatformType.MEMORY.name());
        String chatId = StrUtil.blankToDefault(parts[1], "delegate");
        String userId = StrUtil.blankToDefault(parts[2], "agent");
        String parentThread = StrUtil.nullToEmpty(parts[3]).trim();
        String childThread =
                StrUtil.isBlank(parentThread)
                        ? "delegate-" + subagentId
                        : parentThread + "-delegate-" + subagentId;
        String profile = SourceKeySupport.profile(parentSourceKey);
        String prefix = profile == null ? "" : "profile:" + profile + ":";
        return prefix + platform + ":" + chatId + ":" + childThread + ":" + userId;
    }

    /**
     * 写入Spawn Paused。
     *
     * @param paused paused 参数。
     */
    @Override
    public void setSpawnPaused(boolean paused) {
        this.spawnPaused = paused;
    }

    /**
     * 判断是否Spawn Paused。
     *
     * @return 如果Spawn Paused满足条件则返回 true，否则返回 false。
     */
    @Override
    public boolean isSpawnPaused() {
        return spawnPaused;
    }

    /**
     * 中断子Agent。
     *
     * @param subagentId 子Agent标识。
     * @return 返回interrupt Subagent结果。
     */
    @Override
    public boolean interruptSubagent(String subagentId) {
        return interruptSubagentForParent(null, subagentId);
    }

    /**
     * 中断指定父来源创建的子 Agent。
     *
     * @param parentSourceKey 父会话来源键。
     * @param subagentId 子 Agent 标识。
     * @return 当前来源拥有且成功请求中断时返回 true。
     */
    @Override
    public boolean interruptSubagent(String parentSourceKey, String subagentId) {
        if (StrUtil.isBlank(parentSourceKey)) {
            return false;
        }
        return interruptSubagentForParent(parentSourceKey, subagentId);
    }

    /**
     * 按可选父来源约束执行子 Agent 中断。
     *
     * @param parentSourceKey 父会话来源键；为空时保留管理员全局操作语义。
     * @param subagentId 子 Agent 标识。
     * @return 成功请求中断时返回 true。
     */
    private boolean interruptSubagentForParent(String parentSourceKey, String subagentId) {
        SubagentRunRecord record = activeRegistry.get(subagentId);
        if (record == null
                || (parentSourceKey != null
                        && !parentSourceKey.equals(record.getParentSourceKey()))) {
            return false;
        }
        synchronized (record) {
            if (!record.isActive()) {
                return false;
            }
            record.setInterruptRequested(true);
            record.setStatus("interrupting");
            record.setHeartbeatAt(System.currentTimeMillis());
            saveSubagent(record);
        }
        stopInterruptedChildWhenRegistered(record);
        return true;
    }

    /** child run 尚未登记时短暂重试，避免 interrupt 落入启动注册窗口后失效。 */
    private void stopInterruptedChildWhenRegistered(SubagentRunRecord record) {
        if (agentRunControlService == null) {
            return;
        }
        long deadline = System.currentTimeMillis() + INTERRUPT_REGISTRATION_WAIT_MILLIS;
        while (isInterruptPending(record)) {
            AgentRunStopResult result = agentRunControlService.stop(record.getChildSourceKey());
            if (result != null && result.isActiveRun()) {
                return;
            }
            if (System.currentTimeMillis() >= deadline) {
                return;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 在线程间安全读取仍需递交给 child run 的中断请求。 */
    private boolean isInterruptPending(SubagentRunRecord record) {
        synchronized (record) {
            return record.isActive() && record.isInterruptRequested();
        }
    }

    /**
     * 执行activeSubagents相关逻辑。
     *
     * @return 返回active Subagents结果。
     */
    @Override
    public List<Map<String, Object>> activeSubagents() {
        return activeSubagentsForParent(null);
    }

    /**
     * 查询指定父来源创建的活跃子 Agent。
     *
     * @param parentSourceKey 父会话来源键。
     * @return 返回当前来源的活跃子 Agent。
     */
    @Override
    public List<Map<String, Object>> activeSubagents(String parentSourceKey) {
        if (StrUtil.isBlank(parentSourceKey)) {
            return java.util.Collections.emptyList();
        }
        return activeSubagentsForParent(parentSourceKey);
    }

    /**
     * 按可选父来源约束构建活跃子 Agent 列表。
     *
     * @param parentSourceKey 父会话来源键；为空时保留管理员全局查询语义。
     * @return 返回匹配的活跃子 Agent。
     */
    private List<Map<String, Object>> activeSubagentsForParent(String parentSourceKey) {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (SubagentRunRecord record : activeRegistry.values()) {
            if (parentSourceKey == null || parentSourceKey.equals(record.getParentSourceKey())) {
                list.add(activeSubagentMap(record));
            }
        }
        return list;
    }

    /**
     * 将活跃子 Agent 记录转换为现有管理接口返回结构。
     *
     * @param record 子 Agent 运行记录。
     * @return 返回管理接口状态映射。
     */
    private Map<String, Object> activeSubagentMap(SubagentRunRecord record) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("subagent_id", record.getSubagentId());
        map.put("parent_run_id", record.getParentRunId());
        map.put("child_run_id", record.getChildRunId());
        map.put("source_key", record.getChildSourceKey());
        map.put("status", record.getStatus());
        map.put("depth", record.getDepth());
        map.put("heartbeat_at", record.getHeartbeatAt());
        map.put("output_tail", record.getOutputTailJson());
        return map;
    }

    /**
     * 执行委托Batch相关逻辑。
     *
     * @param sourceKey 渠道来源键。
     * @param tasks tasks 参数。
     * @return 返回委托Batch结果。
     */
    @Override
    public List<DelegationResult> delegateBatch(final String sourceKey, List<DelegationTask> tasks)
            throws Exception {
        List<DelegationResult> results = new ArrayList<DelegationResult>();
        if (isSubagentRun()) {
            results.add(failureResult("delegate", "Subagents cannot create subagents."));
            return results;
        }
        if (tasks == null || tasks.isEmpty()) {
            return results;
        }

        int concurrency =
                Math.min(
                        appConfig == null
                                ? 3
                                : Math.max(1, appConfig.getTask().getSubagentMaxConcurrency()),
                        tasks.size());
        ExecutorService executorService =
                BoundedExecutorFactory.fixed(
                        "solonclaw-delegation-batch", concurrency, Math.max(1, tasks.size()));
        try {
            final AgentRunContext parentContext = AgentRunContext.current();
            List<Future<DelegationResult>> futures = new ArrayList<Future<DelegationResult>>();
            for (final DelegationTask task : tasks) {
                futures.add(
                        executorService.submit(
                                ProfileRuntimeScope.capture(
                                        new Callable<DelegationResult>() {
                                            /**
                                             * 执行回调调用并返回结果。
                                             *
                                             * @return 返回call结果。
                                             */
                                            @Override
                                            public DelegationResult call() throws Exception {
                                                AgentRunContext previous =
                                                        AgentRunContext.current();
                                                AgentRunContext.setCurrent(parentContext);
                                                try {
                                                    DelegationResult result =
                                                            delegateSingle(sourceKey, task);
                                                    result.setName(
                                                            StrUtil.blankToDefault(
                                                                    task.getName(), "delegate"));
                                                    return result;
                                                } finally {
                                                    AgentRunContext.setCurrent(previous);
                                                }
                                            }
                                        })));
            }

            for (Future<DelegationResult> future : futures) {
                try {
                    results.add(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.warn(
                            "delegateBatch child failed: sourceKey={}, error={}",
                            sourceKey,
                            EngineSupport.safeError(e));
                    results.add(failureResult("delegate", e.getMessage()));
                }
            }
            return results;
        } finally {
            executorService.shutdownNow();
        }
    }

    /** 对子会话应用固定黑名单。 */
    private void applyBlockedTools(String childSourceKey) throws Exception {
        for (String blockedTool : BLOCKED_TOOLS) {
            preferenceStore.setToolEnabled(childSourceKey, blockedTool, false);
        }
    }

    /**
     * 应用Allowed工具。
     *
     * @param parentSourceKey parent来源键标识或键值。
     * @param childSourceKey child来源键标识或键值。
     * @param allowedTools allowedTools开关值。
     * @param toolsets toolsets 参数。
     */
    private List<String> applyAllowedTools(
            String parentSourceKey,
            String childSourceKey,
            List<String> allowedTools,
            List<String> toolsets)
            throws Exception {
        LinkedHashSet<String> requested = new LinkedHashSet<String>();
        if (allowedTools != null) {
            for (String toolName : allowedTools) {
                if (StrUtil.isNotBlank(toolName)) {
                    requested.add(toolName.trim());
                }
            }
        }
        requested.addAll(AgentRuntimePolicy.expandToolSelectors(toolsets));
        for (String toolName : ALL_TOOLS) {
            preferenceStore.setToolEnabled(childSourceKey, toolName, false);
        }
        List<String> effective = new ArrayList<String>();
        for (String toolName : requested) {
            String normalized = StrUtil.nullToEmpty(toolName).trim();
            if (ALL_TOOLS.contains(normalized)
                    && !BLOCKED_TOOLS.contains(normalized)
                    && isParentToolEnabled(parentSourceKey, normalized)) {
                preferenceStore.setToolEnabled(childSourceKey, normalized, true);
                effective.add(normalized);
            }
        }
        return effective;
    }

    /** 判断父会话、父 Agent 范围和父运行临时策略是否共同允许某个工具。 */
    private boolean isParentToolEnabled(String parentSourceKey, String toolName) throws Exception {
        String normalized = StrUtil.nullToEmpty(toolName).trim();
        boolean preferenceEnabled =
                ToolNameConstants.TOOL_GATEWAY.equals(normalized)
                        ? preferenceStore.isToolEnabled(parentSourceKey, normalized, false)
                        : preferenceStore.isToolEnabled(parentSourceKey, normalized);
        if (!preferenceEnabled) {
            return false;
        }
        AgentRunContext parentContext = AgentRunContext.current();
        if (parentContext == null || !parentSourceKey.equals(parentContext.getSourceKey())) {
            return false;
        }
        if (!parentContext.hasEnabledToolNamesSnapshot()
                || !parentContext.getEnabledToolNames().contains(normalized)) {
            return false;
        }
        List<String> temporaryAllowed = parentContext.getAllowedToolNames();
        return temporaryAllowed.isEmpty() || temporaryAllowed.contains(normalized);
    }

    /** 在服务边界拒绝子 Agent 递归委派。 */
    private void rejectNestedDelegation() {
        if (isSubagentRun()) {
            throw new IllegalStateException("Subagents cannot create subagents.");
        }
    }

    /** 判断当前运行是否为一次性子 Agent。 */
    private boolean isSubagentRun() {
        AgentRunContext current = AgentRunContext.current();
        return current != null && "subagent".equalsIgnoreCase(current.getRunKind());
    }

    /** 预先创建子会话并写入父会话关系。 */
    private void prepareChildSession(String childSourceKey, SessionRecord parentSession)
            throws Exception {
        SessionRecord existing = sessionRepository.getBoundSession(childSourceKey);
        if (existing != null) {
            return;
        }

        SessionRecord childSession = sessionRepository.bindNewSession(childSourceKey);
        if (parentSession != null) {
            childSession.setParentSessionId(parentSession.getSessionId());
        }
        sessionRepository.save(childSession);
    }

    /** 拼接委托上下文。 */
    private String decoratePrompt(DelegationTask task) {
        String prompt = task == null ? "" : task.getPrompt();
        String context = task == null ? "" : task.getContext();
        StringBuilder buffer = new StringBuilder();
        buffer.append("任务目标:\n").append(prompt);
        if (StrUtil.isBlank(context)) {
            context = "";
        }
        if (StrUtil.isNotBlank(context)) {
            buffer.append("\n\n补充上下文:\n").append(context);
        }
        if (task != null && StrUtil.isNotBlank(task.getExpectedOutput())) {
            buffer.append("\n\n期望输出:\n").append(task.getExpectedOutput());
        }
        if (task != null && StrUtil.isNotBlank(task.getWriteScope())) {
            buffer.append("\n\n写入范围:\n").append(task.getWriteScope());
        }
        return buffer.toString();
    }

    /** 构造失败结果，避免单个子任务异常打断整个批次。 */
    private DelegationResult failureResult(String name, String message) {
        DelegationResult result = new DelegationResult();
        result.setName(StrUtil.blankToDefault(name, "delegate"));
        result.setError(true);
        result.setContent(
                SecretRedactor.redact(StrUtil.blankToDefault(message, "delegation failed"), 1000));
        return result;
    }

    /**
     * 生成仅用于日志的异常摘要，只保留异常类型，避免把会话内容或凭据信息写入日志。
     *
     * @param error 异常对象。
     * @return 返回不含业务明文的异常摘要。
     */
    private String exceptionLogSummary(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        return StrUtil.blankToDefault(error.getClass().getSimpleName(), error.getClass().getName());
    }

    /**
     * 启动Subagent。
     *
     * @param subagentId 子Agent标识。
     * @param parentSourceKey parent来源键标识或键值。
     * @param childSourceKey child来源键标识或键值。
     * @param task 任务参数。
     * @param parentContext parent上下文上下文。
     * @param depth depth 参数。
     * @return 返回Subagent结果。
     */
    private SubagentRunRecord startSubagent(
            String subagentId,
            String parentSourceKey,
            String childSourceKey,
            DelegationTask task,
            AgentRunContext parentContext,
            int depth) {
        SubagentRunRecord record = new SubagentRunRecord();
        long now = System.currentTimeMillis();
        record.setSubagentId(subagentId);
        record.setParentRunId(parentContext == null ? null : parentContext.getRunId());
        record.setParentSourceKey(parentSourceKey);
        record.setChildSourceKey(childSourceKey);
        record.setName(StrUtil.blankToDefault(task == null ? null : task.getName(), "delegate"));
        record.setGoalPreview(
                com.jimuqu.solon.claw.core.model.AgentRunContext.safe(
                        task == null ? null : task.getPrompt(), 1000));
        record.setStatus("running");
        record.setActive(true);
        record.setDepth(depth);
        record.setStartedAt(now);
        record.setHeartbeatAt(now);
        saveSubagent(record);
        activeRegistry.put(subagentId, record);
        if (parentContext != null) {
            parentContext.event("subagent.spawned", "子 Agent 已启动：" + record.getName());
            incrementSubtaskCount(parentContext.getRunId());
        }
        return record;
    }

    /**
     * 执行finish子Agent相关逻辑。
     *
     * @param record 记录参数。
     * @param reply 回复参数。
     */
    private void finishSubagent(SubagentRunRecord record, GatewayReply reply) {
        if (record == null) {
            return;
        }
        synchronized (record) {
            if (record.isInterruptRequested()) {
                finishInterruptedLocked(record, "Subagent interrupted.");
            } else {
                record.setStatus(reply != null && reply.isError() ? "failed" : "success");
                record.setSessionId(reply == null ? null : reply.getSessionId());
                record.setChildRunId(
                        resolveLatestRunId(record.getChildSourceKey(), record.getSessionId()));
                record.setError(reply != null && reply.isError() ? reply.getContent() : null);
                record.setOutputTailJson(buildTailJson(reply == null ? "" : reply.getContent()));
                finishRecord(record);
            }
        }
        activeRegistry.remove(record.getSubagentId(), record);
    }

    /**
     * 执行finishInterrupted相关逻辑。
     *
     * @param record 记录参数。
     * @param message 平台消息或错误消息。
     */
    private void finishInterrupted(SubagentRunRecord record, String message) {
        if (record == null) {
            return;
        }
        synchronized (record) {
            finishInterruptedLocked(record, message);
        }
        activeRegistry.remove(record.getSubagentId(), record);
    }

    /** 在记录锁内写入中断终态。 */
    private void finishInterruptedLocked(SubagentRunRecord record, String message) {
        record.setStatus("interrupted");
        record.setError(message);
        record.setInterruptRequested(true);
        finishRecord(record);
    }

    /** 将执行异常收敛为失败终态；已收到 interrupt 时保持中断语义。 */
    private void finishFailed(SubagentRunRecord record, String message) {
        if (record == null) {
            return;
        }
        synchronized (record) {
            if (!record.isActive()) {
                return;
            }
            if (record.isInterruptRequested()) {
                finishInterruptedLocked(record, message);
            } else {
                record.setStatus("failed");
                record.setError(message);
                finishRecord(record);
            }
        }
        activeRegistry.remove(record.getSubagentId(), record);
    }

    /** 在记录锁内写入公共终态时间并持久化。 */
    private void finishRecord(SubagentRunRecord record) {
        record.setActive(false);
        record.setFinishedAt(System.currentTimeMillis());
        record.setHeartbeatAt(record.getFinishedAt());
        saveSubagent(record);
    }

    /**
     * 判断是否Interrupted。
     *
     * @param subagentId 子Agent标识。
     * @return 如果Interrupted满足条件则返回 true，否则返回 false。
     */
    private boolean isInterrupted(String subagentId) {
        SubagentRunRecord record = activeRegistry.get(subagentId);
        if (record == null) {
            return false;
        }
        synchronized (record) {
            return record.isInterruptRequested();
        }
    }

    /**
     * 解析Depth。
     *
     * @param parentContext parent上下文上下文。
     * @return 返回解析后的Depth。
     */
    private int resolveDepth(AgentRunContext parentContext) {
        if (parentContext == null || StrUtil.isBlank(parentContext.getRunId())) {
            return 1;
        }
        int depth = 1;
        try {
            String runId = parentContext.getRunId();
            LinkedHashSet<String> visited = new LinkedHashSet<String>();
            for (int i = 0;
                    agentRunRepository != null
                            && i < MAX_DEPTH_LOOKUP
                            && StrUtil.isNotBlank(runId)
                            && visited.add(runId);
                    i++) {
                AgentRunRecord parent = agentRunRepository.findRun(runId);
                if (parent == null || !"subagent".equals(parent.getRunKind())) {
                    break;
                }
                depth++;
                runId = parent.getParentRunId();
            }
        } catch (Exception e) {
            log.debug(
                    "resolveDepth failed; using resolved depth. error={}", exceptionLogSummary(e));
        }
        return depth;
    }

    /**
     * 执行incrementSubtask次数相关逻辑。
     *
     * @param parentRunId parent运行标识。
     */
    private void incrementSubtaskCount(String parentRunId) {
        if (agentRunRepository == null || StrUtil.isBlank(parentRunId)) {
            return;
        }
        try {
            AgentRunRecord parent = agentRunRepository.findRun(parentRunId);
            if (parent != null) {
                parent.setSubtaskCount(parent.getSubtaskCount() + 1);
                parent.setLastActivityAt(System.currentTimeMillis());
                agentRunRepository.saveRun(parent);
            }
        } catch (Exception e) {
            log.debug(
                    "incrementSubtaskCount failed; continuing delegation. error={}",
                    exceptionLogSummary(e));
        }
    }

    /**
     * 解析Latest运行标识。
     *
     * @param childSourceKey child来源键标识或键值。
     * @param sessionId 当前会话标识。
     * @return 返回解析后的Latest运行标识。
     */
    private String resolveLatestRunId(String childSourceKey, String sessionId) {
        if (agentRunRepository == null || StrUtil.isBlank(childSourceKey)) {
            return null;
        }
        try {
            List<AgentRunRecord> runs = agentRunRepository.listActiveBySource(childSourceKey, 1);
            if (!runs.isEmpty()) {
                return runs.get(0).getRunId();
            }
            if (StrUtil.isNotBlank(sessionId)) {
                List<AgentRunRecord> bySession = agentRunRepository.listBySession(sessionId, 1);
                if (!bySession.isEmpty()) {
                    return bySession.get(0).getRunId();
                }
            }
        } catch (Exception e) {
            log.debug(
                    "resolveLatestRunId failed; child run id unavailable. error={}",
                    exceptionLogSummary(e));
        }
        return null;
    }

    /**
     * 保存Subagent。
     *
     * @param record 记录参数。
     */
    private void saveSubagent(SubagentRunRecord record) {
        if (agentRunRepository == null) {
            return;
        }
        try {
            agentRunRepository.saveSubagentRun(record);
        } catch (Exception e) {
            log.debug(
                    "saveSubagent failed; keeping in-memory delegation state. error={}",
                    exceptionLogSummary(e));
        }
    }

    /**
     * 构建Tail JSON。
     *
     * @param content 待处理内容。
     * @return 返回创建好的Tail JSON。
     */
    private String buildTailJson(String content) {
        ONode array = new ONode().asArray();
        ONode item = new ONode().asObject();
        item.set("preview", com.jimuqu.solon.claw.core.model.AgentRunContext.safe(content, 1000));
        item.set("is_error", false);
        array.add(item);
        return array.toJson();
    }

    /** 单个后台委派的父会话归属与进程内取消句柄。 */
    private static final class BackgroundDelegation {
        /** 后台委派标识。 */
        private final String delegationId;

        /** 父会话来源键。 */
        private final String sourceKey;

        /** 发起委派时捕获的父会话标识。 */
        private final String parentSessionId;

        /** 是否已请求取消，确保取消和计数幂等。 */
        private final AtomicBoolean cancelRequested = new AtomicBoolean(false);

        /** 任务是否已由执行器取出，用于区分排队取消与运行中取消。 */
        private final AtomicBoolean started = new AtomicBoolean(false);

        /** 有界执行器中的实际任务句柄。 */
        private FutureTask<Void> future;

        /** 创建后台委派归属记录。 */
        private BackgroundDelegation(
                String delegationId, String sourceKey, String parentSessionId) {
            this.delegationId = delegationId;
            this.sourceKey = sourceKey;
            this.parentSessionId = parentSessionId;
        }
    }
}
