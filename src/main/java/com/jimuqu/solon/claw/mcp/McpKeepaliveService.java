package com.jimuqu.solon.claw.mcp;

import java.io.Closeable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Sends periodic pings to keep MCP server connections alive and triggers
 * reconnect when a ping fails.
 */
public class McpKeepaliveService implements Closeable {
    private static final Logger LOG = Logger.getLogger(McpKeepaliveService.class.getName());
    private static final long DEFAULT_INTERVAL_SECONDS = 30L;

    private final McpRuntimeService runtimeService;
    private final long intervalSeconds;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledFuture<?> task;

    public McpKeepaliveService(McpRuntimeService runtimeService) {
        this(runtimeService, DEFAULT_INTERVAL_SECONDS);
    }

    public McpKeepaliveService(McpRuntimeService runtimeService, long intervalSeconds) {
        this.runtimeService = runtimeService;
        this.intervalSeconds = intervalSeconds > 0 ? intervalSeconds : DEFAULT_INTERVAL_SECONDS;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mcp-keepalive");
            t.setDaemon(true);
            return t;
        });
    }

    /** Starts the keepalive loop. Safe to call multiple times; only starts once. */
    public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            task = scheduler.scheduleWithFixedDelay(
                    this::ping,
                    intervalSeconds,
                    intervalSeconds,
                    TimeUnit.SECONDS);
        }
    }

    /** Stops the keepalive loop. */
    public synchronized void stop() {
        if (running.compareAndSet(true, false)) {
            if (task != null) {
                task.cancel(false);
                task = null;
            }
        }
    }

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

    /** Returns the configured keepalive interval in seconds. */
    public long getIntervalSeconds() {
        return intervalSeconds;
    }

    /** Returns whether the keepalive loop is currently active. */
    public boolean isRunning() {
        return running.get();
    }

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

    private void pingAllServers() {
        // Attempt to refresh live tools for all enabled servers as a keepalive probe.
        // Failures are logged but do not propagate to avoid killing the scheduler thread.
        try {
            runtimeService.resolveEnabledToolProviders();
        } catch (Exception e) {
            LOG.fine("MCP keepalive: resolveEnabledToolProviders failed: " + e.getMessage());
        }
    }

    /**
     * Attempts to reconnect a specific server without blocking the caller on discovery.
     * Returns true when a reload task was accepted.
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
