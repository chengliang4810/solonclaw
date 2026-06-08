package com.jimuqu.solon.claw.mcp;

import java.io.Closeable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/** 提供MCP保活相关业务能力，封装调用方不需要感知的运行细节。 */
public class McpKeepaliveService implements Closeable {
    /** 日志的统一常量值。 */
    private static final Logger LOG = Logger.getLogger(McpKeepaliveService.class.getName());

    /** 默认整型ERVALSECONDS的统一常量值。 */
    private static final long DEFAULT_INTERVAL_SECONDS = 30L;

    /** 注入运行时服务，用于调用对应业务能力。 */
    private final McpRuntimeService runtimeService;

    /** 记录MCP保活中的intervalSeconds。 */
    private final long intervalSeconds;

    /** 保存调度器执行组件，负责调度异步或定时任务。 */
    private final ScheduledExecutorService scheduler;

    /** 记录MCP保活中的running。 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 记录MCP保活中的任务。 */
    private ScheduledFuture<?> task;

    /**
     * 创建MCP保活服务实例，并注入运行所需依赖。
     *
     * @param runtimeService 运行时服务依赖。
     */
    public McpKeepaliveService(McpRuntimeService runtimeService) {
        this(runtimeService, DEFAULT_INTERVAL_SECONDS);
    }

    /**
     * 创建MCP保活服务实例，并注入运行所需依赖。
     *
     * @param runtimeService 运行时服务依赖。
     * @param intervalSeconds intervalSeconds 参数。
     */
    public McpKeepaliveService(McpRuntimeService runtimeService, long intervalSeconds) {
        this.runtimeService = runtimeService;
        this.intervalSeconds = intervalSeconds > 0 ? intervalSeconds : DEFAULT_INTERVAL_SECONDS;
        this.scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "mcp-keepalive");
                            t.setDaemon(true);
                            return t;
                        });
    }

    /** 启动当前组件并准备运行资源。 */
    public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            task =
                    scheduler.scheduleWithFixedDelay(
                            this::ping, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        }
    }

    /** 停止当前组件并释放运行状态。 */
    public synchronized void stop() {
        if (running.compareAndSet(true, false)) {
            if (task != null) {
                task.cancel(false);
                task = null;
            }
        }
    }

    /** 关闭当前组件持有的运行资源。 */
    @Override
    public void close() {
        stop();
        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 读取Interval Seconds。
     *
     * @return 返回读取到的Interval Seconds。
     */
    public long getIntervalSeconds() {
        return intervalSeconds;
    }

    /**
     * 判断是否Running。
     *
     * @return 如果Running满足条件则返回 true，否则返回 false。
     */
    public boolean isRunning() {
        return running.get();
    }

    /** 执行ping相关逻辑。 */
    private void ping() {
        if (!running.get()) {
            return;
        }
        try {
            pingAllServers();
        } catch (Exception e) {
            LOG.warning("MCP keepalive ping cycle failed: " + e.getMessage());
        }
    }

    /** 执行ping全部服务端相关逻辑。 */
    private void pingAllServers() {
        // 保活任务只记录失败，不向外抛出异常，避免调度线程被单次 MCP 故障终止。
        try {
            runtimeService.resolveEnabledToolProviders();
        } catch (Exception e) {
            LOG.fine("MCP keepalive: resolveEnabledToolProviders failed: " + e.getMessage());
        }
    }

    /**
     * 执行reconnect相关逻辑。
     *
     * @param serverId MCP 服务端标识。
     * @return 返回reconnect结果。
     */
    public boolean reconnect(String serverId) {
        try {
            runtimeService.reloadAsync(serverId);
            LOG.info("MCP keepalive: queued reconnect for server " + serverId);
            return true;
        } catch (Exception e) {
            LOG.warning(
                    "MCP keepalive: reconnect queue failed for server "
                            + serverId
                            + ": "
                            + e.getMessage());
            return false;
        }
    }
}
