package com.jimuqu.solon.claw.gateway.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.service.AgentRunControlService;
import com.jimuqu.solon.claw.support.constants.RuntimePathConstants;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.noear.snack4.ONode;

/** 承载消息网关重启Coordinator相关状态和辅助逻辑。 */
public class GatewayRestartCoordinator {
    /** 重启REQUESTERMARKER的统一常量值。 */
    public static final String RESTART_REQUESTER_MARKER = "restart-requester.json";

    /** 记录消息网关重启Coordinator中的draining。 */
    private final AtomicBoolean draining = new AtomicBoolean(false);

    /** 保存执行器服务执行组件，负责调度异步或定时任务。 */
    private final ScheduledExecutorService executorService;

    /** 注入Agent运行控制服务，用于调用对应业务能力。 */
    private final AgentRunControlService agentRunControlService;

    /** 记录消息网关重启Coordinator中的退出Handler。 */
    private final RestartExitHandler exitHandler;

    /** 记录消息网关重启Coordinator中的运行时主渠道。 */
    private final File runtimeHome;

    /** 是否启用重启Requested。 */
    private volatile boolean restartRequested;

    /** 记录消息网关重启Coordinator中的active运行次数。 */
    private volatile int activeRunCount;

    /** 记录消息网关重启Coordinator中的requested时间。 */
    private volatile long requestedAt;

    /** 记录消息网关重启Coordinator中的requester来源键。 */
    private volatile String requesterSourceKey;

    /** 记录消息网关重启Coordinator中的requesterRouting。 */
    private volatile RequesterRouting requesterRouting = RequesterRouting.empty();

    /** 记录消息网关重启Coordinator中的drainTimeoutSeconds。 */
    private volatile int drainTimeoutSeconds;

    /** 创建消息网关重启Coordinator实例。 */
    public GatewayRestartCoordinator() {
        this(null, null, new SystemExitRestartHandler());
    }

    /**
     * 创建消息网关重启Coordinator实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param agentRunControlService Agent运行控制服务依赖。
     */
    public GatewayRestartCoordinator(
            AppConfig appConfig, AgentRunControlService agentRunControlService) {
        this(appConfig, agentRunControlService, new SystemExitRestartHandler());
    }

    /**
     * 创建消息网关重启Coordinator实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param agentRunControlService Agent运行控制服务依赖。
     * @param exitHandler 退出Handler参数。
     */
    public GatewayRestartCoordinator(
            AppConfig appConfig,
            AgentRunControlService agentRunControlService,
            RestartExitHandler exitHandler) {
        this.agentRunControlService = agentRunControlService;
        this.exitHandler = exitHandler == null ? new SystemExitRestartHandler() : exitHandler;
        this.runtimeHome =
                FileUtil.file(
                                StrUtil.blankToDefault(
                                        appConfig == null ? null : appConfig.getRuntime().getHome(),
                                        RuntimePathConstants.RUNTIME_HOME))
                        .getAbsoluteFile();
        this.drainTimeoutSeconds =
                appConfig == null
                        ? 180
                        : Math.max(0, appConfig.getTask().getRestartDrainTimeoutSeconds());
        this.executorService =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            Thread thread = new Thread(runnable, "jimuqu-gateway-restart-drain");
                            thread.setDaemon(true);
                            return thread;
                        });
    }

    /**
     * 执行请求重启Drain相关逻辑。
     *
     * @param sourceKey 渠道来源键。
     * @param activeRuns activeRuns 参数。
     * @return 返回请求重启Drain结果。
     */
    public RestartRequest requestRestartDrain(String sourceKey, int activeRuns) {
        return requestRestartDrain(sourceKey, null, activeRuns);
    }

    /**
     * 执行请求重启Drain相关逻辑。
     *
     * @param requester requester请求载荷。
     * @param activeRuns activeRuns 参数。
     * @return 返回请求重启Drain结果。
     */
    public RestartRequest requestRestartDrain(GatewayMessage requester, int activeRuns) {
        return requestRestartDrain(
                requester == null ? null : requester.sourceKey(), requester, activeRuns);
    }

    /**
     * 执行请求重启Drain相关逻辑。
     *
     * @param sourceKey 渠道来源键。
     * @param requester requester请求载荷。
     * @param activeRuns activeRuns 参数。
     * @return 返回请求重启Drain结果。
     */
    private RestartRequest requestRestartDrain(
            String sourceKey, GatewayMessage requester, int activeRuns) {
        int count = Math.max(0, activeRuns);
        boolean first = draining.compareAndSet(false, true);
        restartRequested = true;
        activeRunCount = count;
        requestedAt = System.currentTimeMillis();
        requesterSourceKey = StrUtil.nullToEmpty(sourceKey);
        requesterRouting = RequesterRouting.from(requesterSourceKey, requester);
        persistRequesterMarker(count, requestedAt, requesterRouting);
        if (first) {
            scheduleDrainExit(count);
        }
        return new RestartRequest(
                first,
                count,
                requestedAt,
                requesterSourceKey,
                requesterRouting,
                drainTimeoutSeconds);
    }

    /**
     * 执行调度Drain退出相关逻辑。
     *
     * @param initialActiveRuns initialActiveRuns 参数。
     */
    private void scheduleDrainExit(int initialActiveRuns) {
        if (initialActiveRuns <= 0 || drainTimeoutSeconds <= 0) {
            scheduleExit(3000L);
            return;
        }
        long timeoutMs = Math.max(0L, drainTimeoutSeconds) * 1000L;
        long deadline = System.currentTimeMillis() + timeoutMs;
        executorService.execute(
                () -> {
                    try {
                        while (System.currentTimeMillis() < deadline) {
                            int count = currentRunningRunCount();
                            activeRunCount = count;
                            if (count <= 0) {
                                sleepQuietly(3000L);
                                exitHandler.restartAfterDrain(false);
                                return;
                            }
                            sleepQuietly(200L);
                        }
                        activeRunCount = currentRunningRunCount();
                        if (activeRunCount > 0 && agentRunControlService != null) {
                            agentRunControlService.stopAllRunningRuns("restart_timeout");
                            sleepQuietly(3000L);
                        }
                        exitHandler.restartAfterDrain(activeRunCount > 0);
                    } finally {
                        clear();
                    }
                });
    }

    /**
     * 执行调度退出相关逻辑。
     *
     * @param delayMs delayMs 参数。
     */
    private void scheduleExit(long delayMs) {
        executorService.schedule(
                () -> {
                    try {
                        exitHandler.restartAfterDrain(false);
                    } finally {
                        clear();
                    }
                },
                Math.max(0L, delayMs),
                TimeUnit.MILLISECONDS);
    }

    /**
     * 执行当前Running运行次数相关逻辑。
     *
     * @return 返回当前Running运行次数结果。
     */
    private int currentRunningRunCount() {
        return agentRunControlService == null
                ? activeRunCount
                : agentRunControlService.runningRunCount();
    }

    /**
     * 执行persistRequesterMarker相关逻辑。
     *
     * @param activeRuns activeRuns 参数。
     * @param requestedAtMillis requestedAtMillis请求载荷。
     * @param routing routing 参数。
     */
    private void persistRequesterMarker(
            int activeRuns, long requestedAtMillis, RequesterRouting routing) {
        if (routing == null || routing.isEmpty()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        if (routing.getPlatform() != null) {
            payload.put("platform", routing.getPlatform().name());
        }
        putIfNotBlank(payload, "chat_id", routing.getChatId());
        putIfNotBlank(payload, "user_id", routing.getUserId());
        putIfNotBlank(payload, "chat_type", routing.getChatType());
        putIfNotBlank(payload, "thread_id", routing.getThreadId());
        putIfNotBlank(payload, "source_key", routing.getSourceKey());
        payload.put("active_run_count", Integer.valueOf(Math.max(0, activeRuns)));
        payload.put("requested_at", Long.valueOf(requestedAtMillis));
        payload.put("drain_timeout_seconds", Integer.valueOf(drainTimeoutSeconds));

        File marker = FileUtil.file(runtimeHome, RESTART_REQUESTER_MARKER);
        File temp = FileUtil.file(runtimeHome, RESTART_REQUESTER_MARKER + ".tmp");
        FileUtil.mkParentDirs(marker);
        FileUtil.writeString(ONode.serialize(payload), temp, StandardCharsets.UTF_8);
        if (!temp.renameTo(marker)) {
            FileUtil.copy(temp, marker, true);
            FileUtil.del(temp);
        }
    }

    /**
     * 写入If Not Blank。
     *
     * @param payload 待签名或解析的载荷内容。
     * @param key 配置键或映射键。
     * @param value 待规范化或校验的原始值。
     */
    private static void putIfNotBlank(Map<String, Object> payload, String key, String value) {
        if (StrUtil.isNotBlank(value)) {
            payload.put(key, value);
        }
    }

    /**
     * 执行sleepQuietly相关逻辑。
     *
     * @param millis millis 参数。
     */
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 判断是否Draining。
     *
     * @return 如果Draining满足条件则返回 true，否则返回 false。
     */
    public boolean isDraining() {
        return draining.get();
    }

    /**
     * 判断是否重启Requested。
     *
     * @return 如果重启Requested满足条件则返回 true，否则返回 false。
     */
    public boolean isRestartRequested() {
        return restartRequested;
    }

    /**
     * 读取Active运行次数。
     *
     * @return 返回读取到的Active运行次数。
     */
    public int getActiveRunCount() {
        return activeRunCount;
    }

    /**
     * 读取Requested时间。
     *
     * @return 返回读取到的Requested时间。
     */
    public long getRequestedAt() {
        return requestedAt;
    }

    /**
     * 读取Requester来源键。
     *
     * @return 返回读取到的Requester来源键。
     */
    public String getRequesterSourceKey() {
        return requesterSourceKey;
    }

    /**
     * 读取Requester Routing。
     *
     * @return 返回读取到的Requester Routing。
     */
    public RequesterRouting getRequesterRouting() {
        return requesterRouting;
    }

    /** 执行clear相关逻辑。 */
    public void clear() {
        draining.set(false);
        restartRequested = false;
        activeRunCount = 0;
        requestedAt = 0L;
        requesterSourceKey = "";
        requesterRouting = RequesterRouting.empty();
    }

    /** 关闭当前组件持有的运行资源。 */
    public void shutdown() {
        executorService.shutdownNow();
    }

    /** 定义重启Exit Handler的抽象契约，供不同运行时实现保持一致行为。 */
    public interface RestartExitHandler {
        /**
         * 重启After Drain。
         *
         * @param timedOut timedOut 参数。
         */
        void restartAfterDrain(boolean timedOut);
    }

    /** 承载系统退出重启Handler相关状态和辅助逻辑。 */
    private static class SystemExitRestartHandler implements RestartExitHandler {
        /**
         * 重启After Drain。
         *
         * @param timedOut timedOut 参数。
         */
        @Override
        public void restartAfterDrain(boolean timedOut) {
            System.exit(75);
        }
    }

    /** 承载重启请求相关状态和辅助逻辑。 */
    public static class RestartRequest {
        /** 是否启用first请求。 */
        private final boolean firstRequest;

        /** 记录重启请求中的active运行次数。 */
        private final int activeRunCount;

        /** 记录重启请求中的requested时间。 */
        private final long requestedAt;

        /** 记录重启请求中的requester来源键。 */
        private final String requesterSourceKey;

        /** 记录重启请求中的requesterRouting。 */
        private final RequesterRouting requesterRouting;

        /** 记录重启请求中的drainTimeoutSeconds。 */
        private final int drainTimeoutSeconds;

        /**
         * 创建重启请求实例，并注入运行所需依赖。
         *
         * @param firstRequest first请求请求载荷。
         * @param activeRunCount active运行Count参数。
         * @param requestedAt requestedAt请求载荷。
         * @param requesterSourceKey requester来源键标识或键值。
         * @param requesterRouting requesterRouting请求载荷。
         * @param drainTimeoutSeconds drainTimeoutSeconds 参数。
         */
        private RestartRequest(
                boolean firstRequest,
                int activeRunCount,
                long requestedAt,
                String requesterSourceKey,
                RequesterRouting requesterRouting,
                int drainTimeoutSeconds) {
            this.firstRequest = firstRequest;
            this.activeRunCount = activeRunCount;
            this.requestedAt = requestedAt;
            this.requesterSourceKey = requesterSourceKey;
            this.requesterRouting =
                    requesterRouting == null ? RequesterRouting.empty() : requesterRouting;
            this.drainTimeoutSeconds = drainTimeoutSeconds;
        }

        /**
         * 判断是否First请求。
         *
         * @return 如果First请求满足条件则返回 true，否则返回 false。
         */
        public boolean isFirstRequest() {
            return firstRequest;
        }

        /**
         * 读取Active运行次数。
         *
         * @return 返回读取到的Active运行次数。
         */
        public int getActiveRunCount() {
            return activeRunCount;
        }

        /**
         * 读取Requested时间。
         *
         * @return 返回读取到的Requested时间。
         */
        public long getRequestedAt() {
            return requestedAt;
        }

        /**
         * 读取Requester来源键。
         *
         * @return 返回读取到的Requester来源键。
         */
        public String getRequesterSourceKey() {
            return requesterSourceKey;
        }

        /**
         * 读取Requester Routing。
         *
         * @return 返回读取到的Requester Routing。
         */
        public RequesterRouting getRequesterRouting() {
            return requesterRouting;
        }

        /**
         * 读取Drain Timeout Seconds。
         *
         * @return 返回读取到的Drain Timeout Seconds。
         */
        public int getDrainTimeoutSeconds() {
            return drainTimeoutSeconds;
        }
    }

    /** 承载RequesterRouting相关状态和辅助逻辑。 */
    public static class RequesterRouting {
        /** 记录RequesterRouting中的平台。 */
        private final PlatformType platform;

        /** 记录RequesterRouting中的聊天标识。 */
        private final String chatId;

        /** 记录RequesterRouting中的用户标识。 */
        private final String userId;

        /** 记录RequesterRouting中的聊天类型。 */
        private final String chatType;

        /** 记录RequesterRouting中的thread标识。 */
        private final String threadId;

        /** 记录RequesterRouting中的来源键。 */
        private final String sourceKey;

        /**
         * 创建Requester Routing实例，并注入运行所需依赖。
         *
         * @param platform 平台参数。
         * @param chatId 聊天标识。
         * @param userId 用户标识。
         * @param chatType 聊天类型参数。
         * @param threadId thread标识。
         * @param sourceKey 渠道来源键。
         */
        private RequesterRouting(
                PlatformType platform,
                String chatId,
                String userId,
                String chatType,
                String threadId,
                String sourceKey) {
            this.platform = platform;
            this.chatId = StrUtil.nullToEmpty(chatId);
            this.userId = StrUtil.nullToEmpty(userId);
            this.chatType = StrUtil.nullToEmpty(chatType);
            this.threadId = StrUtil.nullToEmpty(threadId);
            this.sourceKey = StrUtil.nullToEmpty(sourceKey);
        }

        /**
         * 返回当前类型的空结果。
         *
         * @return 返回empty结果。
         */
        private static RequesterRouting empty() {
            return new RequesterRouting(null, "", "", "", "", "");
        }

        /**
         * 执行from相关逻辑。
         *
         * @param sourceKey 渠道来源键。
         * @param requester requester请求载荷。
         * @return 返回from结果。
         */
        private static RequesterRouting from(String sourceKey, GatewayMessage requester) {
            if (requester == null) {
                return new RequesterRouting(null, "", "", "", "", sourceKey);
            }
            return new RequesterRouting(
                    requester.getPlatform(),
                    requester.getChatId(),
                    requester.getUserId(),
                    requester.getChatType(),
                    requester.getThreadId(),
                    sourceKey);
        }

        /**
         * 读取平台。
         *
         * @return 返回读取到的平台。
         */
        public PlatformType getPlatform() {
            return platform;
        }

        /**
         * 读取Chat标识。
         *
         * @return 返回读取到的Chat标识。
         */
        public String getChatId() {
            return chatId;
        }

        /**
         * 读取用户标识。
         *
         * @return 返回读取到的用户标识。
         */
        public String getUserId() {
            return userId;
        }

        /**
         * 读取Chat类型。
         *
         * @return 返回读取到的Chat类型。
         */
        public String getChatType() {
            return chatType;
        }

        /**
         * 读取Thread标识。
         *
         * @return 返回读取到的Thread标识。
         */
        public String getThreadId() {
            return threadId;
        }

        /**
         * 读取来源键。
         *
         * @return 返回读取到的来源键。
         */
        public String getSourceKey() {
            return sourceKey;
        }

        /**
         * 判断是否Empty。
         *
         * @return 如果Empty满足条件则返回 true，否则返回 false。
         */
        private boolean isEmpty() {
            return platform == null
                    && StrUtil.isBlank(chatId)
                    && StrUtil.isBlank(userId)
                    && StrUtil.isBlank(chatType)
                    && StrUtil.isBlank(threadId)
                    && StrUtil.isBlank(sourceKey);
        }
    }
}
