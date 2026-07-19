package com.jimuqu.solon.claw.gateway.service;

import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.ChannelStatus;
import com.jimuqu.solon.claw.core.service.ChannelAdapter;
import com.jimuqu.solon.claw.core.service.InboundMessageHandler;
import com.jimuqu.solon.claw.profile.ProfileRuntimeScope;
import com.jimuqu.solon.claw.support.ErrorTextSupport;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 管理渠道连接生命周期、单任务重连、连接 watchdog 与失败熔断。 */
public class ChannelConnectionManager {
    /** 渠道连接日志器。 */
    private static final Logger log = LoggerFactory.getLogger(ChannelConnectionManager.class);

    /** 重连退避秒数，保持 5、15、30、60 秒的既有策略。 */
    private static final long[] BACKOFF_SECONDS = new long[] {5L, 15L, 30L, 60L};

    /** 连续失败达到该次数后暂停普通退避，进入有限冷却。 */
    private static final int CIRCUIT_FAILURE_THRESHOLD = 5;

    /** 重连熔断冷却时间，单位秒。 */
    private static final long CIRCUIT_COOLDOWN_SECONDS = 300L;

    /** 连接 watchdog 检查间隔，单位秒。 */
    private static final long WATCHDOG_INTERVAL_SECONDS = 30L;

    /** SDK 自恢复优先窗口，超时后由统一 watchdog 接管，单位秒。 */
    private static final long SDK_RECOVERY_GRACE_SECONDS = 60L;

    /** 平台到渠道适配器的映射。 */
    private final Map<PlatformType, ChannelAdapter> adapters;

    /** 每个平台的重连诊断状态。 */
    private final Map<PlatformType, ReconnectState> reconnectStates =
            new EnumMap<PlatformType, ReconnectState>(PlatformType.class);

    /** 每个平台当前唯一的待执行重连任务。 */
    private final Map<PlatformType, ScheduledFuture<?>> reconnectFutures =
            new EnumMap<PlatformType, ScheduledFuture<?>>(PlatformType.class);

    /** 串行化同一平台的状态、Future 和连接尝试切换。 */
    private final Object reconnectLock = new Object();

    /** 每个平台独立的完整 disconnect/connect 生命周期门。 */
    private final Map<PlatformType, Object> transitionLocks =
            new EnumMap<PlatformType, Object>(PlatformType.class);

    /** 已完成真实握手或健康不可观测但启动成功的平台集合。 */
    private final Set<PlatformType> readyPlatforms = EnumSet.noneOf(PlatformType.class);

    /** SDK 自恢复优先窗口截止时间，超时后不再抑制统一 watchdog。 */
    private final Map<PlatformType, Long> sdkRecoveryDeadlines =
            new EnumMap<PlatformType, Long>(PlatformType.class);

    /** 执行延迟重连和周期 watchdog 的调度器。 */
    private final ScheduledExecutorService reconnectExecutor;

    /** 构造管理器时捕获的 Profile 作用域。 */
    private final ProfileRuntimeScope.Context runtimeScope;

    /** 管理器关闭后拒绝新的重连与 watchdog 恢复。 */
    private volatile boolean closed;

    /** 渠道连接成功后的异步回调，用于补投该平台运行期失败的持久化回复。 */
    private volatile Consumer<PlatformType> connectedHandler;

    /**
     * 创建渠道连接管理器。
     *
     * @param adapters 平台到渠道适配器的映射。
     */
    public ChannelConnectionManager(Map<PlatformType, ChannelAdapter> adapters) {
        this.adapters = adapters;
        this.runtimeScope = ProfileRuntimeScope.current();
        this.reconnectExecutor =
                Executors.newScheduledThreadPool(
                        2,
                        new ThreadFactory() {
                            /** 渠道连接线程序号。 */
                            private final AtomicInteger sequence = new AtomicInteger(1);

                            /** 创建守护调度线程。 */
                            @Override
                            public Thread newThread(Runnable runnable) {
                                Thread thread =
                                        new Thread(
                                                runnable,
                                                "channel-reconnect-" + sequence.getAndIncrement());
                                thread.setDaemon(true);
                                return thread;
                            }
                        });
        for (final ChannelAdapter adapter : adapters.values()) {
            transitionLocks.put(adapter.platform(), new Object());
            adapter.setReconnectHandler(
                    new Runnable() {
                        /** 将适配器运行期断线交回统一退避调度器。 */
                        @Override
                        public void run() {
                            markConnectionUnavailable(adapter.platform());
                            scheduleReconnect(adapter.platform());
                        }
                    });
            adapter.setConnectionReadyHandler(
                    new Runnable() {
                        /** 在异步握手或 SDK 自恢复成功后完成统一 ready 交接。 */
                        @Override
                        public void run() {
                            markConnectionReady(adapter.platform());
                        }
                    });
            adapter.setConnectionUnavailableHandler(
                    new Runnable() {
                        /** SDK 自恢复开始时撤销旧 ready 状态，但不启动第二套重连。 */
                        @Override
                        public void run() {
                            markSdkConnectionUnavailable(adapter.platform());
                        }
                    });
        }
        reconnectExecutor.scheduleWithFixedDelay(
                ProfileRuntimeScope.capture(
                        runtimeScope,
                        new Runnable() {
                            /** 定期补回启用但断线且没有重连任务的渠道。 */
                            @Override
                            public void run() {
                                watchdogReconnects();
                            }
                        }),
                WATCHDOG_INTERVAL_SECONDS,
                WATCHDOG_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
    }

    /**
     * 读取全部渠道适配器。
     *
     * @return 返回平台到适配器的映射。
     */
    public Map<PlatformType, ChannelAdapter> adapters() {
        return adapters;
    }

    /**
     * 为全部渠道绑定统一入站处理器。
     *
     * @param handler 入站处理器。
     */
    public void bindInboundHandler(final InboundMessageHandler handler) {
        for (ChannelAdapter adapter : adapters.values()) {
            adapter.setInboundMessageHandler(handler);
        }
    }

    /**
     * 绑定渠道连接成功后的统一异步处理器。
     *
     * @param handler 接收已连接平台的处理器；传 null 表示取消绑定。
     */
    public void bindConnectedHandler(Consumer<PlatformType> handler) {
        this.connectedHandler = handler;
    }

    /** 启动全部已配置渠道。 */
    public void startAll() {
        for (ChannelAdapter adapter : adapters.values()) {
            connectIsolated(adapter, false);
        }
    }

    /** 手工刷新全部渠道，并清除旧重连熔断状态。 */
    public void refreshAll() {
        for (ChannelAdapter adapter : adapters.values()) {
            synchronized (transitionLock(adapter.platform())) {
                markConnectionUnavailable(adapter.platform());
                clearReconnectState(adapter.platform());
                disconnectIsolatedUnlocked(adapter);
                connectIsolatedUnlocked(adapter, false);
            }
        }
    }

    /** 断开全部渠道。 */
    public void disconnectAll() {
        for (ChannelAdapter adapter : adapters.values()) {
            synchronized (transitionLock(adapter.platform())) {
                markConnectionUnavailable(adapter.platform());
                disconnectIsolatedUnlocked(adapter);
            }
        }
    }

    /**
     * 读取全部渠道状态快照，并叠加统一重连诊断。
     *
     * @return 返回渠道状态列表。
     */
    public List<ChannelStatus> statusSnapshots() {
        List<ChannelStatus> statuses = new ArrayList<ChannelStatus>();
        for (ChannelAdapter adapter : adapters.values()) {
            ChannelStatus status = adapter.statusSnapshot();
            applyReconnectState(status);
            statuses.add(status);
        }
        return statuses;
    }

    /**
     * 为指定平台请求一次自动重连；已有任务时合并请求。
     *
     * @param platform 渠道平台。
     */
    public void scheduleReconnect(final PlatformType platform) {
        if (closed || reconnectExecutor.isShutdown()) {
            return;
        }
        ChannelAdapter adapter = adapters.get(platform);
        if (adapter != null) {
            scheduleReconnect(adapter, null, false, false);
        }
    }

    /** 关闭调度器、取消待执行重连并断开全部渠道。 */
    public void shutdown() {
        closed = true;
        synchronized (reconnectLock) {
            for (ScheduledFuture<?> future : reconnectFutures.values()) {
                if (future != null) {
                    future.cancel(false);
                }
            }
            reconnectFutures.clear();
        }
        reconnectExecutor.shutdownNow();
        for (ChannelAdapter adapter : adapters.values()) {
            markConnectionUnavailable(adapter.platform());
            disconnectIsolatedUnlocked(adapter);
        }
    }

    /** 执行单个渠道连接并把失败交给统一调度器。 */
    private void connectIsolated(ChannelAdapter adapter, boolean scheduledAttempt) {
        synchronized (transitionLock(adapter.platform())) {
            connectIsolatedUnlocked(adapter, scheduledAttempt);
        }
    }

    /** 在当前平台生命周期门内执行连接并把失败交给统一调度器。 */
    private void connectIsolatedUnlocked(ChannelAdapter adapter, boolean scheduledAttempt) {
        if (closed) {
            disconnectIsolatedUnlocked(adapter);
            return;
        }
        try {
            boolean started = adapter.connect();
            if (closed) {
                disconnectIsolatedUnlocked(adapter);
                return;
            }
            log.info(
                    "[CHANNEL] platform={}, enabled={}, connected={}, detail={}",
                    adapter.platform(),
                    adapter.isEnabled(),
                    adapter.isConnected(),
                    adapter.detail());
            if (adapter.isEnabled() && !started) {
                markConnectionUnavailable(adapter.platform());
                disconnectIsolatedUnlocked(adapter);
                scheduleReconnect(adapter, null, scheduledAttempt, false);
            } else if (started && adapter.isConnected()) {
                markConnectionReady(adapter.platform());
            }
        } catch (Exception e) {
            log.warn(
                    "[CHANNEL] platform={} connect failed, application will continue: {}",
                    adapter.platform(),
                    ErrorTextSupport.safeError(e));
            markConnectionUnavailable(adapter.platform());
            disconnectIsolatedUnlocked(adapter);
            if (!closed) {
                scheduleReconnect(adapter, e, scheduledAttempt, false);
            }
        }
    }

    /** 在连接线程之外通知平台恢复成功，避免回复补投阻塞后续渠道连接。 */
    private void notifyConnected(final PlatformType platform) {
        final Consumer<PlatformType> handler = connectedHandler;
        if (handler == null || closed || reconnectExecutor.isShutdown()) {
            return;
        }
        try {
            reconnectExecutor.execute(
                    ProfileRuntimeScope.capture(
                            runtimeScope,
                            new Runnable() {
                                /** 执行当前平台的连接后恢复回调。 */
                                @Override
                                public void run() {
                                    try {
                                        handler.accept(platform);
                                    } catch (Exception e) {
                                        log.warn(
                                                "[CHANNEL] platform={} connected callback failed: {}",
                                                platform,
                                                ErrorTextSupport.safeError(e));
                                    }
                                }
                            }));
        } catch (RejectedExecutionException e) {
            if (!closed) {
                log.debug(
                        "[CHANNEL] platform={} connected callback rejected: {}",
                        platform,
                        ErrorTextSupport.safeError(e));
            }
        }
    }

    /** 隔离单个渠道断开异常。 */
    private void disconnectIsolated(ChannelAdapter adapter) {
        synchronized (transitionLock(adapter.platform())) {
            markConnectionUnavailable(adapter.platform());
            disconnectIsolatedUnlocked(adapter);
        }
    }

    /** 在已经持有平台生命周期门或 shutdown 迟到清理时隔离断开异常。 */
    private void disconnectIsolatedUnlocked(ChannelAdapter adapter) {
        try {
            adapter.disconnect();
        } catch (Exception e) {
            log.debug(
                    "[CHANNEL] platform={} disconnect failed: {}",
                    adapter.platform(),
                    ErrorTextSupport.safeError(e));
        }
    }

    /**
     * 调度当前平台唯一的重连任务。
     *
     * @param adapter 渠道适配器。
     * @param error 最近一次连接异常。
     * @param scheduledAttempt 是否由正在执行的重连任务继续调度。
     * @param watchdogRecovery 是否由 watchdog 补回丢失任务。
     */
    private void scheduleReconnect(
            final ChannelAdapter adapter,
            final Throwable error,
            boolean scheduledAttempt,
            boolean watchdogRecovery) {
        if (closed || reconnectExecutor.isShutdown() || !adapter.isEnabled()) {
            return;
        }
        synchronized (reconnectLock) {
            ScheduledFuture<?> existing = reconnectFutures.get(adapter.platform());
            if (existing != null && !existing.isDone() && !existing.isCancelled()) {
                updateReconnectError(adapter.platform(), error);
                return;
            }
            ReconnectState state = reconnectStates.get(adapter.platform());
            if (state == null) {
                state = new ReconnectState();
                reconnectStates.put(adapter.platform(), state);
            }
            if (state.connecting && !scheduledAttempt) {
                updateReconnectError(adapter.platform(), error);
                return;
            }
            long now = System.currentTimeMillis();
            state.failureCount++;
            state.attempt = state.failureCount;
            state.lastReconnectAt = now;
            String safeError = ErrorTextSupport.safeError(error);
            if (safeError != null && safeError.length() > 0) {
                state.lastError = safeError;
            }
            long delayMillis =
                    TimeUnit.SECONDS.toMillis(
                            BACKOFF_SECONDS[
                                    Math.min(state.failureCount - 1, BACKOFF_SECONDS.length - 1)]);
            if (state.failureCount >= CIRCUIT_FAILURE_THRESHOLD) {
                state.circuitOpenedAt = now;
                state.circuitOpenUntil = now + TimeUnit.SECONDS.toMillis(CIRCUIT_COOLDOWN_SECONDS);
                delayMillis = state.circuitOpenUntil - now;
            }
            if (watchdogRecovery) {
                state.watchdogRecoveredAt = now;
            }
            state.nextReconnectAt = now + Math.max(1L, delayMillis);
            final long generation = ++state.generation;
            try {
                ScheduledFuture<?> future =
                        reconnectExecutor.schedule(
                                ProfileRuntimeScope.capture(
                                        runtimeScope,
                                        new Runnable() {
                                            /** 执行当前代次的重连任务。 */
                                            @Override
                                            public void run() {
                                                executeReconnect(adapter, generation);
                                            }
                                        }),
                                Math.max(1L, delayMillis),
                                TimeUnit.MILLISECONDS);
                reconnectFutures.put(adapter.platform(), future);
            } catch (RejectedExecutionException ignored) {
                // shutdown 与断线回调并发时直接放弃，关闭流程会负责断开适配器。
            }
        }
    }

    /** 执行仍属于当前调度代次的重连任务，过期任务不触碰适配器。 */
    private void executeReconnect(ChannelAdapter adapter, long generation) {
        ReconnectState state;
        synchronized (reconnectLock) {
            state = reconnectStates.get(adapter.platform());
            if (closed || state == null || state.generation != generation) {
                return;
            }
            reconnectFutures.remove(adapter.platform());
            state.connecting = true;
            state.nextReconnectAt = 0L;
        }
        synchronized (transitionLock(adapter.platform())) {
            synchronized (reconnectLock) {
                ReconnectState current = reconnectStates.get(adapter.platform());
                if (closed || current != state || current.generation != generation) {
                    if (current == state) {
                        current.connecting = false;
                    }
                    return;
                }
            }
            try {
                if (!adapter.isEnabled()) {
                    markConnectionUnavailable(adapter.platform());
                    clearReconnectState(adapter.platform());
                } else if (!isOperationallyReady(adapter)) {
                    disconnectIsolatedUnlocked(adapter);
                    connectIsolatedUnlocked(adapter, true);
                } else {
                    markConnectionReady(adapter.platform());
                }
            } finally {
                synchronized (reconnectLock) {
                    ReconnectState current = reconnectStates.get(adapter.platform());
                    if (current == state) {
                        current.connecting = false;
                    }
                }
            }
        }
    }

    /** 定期补回启用但断线且没有有效重连 Future 的渠道。 */
    private void watchdogReconnects() {
        if (closed) {
            return;
        }
        long now = System.currentTimeMillis();
        for (ChannelAdapter adapter : adapters.values()) {
            if (!adapter.isEnabled()) {
                continue;
            }
            if (isSdkRecovering(adapter.platform(), now)) {
                continue;
            }
            if (isOperationallyReady(adapter)) {
                markConnectionReady(adapter.platform());
                continue;
            }
            markConnectionUnavailable(adapter.platform());
            boolean missing;
            synchronized (reconnectLock) {
                ScheduledFuture<?> future = reconnectFutures.get(adapter.platform());
                ReconnectState state = reconnectStates.get(adapter.platform());
                missing =
                        (future == null || future.isDone() || future.isCancelled())
                                && (state == null || !state.connecting);
            }
            if (missing) {
                scheduleReconnect(adapter, null, false, true);
            }
        }
    }

    /** 重复断线通知只刷新脱敏错误，不增加失败计数或重复调度。 */
    private void updateReconnectError(PlatformType platform, Throwable error) {
        ReconnectState state = reconnectStates.get(platform);
        String safeError = ErrorTextSupport.safeError(error);
        if (state != null && safeError != null && safeError.length() > 0) {
            state.lastError = safeError;
        }
    }

    /** 把统一重连状态叠加到适配器状态快照。 */
    private void applyReconnectState(ChannelStatus status) {
        if (status == null || status.getPlatform() == null || status.isConnected()) {
            return;
        }
        ReconnectState state;
        ScheduledFuture<?> future;
        synchronized (reconnectLock) {
            state = reconnectStates.get(status.getPlatform());
            future = reconnectFutures.get(status.getPlatform());
        }
        if (state == null) {
            return;
        }
        long now = System.currentTimeMillis();
        status.setReconnecting(
                state.connecting || (future != null && !future.isDone() && !future.isCancelled()));
        status.setReconnectAttempt(state.attempt);
        status.setLastReconnectAt(state.lastReconnectAt);
        status.setNextReconnectAt(state.nextReconnectAt);
        status.setLastReconnectError(state.lastError);
        status.setReconnectFailureCount(state.failureCount);
        status.setReconnectCircuitOpen(state.circuitOpenUntil > now);
        status.setReconnectCircuitOpenedAt(state.circuitOpenedAt);
        status.setReconnectCircuitOpenUntil(state.circuitOpenUntil);
        status.setReconnectWatchdogRecoveredAt(state.watchdogRecoveredAt);
    }

    /** 清除平台重连状态并取消其待执行任务。 */
    private void clearReconnectState(PlatformType platform) {
        if (platform == null) {
            return;
        }
        synchronized (reconnectLock) {
            ScheduledFuture<?> future = reconnectFutures.remove(platform);
            if (future != null) {
                future.cancel(false);
            }
            reconnectStates.remove(platform);
            sdkRecoveryDeadlines.remove(platform);
        }
    }

    /** 返回指定平台的生命周期门。 */
    private Object transitionLock(PlatformType platform) {
        Object lock = transitionLocks.get(platform);
        return lock == null ? reconnectLock : lock;
    }

    /** 判断渠道是否已完成真实握手，或健康不可观测但启动流程已经成功。 */
    private boolean isOperationallyReady(ChannelAdapter adapter) {
        if (adapter == null) {
            return false;
        }
        if (adapter.isConnected()) {
            return true;
        }
        synchronized (reconnectLock) {
            return !adapter.isConnectionHealthObservable()
                    && readyPlatforms.contains(adapter.platform());
        }
    }

    /** 记录平台进入不可用状态，使下一次真实 ready 能触发一次恢复回调。 */
    private void markConnectionUnavailable(PlatformType platform) {
        if (platform == null) {
            return;
        }
        synchronized (reconnectLock) {
            readyPlatforms.remove(platform);
        }
    }

    /** SDK 自恢复开始时撤销 ready，并仅在有限窗口内让 SDK 独占恢复。 */
    private void markSdkConnectionUnavailable(PlatformType platform) {
        if (platform == null || closed) {
            return;
        }
        synchronized (reconnectLock) {
            readyPlatforms.remove(platform);
            sdkRecoveryDeadlines.put(
                    platform,
                    Long.valueOf(
                            System.currentTimeMillis()
                                    + TimeUnit.SECONDS.toMillis(SDK_RECOVERY_GRACE_SECONDS)));
        }
    }

    /** 判断平台是否仍处于 SDK 自恢复优先窗口。 */
    private boolean isSdkRecovering(PlatformType platform, long now) {
        synchronized (reconnectLock) {
            Long deadline = sdkRecoveryDeadlines.get(platform);
            if (deadline == null) {
                return false;
            }
            if (deadline.longValue() > now) {
                return true;
            }
            sdkRecoveryDeadlines.remove(platform);
            return false;
        }
    }

    /** 原子记录平台 ready、清理旧退避，并对每次状态跃迁只通知一次。 */
    private void markConnectionReady(PlatformType platform) {
        if (platform == null || closed) {
            return;
        }
        ChannelAdapter adapter = adapters.get(platform);
        if (adapter == null || !adapter.isEnabled()) {
            return;
        }
        boolean newlyReady;
        synchronized (reconnectLock) {
            newlyReady = readyPlatforms.add(platform);
            sdkRecoveryDeadlines.remove(platform);
            ScheduledFuture<?> future = reconnectFutures.remove(platform);
            if (future != null) {
                future.cancel(false);
            }
            reconnectStates.remove(platform);
        }
        if (newlyReady) {
            notifyConnected(platform);
        }
    }

    /** 单个平台的重连、watchdog 与熔断状态。 */
    private static final class ReconnectState {
        /** 当前重连尝试次数。 */
        private int attempt;

        /** 当前连续连接失败次数。 */
        private int failureCount;

        /** 最近一次重连调度时间戳。 */
        private long lastReconnectAt;

        /** 下一次重连计划时间戳。 */
        private long nextReconnectAt;

        /** 最近一次脱敏错误。 */
        private String lastError;

        /** 当前连接尝试是否正在执行。 */
        private boolean connecting;

        /** 当前调度代次，过期 Future 不得执行。 */
        private long generation;

        /** 最近一次打开熔断的时间戳。 */
        private long circuitOpenedAt;

        /** 当前熔断冷却截止时间戳。 */
        private long circuitOpenUntil;

        /** 最近一次 watchdog 补回任务的时间戳。 */
        private long watchdogRecoveredAt;
    }
}
