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
    /** MCP 保活任务的日志记录器。 */
    private static final Logger LOG = Logger.getLogger(McpKeepaliveService.class.getName());

    /** 未显式配置时的 MCP 保活间隔，避免过于频繁地触发工具发现。 */
    private static final long DEFAULT_INTERVAL_SECONDS = 30L;

    /** MCP 运行时服务，负责解析启用的服务端并刷新工具提供方。 */
    private final McpRuntimeService runtimeService;

    /** 每轮保活之间的固定延迟秒数。 */
    private final long intervalSeconds;

    /** 单线程调度器，保证同一时间只有一个保活任务访问 MCP 运行时。 */
    private final ScheduledExecutorService scheduler;

    /** 当前保活调度是否处于运行状态。 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 已提交的周期性保活任务句柄，用于停止时取消。 */
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
     * @param intervalSeconds 保活间隔秒数，小于等于 0 时回退到默认值。
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
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 读取当前保活间隔。
     *
     * @return 实际生效的保活间隔秒数。
     */
    public long getIntervalSeconds() {
        return intervalSeconds;
    }

    /**
     * 判断保活调度是否已经启动。
     *
     * @return 已启动且尚未停止时返回 true。
     */
    public boolean isRunning() {
        return running.get();
    }

    /** 执行单轮保活探测，并隔离异常避免调度线程退出。 */
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

    /** 触发启用 MCP 服务端的工具提供方解析，用作轻量保活。 */
    private void pingAllServers() {
        // 保活任务只记录失败，不向外抛出异常，避免调度线程被单次 MCP 故障终止。
        try {
            runtimeService.resolveEnabledToolProviders();
        } catch (Exception e) {
            LOG.fine("MCP keepalive: resolveEnabledToolProviders failed: " + e.getMessage());
        }
    }

    /**
     * 请求异步重连指定 MCP 服务端。
     *
     * @param serverId MCP 服务端标识。
     * @return 成功提交重连任务时返回 true，提交失败时返回 false。
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
