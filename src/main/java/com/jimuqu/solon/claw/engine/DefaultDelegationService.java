package com.jimuqu.solon.claw.engine;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.agent.AgentRuntimePolicy;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.AgentRunContext;
import com.jimuqu.solon.claw.core.model.AgentRunRecord;
import com.jimuqu.solon.claw.core.model.DelegationResult;
import com.jimuqu.solon.claw.core.model.DelegationTask;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.model.SubagentRunRecord;
import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.AgentRunControlService;
import com.jimuqu.solon.claw.core.service.DelegationService;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.profile.ProfileRuntimeScope;
import com.jimuqu.solon.claw.profile.ProfileManager;
import com.jimuqu.solon.claw.profile.ProfileView;
import com.jimuqu.solon.claw.gateway.service.ProfileMultiplexRuntimeManager;
import com.jimuqu.solon.claw.gateway.service.ProfileRuntimeBundle;
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
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
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

    /** 记录默认委托中的concurrencyLimiter。 */
    private final Semaphore concurrencyLimiter;

    /** 顶层委派后台执行器；有界队列防止模型连续委派造成无上限内存增长。 */
    private final ExecutorService backgroundExecutor;

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
        if (tasks == null || tasks.isEmpty()) {
            throw new IllegalArgumentException("No tasks provided");
        }
        final String delegationId = "dg-" + IdSupport.newId();
        final AgentRunContext parentContext = AgentRunContext.current();
        try {
            backgroundExecutor.submit(
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
                                    deliverBackgroundResult(sourceKey, delegationId, results);
                                }
                            }));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            throw new IllegalStateException("Background delegation capacity is full");
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
            String sourceKey, String delegationId, List<DelegationResult> results) {
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
                                + delegationId
                                + "\n请结合以下子任务结果继续处理原任务：\n"
                                + payload);
        completion.setThreadId(StrUtil.blankToDefault(source[3], null));
        completion.setSourceKeyOverride(sourceKey);
        try {
            waitForParentRun(sourceKey);
            GatewayReply reply = conversationHolder.get().handleIncoming(completion);
            if (deliveryService != null
                    && reply != null
                    && completion.getPlatform() != PlatformType.MEMORY) {
                DeliveryRequest request =
                        SourceKeySupport.toDeliveryRequest(sourceKey, reply.getContent());
                request.getChannelExtras().putAll(reply.getChannelExtras());
                deliveryService.deliver(request);
            }
        } catch (Exception e) {
            log.warn(
                    "Background delegation result delivery failed: error={}",
                    exceptionLogSummary(e));
        }
    }

    /** 父 run 仍在收敛当前轮时等待其退出，避免完成消息误触发 interrupt/steer busy 策略。 */
    private void waitForParentRun(String sourceKey) {
        if (agentRunControlService == null) {
            return;
        }
        long deadline = System.currentTimeMillis() + 30000L;
        while (agentRunControlService.isRunning(sourceKey)
                && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (agentRunControlService.isRunning(sourceKey)) {
            log.warn("Background delegation parent run did not drain before delivery timeout");
        }
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

        try {
            String targetProfile = resolveTargetProfile(task);
            ProfileRuntimeScope.Context currentProfile = ProfileRuntimeScope.current();
            String currentProfileName =
                    currentProfile == null ? "default" : currentProfile.getProfile();
            if (StrUtil.isNotBlank(targetProfile) && !targetProfile.equals(currentProfileName)) {
                return delegateToProfile(sourceKey, task, targetProfile);
            }
            SessionRecord parentSession = sessionRepository.getBoundSession(sourceKey);
            String subagentId = "sa-" + IdSupport.newId();
            String childSourceKey = childSourceKey(sourceKey, subagentId);
            cloneToolVisibility(sourceKey, childSourceKey);
            applyAllowedTools(
                    sourceKey,
                    childSourceKey,
                    task == null ? null : task.getAllowedTools(),
                    task == null ? null : task.getToolsets());
            applyBlockedTools(childSourceKey, task == null ? null : task.getRole());
            prepareChildSession(childSourceKey, parentSession);

            if (conversationHolder.get() == null) {
                return failureResult("delegate", "Conversation orchestrator is not ready");
            }
            GatewayMessage message =
                    new GatewayMessage(PlatformType.MEMORY, "", "", decoratePrompt(task));
            message.setSourceKeyOverride(childSourceKey);
            SubagentRunRecord subagent =
                    startSubagent(
                            subagentId, sourceKey, childSourceKey, task, parentContext, depth);
            if (isInterrupted(subagentId)) {
                finishInterrupted(subagent, "Subagent interrupted before start.");
                return failureResult(subagent.getName(), "Subagent interrupted before start.");
            }
            GatewayReply reply = conversationHolder.get().handleIncoming(message);
            finishSubagent(subagent, reply);

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

    /** 将任务交给目标 Profile 的独立运行时，避免跨 Profile 复用当前会话库和 Bean。 */
    private DelegationResult delegateToProfile(
            String sourceKey, DelegationTask task, String targetProfile) throws Exception {
        ProfileMultiplexRuntimeManager manager =
                org.noear.solon.Solon.context().getBean(ProfileMultiplexRuntimeManager.class);
        if (manager == null) {
            return failureResult(task.getName(), "Profile runtime manager is not ready.");
        }
        ProfileRuntimeBundle runtime = manager.requireRuntime(targetProfile);
        String subagentId = "sa-" + IdSupport.newId();
        String childSourceKey = childSourceKey(sourceKey, subagentId);
        GatewayMessage message =
                new GatewayMessage(PlatformType.MEMORY, "", "", decoratePrompt(task));
        message.setSourceKeyOverride(childSourceKey);
        GatewayReply reply = runtime.handle(message);
        DelegationResult result = new DelegationResult();
        result.setSubagentId(subagentId);
        result.setName(StrUtil.blankToDefault(task.getName(), "delegate"));
        result.setSessionId(reply == null ? null : reply.getSessionId());
        result.setSourceKey(childSourceKey);
        result.setContent(reply == null ? "" : reply.getContent());
        result.setError(reply != null && reply.isError());
        return result;
    }

    /** 优先采用显式 Profile；否则仅在任务明确命中名称或完整职责说明时自动选择。 */
    private String resolveTargetProfile(DelegationTask task) {
        if (task == null) {
            return null;
        }
        if (StrUtil.isNotBlank(task.getProfile())) {
            return task.getProfile().trim();
        }
        ProfileManager manager;
        try {
            manager = org.noear.solon.Solon.context().getBean(ProfileManager.class);
        } catch (RuntimeException e) {
            return null;
        }
        if (manager == null) {
            return null;
        }
        String text = (StrUtil.nullToEmpty(task.getPrompt()) + " " + StrUtil.nullToEmpty(task.getContext()))
                .toLowerCase(java.util.Locale.ROOT);
        try {
            for (ProfileView view : manager.listProfileViews()) {
                String name = view.getName();
                String description = view.getDescription();
                if ((StrUtil.isNotBlank(name) && text.contains(name.toLowerCase(java.util.Locale.ROOT)))
                        || (StrUtil.isNotBlank(description)
                                && text.contains(description.toLowerCase(java.util.Locale.ROOT)))) {
                    return name;
                }
            }
        } catch (Exception e) {
            log.debug("Profile delegation routing metadata unavailable: {}", EngineSupport.safeError(e));
        }
        return null;
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
        return platform + ":" + chatId + ":" + childThread + ":" + userId;
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
        SubagentRunRecord record = activeRegistry.get(subagentId);
        if (record == null) {
            return false;
        }
        record.setInterruptRequested(true);
        record.setStatus("interrupting");
        record.setHeartbeatAt(System.currentTimeMillis());
        saveSubagent(record);
        if (agentRunControlService != null) {
            agentRunControlService.stop(record.getChildSourceKey());
        }
        return true;
    }

    /**
     * 执行activeSubagents相关逻辑。
     *
     * @return 返回active Subagents结果。
     */
    @Override
    public List<Map<String, Object>> activeSubagents() {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (SubagentRunRecord record : activeRegistry.values()) {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("subagent_id", record.getSubagentId());
            map.put("parent_run_id", record.getParentRunId());
            map.put("child_run_id", record.getChildRunId());
            map.put("source_key", record.getChildSourceKey());
            map.put("status", record.getStatus());
            map.put("depth", record.getDepth());
            map.put("heartbeat_at", record.getHeartbeatAt());
            map.put("output_tail", record.getOutputTailJson());
            list.add(map);
        }
        return list;
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
        if (tasks == null || tasks.isEmpty()) {
            return results;
        }

        ExecutorService executorService =
                Executors.newFixedThreadPool(
                        Math.min(
                                appConfig == null
                                        ? 3
                                        : Math.max(
                                                1, appConfig.getTask().getSubagentMaxConcurrency()),
                                tasks.size()));
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

    /** 复制父来源键的工具可见性。 */
    private void cloneToolVisibility(String parentSourceKey, String childSourceKey)
            throws Exception {
        for (String toolName : ALL_TOOLS) {
            boolean enabled = preferenceStore.isToolEnabled(parentSourceKey, toolName);
            preferenceStore.setToolEnabled(childSourceKey, toolName, enabled);
        }
    }

    /**
     * 对子会话应用固定黑名单；orchestrator 仅保留继续委派能力，其它高风险工具仍禁用。
     *
     * @param childSourceKey 子代理来源键。
     * @param role 子代理角色。
     */
    private void applyBlockedTools(String childSourceKey, String role) throws Exception {
        for (String blockedTool : BLOCKED_TOOLS) {
            if (ToolNameConstants.DELEGATE_TASK.equals(blockedTool)
                    && "orchestrator".equalsIgnoreCase(StrUtil.nullToEmpty(role).trim())) {
                continue;
            }
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
    private void applyAllowedTools(
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
        if (requested.isEmpty()) {
            return;
        }
        for (String toolName : ALL_TOOLS) {
            preferenceStore.setToolEnabled(childSourceKey, toolName, false);
        }
        for (String toolName : requested) {
            String normalized = StrUtil.nullToEmpty(toolName).trim();
            if (ALL_TOOLS.contains(normalized)
                    && isParentToolEnabled(parentSourceKey, normalized)) {
                preferenceStore.setToolEnabled(childSourceKey, normalized, true);
            }
        }
    }

    /** 判断父会话是否允许某个工具。 */
    private boolean isParentToolEnabled(String parentSourceKey, String toolName) throws Exception {
        String normalized = StrUtil.nullToEmpty(toolName).trim();
        return preferenceStore.isToolEnabled(parentSourceKey, normalized);
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
            childSession.setModelOverride(parentSession.getModelOverride());
            childSession.setServiceTierOverride(parentSession.getServiceTierOverride());
            childSession.setReasoningEffortOverride(parentSession.getReasoningEffortOverride());
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
        record.setStatus(reply != null && reply.isError() ? "failed" : "success");
        record.setSessionId(reply == null ? null : reply.getSessionId());
        record.setChildRunId(resolveLatestRunId(record.getChildSourceKey(), record.getSessionId()));
        record.setError(reply != null && reply.isError() ? reply.getContent() : null);
        record.setOutputTailJson(buildTailJson(reply == null ? "" : reply.getContent()));
        record.setActive(false);
        record.setFinishedAt(System.currentTimeMillis());
        record.setHeartbeatAt(record.getFinishedAt());
        saveSubagent(record);
        activeRegistry.remove(record.getSubagentId());
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
        record.setStatus("interrupted");
        record.setError(message);
        record.setActive(false);
        record.setInterruptRequested(true);
        record.setFinishedAt(System.currentTimeMillis());
        record.setHeartbeatAt(record.getFinishedAt());
        saveSubagent(record);
        activeRegistry.remove(record.getSubagentId());
    }

    /**
     * 判断是否Interrupted。
     *
     * @param subagentId 子Agent标识。
     * @return 如果Interrupted满足条件则返回 true，否则返回 false。
     */
    private boolean isInterrupted(String subagentId) {
        SubagentRunRecord record = activeRegistry.get(subagentId);
        return record != null && record.isInterruptRequested();
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
        try {
            AgentRunRecord parent =
                    agentRunRepository == null
                            ? null
                            : agentRunRepository.findRun(parentContext.getRunId());
            if (parent != null && "subagent".equals(parent.getRunKind())) {
                return 2;
            }
        } catch (Exception e) {
            log.debug("resolveDepth failed; using default depth. error={}", exceptionLogSummary(e));
        }
        return 1;
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
}
