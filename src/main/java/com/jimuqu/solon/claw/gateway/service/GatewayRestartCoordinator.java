package com.jimuqu.solon.claw.gateway.service;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.service.AgentRunControlService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Coordinates gateway restart drain state inside the Java gateway. */
public class GatewayRestartCoordinator {
    private final AtomicBoolean draining = new AtomicBoolean(false);
    private final ScheduledExecutorService executorService;
    private final AgentRunControlService agentRunControlService;
    private final RestartExitHandler exitHandler;
    private volatile boolean restartRequested;
    private volatile int activeRunCount;
    private volatile long requestedAt;
    private volatile String requesterSourceKey;
    private volatile int drainTimeoutSeconds;

    public GatewayRestartCoordinator() {
        this(null, null, new SystemExitRestartHandler());
    }

    public GatewayRestartCoordinator(
            AppConfig appConfig, AgentRunControlService agentRunControlService) {
        this(appConfig, agentRunControlService, new SystemExitRestartHandler());
    }

    public GatewayRestartCoordinator(
            AppConfig appConfig,
            AgentRunControlService agentRunControlService,
            RestartExitHandler exitHandler) {
        this.agentRunControlService = agentRunControlService;
        this.exitHandler = exitHandler == null ? new SystemExitRestartHandler() : exitHandler;
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

    public RestartRequest requestRestartDrain(String sourceKey, int activeRuns) {
        int count = Math.max(0, activeRuns);
        boolean first = draining.compareAndSet(false, true);
        restartRequested = true;
        activeRunCount = count;
        requestedAt = System.currentTimeMillis();
        requesterSourceKey = StrUtil.nullToEmpty(sourceKey);
        if (first) {
            scheduleDrainExit(count);
        }
        return new RestartRequest(first, count, requestedAt, requesterSourceKey, drainTimeoutSeconds);
    }

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
                            agentRunControlService.stopAllRunningRuns();
                            sleepQuietly(3000L);
                        }
                        exitHandler.restartAfterDrain(activeRunCount > 0);
                    } finally {
                        clear();
                    }
                });
    }

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

    private int currentRunningRunCount() {
        return agentRunControlService == null ? activeRunCount : agentRunControlService.runningRunCount();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isDraining() {
        return draining.get();
    }

    public boolean isRestartRequested() {
        return restartRequested;
    }

    public int getActiveRunCount() {
        return activeRunCount;
    }

    public long getRequestedAt() {
        return requestedAt;
    }

    public String getRequesterSourceKey() {
        return requesterSourceKey;
    }

    public void clear() {
        draining.set(false);
        restartRequested = false;
        activeRunCount = 0;
        requestedAt = 0L;
        requesterSourceKey = "";
    }

    public void shutdown() {
        executorService.shutdownNow();
    }

    public interface RestartExitHandler {
        void restartAfterDrain(boolean timedOut);
    }

    private static class SystemExitRestartHandler implements RestartExitHandler {
        @Override
        public void restartAfterDrain(boolean timedOut) {
            System.exit(75);
        }
    }

    public static class RestartRequest {
        private final boolean firstRequest;
        private final int activeRunCount;
        private final long requestedAt;
        private final String requesterSourceKey;
        private final int drainTimeoutSeconds;

        private RestartRequest(
                boolean firstRequest,
                int activeRunCount,
                long requestedAt,
                String requesterSourceKey,
                int drainTimeoutSeconds) {
            this.firstRequest = firstRequest;
            this.activeRunCount = activeRunCount;
            this.requestedAt = requestedAt;
            this.requesterSourceKey = requesterSourceKey;
            this.drainTimeoutSeconds = drainTimeoutSeconds;
        }

        public boolean isFirstRequest() {
            return firstRequest;
        }

        public int getActiveRunCount() {
            return activeRunCount;
        }

        public long getRequestedAt() {
            return requestedAt;
        }

        public String getRequesterSourceKey() {
            return requesterSourceKey;
        }

        public int getDrainTimeoutSeconds() {
            return drainTimeoutSeconds;
        }
    }
}
