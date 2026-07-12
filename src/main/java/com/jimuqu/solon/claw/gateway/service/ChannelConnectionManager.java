package com.jimuqu.solon.claw.gateway.service;

import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.ChannelStatus;
import com.jimuqu.solon.claw.core.service.ChannelAdapter;
import com.jimuqu.solon.claw.core.service.InboundMessageHandler;
import com.jimuqu.solon.claw.profile.ProfileRuntimeScope;
import com.jimuqu.solon.claw.support.ErrorTextSupport;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 管理渠道Connection生命周期与状态切换。 */
public class ChannelConnectionManager {
    /** 日志的统一常量值。 */
    private static final Logger log = LoggerFactory.getLogger(ChannelConnectionManager.class);

    /** BACKOFFSECONDS的统一常量值。 */
    private static final long[] BACKOFF_SECONDS = new long[] {5L, 15L, 30L, 60L};

    /** 保存adapters映射，便于按键快速查询。 */
    private final Map<PlatformType, ChannelAdapter> adapters;

    /** 保存reconnectStates映射，便于按键快速查询。 */
    private final Map<PlatformType, ReconnectState> reconnectStates =
            Collections.synchronizedMap(
                    new EnumMap<PlatformType, ReconnectState>(PlatformType.class));

    /** 保存reconnect执行器执行组件，负责调度异步或定时任务。 */
    private final ScheduledExecutorService reconnectExecutor;

    /** 构造当前连接管理器时捕获的 Profile 作用域，供延迟重连恢复独立配置和 Bean 容器。 */
    private final ProfileRuntimeScope.Context runtimeScope;

    /**
     * 创建渠道Connection管理器实例，并注入运行所需依赖。
     *
     * @param adapters adapters 参数。
     */
    public ChannelConnectionManager(Map<PlatformType, ChannelAdapter> adapters) {
        this.adapters = adapters;
        this.runtimeScope = ProfileRuntimeScope.current();
        this.reconnectExecutor =
                Executors.newScheduledThreadPool(
                        1,
                        new ThreadFactory() {
                            /** 记录渠道连接中的sequence。 */
                            private final AtomicInteger sequence = new AtomicInteger(1);

                            /**
                             * 创建Thread。
                             *
                             * @param runnable runnable 参数。
                             * @return 返回创建好的Thread。
                             */
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
            adapter.setReconnectHandler(
                    new Runnable() {
                        /** 将适配器运行期断线交回统一退避调度器。 */
                        @Override
                        public void run() {
                            scheduleReconnect(adapter.platform());
                        }
                    });
        }
    }

    /**
     * 执行adapters相关逻辑。
     *
     * @return 返回adapters结果。
     */
    public Map<PlatformType, ChannelAdapter> adapters() {
        return adapters;
    }

    /**
     * 执行bind入站Handler相关逻辑。
     *
     * @param handler handler 参数。
     */
    public void bindInboundHandler(final InboundMessageHandler handler) {
        for (ChannelAdapter adapter : adapters.values()) {
            adapter.setInboundMessageHandler(handler);
        }
    }

    /** 启动全部。 */
    public void startAll() {
        for (ChannelAdapter adapter : adapters.values()) {
            connectIsolated(adapter, 0);
        }
    }

    /** 刷新全部。 */
    public void refreshAll() {
        for (ChannelAdapter adapter : adapters.values()) {
            disconnectIsolated(adapter);
            connectIsolated(adapter, 0);
        }
    }

    /** 断开全部。 */
    public void disconnectAll() {
        for (ChannelAdapter adapter : adapters.values()) {
            disconnectIsolated(adapter);
        }
    }

    /**
     * 执行状态Snapshots相关逻辑。
     *
     * @return 返回状态Snapshots结果。
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
     * 执行调度Reconnect相关逻辑。
     *
     * @param platform 平台参数。
     */
    public void scheduleReconnect(final PlatformType platform) {
        if (reconnectExecutor.isShutdown()) {
            return;
        }
        final ChannelAdapter adapter = adapters.get(platform);
        if (adapter == null) {
            return;
        }
        scheduleReconnect(adapter, 0);
    }

    /** 关闭当前组件持有的运行资源。 */
    public void shutdown() {
        reconnectExecutor.shutdownNow();
        disconnectAll();
    }

    /**
     * 连接Isolated。
     *
     * @param adapter adapter 参数。
     * @param attempt attempt 参数。
     */
    private void connectIsolated(ChannelAdapter adapter, int attempt) {
        try {
            boolean connected = adapter.connect();
            log.info(
                    "[CHANNEL] platform={}, enabled={}, connected={}, detail={}",
                    adapter.platform(),
                    adapter.isEnabled(),
                    connected,
                    adapter.detail());
            if (adapter.isEnabled() && !connected) {
                scheduleReconnect(adapter, attempt, null);
            } else if (connected) {
                clearReconnectState(adapter.platform());
            }
        } catch (Exception e) {
            log.warn(
                    "[CHANNEL] platform={} connect failed, application will continue: {}",
                    adapter.platform(),
                    ErrorTextSupport.safeError(e));
            disconnectIsolated(adapter);
            scheduleReconnect(adapter, attempt, e);
        }
    }

    /**
     * 断开Isolated。
     *
     * @param adapter adapter 参数。
     */
    private void disconnectIsolated(ChannelAdapter adapter) {
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
     * 执行调度Reconnect相关逻辑。
     *
     * @param adapter adapter 参数。
     * @param attempt attempt 参数。
     */
    private void scheduleReconnect(final ChannelAdapter adapter, final int attempt) {
        scheduleReconnect(adapter, attempt, null);
    }

    /**
     * 执行调度Reconnect相关逻辑。
     *
     * @param adapter adapter 参数。
     * @param attempt attempt 参数。
     * @param error 错误参数。
     */
    private void scheduleReconnect(
            final ChannelAdapter adapter, final int attempt, final Throwable error) {
        if (!adapter.isEnabled()) {
            return;
        }
        int index = Math.min(attempt, BACKOFF_SECONDS.length - 1);
        long delaySeconds = BACKOFF_SECONDS[index];
        long now = System.currentTimeMillis();
        ReconnectState state =
                new ReconnectState(
                        attempt + 1,
                        now,
                        now + TimeUnit.SECONDS.toMillis(delaySeconds),
                        ErrorTextSupport.safeError(error));
        reconnectStates.put(adapter.platform(), state);
        reconnectExecutor.schedule(
                ProfileRuntimeScope.capture(
                        runtimeScope,
                        new Runnable() {
                            /** 执行异步任务主体。 */
                            @Override
                            public void run() {
                                if (!adapter.isConnected()) {
                                    disconnectIsolated(adapter);
                                    connectIsolated(adapter, attempt + 1);
                                }
                            }
                        }),
                delaySeconds,
                TimeUnit.SECONDS);
    }

    /**
     * 应用Reconnect状态。
     *
     * @param status 状态参数。
     */
    private void applyReconnectState(ChannelStatus status) {
        if (status == null || status.getPlatform() == null || status.isConnected()) {
            return;
        }
        ReconnectState state = reconnectStates.get(status.getPlatform());
        if (state == null) {
            return;
        }
        status.setReconnecting(true);
        status.setReconnectAttempt(state.attempt);
        status.setLastReconnectAt(state.lastReconnectAt);
        status.setNextReconnectAt(state.nextReconnectAt);
        status.setLastReconnectError(state.lastError);
    }

    /**
     * 清理Reconnect状态。
     *
     * @param platform 平台参数。
     */
    private void clearReconnectState(PlatformType platform) {
        if (platform != null) {
            reconnectStates.remove(platform);
        }
    }

    /** 表示Reconnect数据，在服务、仓储和接口之间传递。 */
    private static class ReconnectState {
        /** 记录Reconnect中的attempt。 */
        private final int attempt;

        /** 记录Reconnect中的最近一次Reconnect时间。 */
        private final long lastReconnectAt;

        /** 记录Reconnect中的nextReconnect时间。 */
        private final long nextReconnectAt;

        /** 记录Reconnect中的最近一次错误。 */
        private final String lastError;

        /**
         * 创建Reconnect状态实例，并注入运行所需依赖。
         *
         * @param attempt attempt 参数。
         * @param lastReconnectAt lastReconnectAt 参数。
         * @param nextReconnectAt nextReconnectAt 参数。
         * @param lastError last错误参数。
         */
        private ReconnectState(
                int attempt, long lastReconnectAt, long nextReconnectAt, String lastError) {
            this.attempt = Math.max(1, attempt);
            this.lastReconnectAt = lastReconnectAt;
            this.nextReconnectAt = nextReconnectAt;
            this.lastError = lastError;
        }
    }
}
