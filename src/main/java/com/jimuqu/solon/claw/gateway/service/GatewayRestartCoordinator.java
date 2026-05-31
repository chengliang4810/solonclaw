package com.jimuqu.solon.claw.gateway.service;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
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
    private volatile RequesterRouting requesterRouting = RequesterRouting.empty();
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
        return requestRestartDrain(sourceKey, null, activeRuns);
    }

    public RestartRequest requestRestartDrain(GatewayMessage requester, int activeRuns) {
        return requestRestartDrain(requester == null ? null : requester.sourceKey(), requester, activeRuns);
    }

    private RestartRequest requestRestartDrain(
            String sourceKey, GatewayMessage requester, int activeRuns) {
        int count = Math.max(0, activeRuns);
        boolean first = draining.compareAndSet(false, true);
        restartRequested = true;
        activeRunCount = count;
        requestedAt = System.currentTimeMillis();
        requesterSourceKey = StrUtil.nullToEmpty(sourceKey);
        requesterRouting = RequesterRouting.from(requesterSourceKey, requester);
        if (first) {
            scheduleDrainExit(count);
        }
        return new RestartRequest(
                first, count, requestedAt, requesterSourceKey, requesterRouting, drainTimeoutSeconds);
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
                            agentRunControlService.stopAllRunningRuns("restart_timeout");
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

    public RequesterRouting getRequesterRouting() {
        return requesterRouting;
    }

    public void clear() {
        draining.set(false);
        restartRequested = false;
        activeRunCount = 0;
        requestedAt = 0L;
        requesterSourceKey = "";
        requesterRouting = RequesterRouting.empty();
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
        private final RequesterRouting requesterRouting;
        private final int drainTimeoutSeconds;

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

        public RequesterRouting getRequesterRouting() {
            return requesterRouting;
        }

        public int getDrainTimeoutSeconds() {
            return drainTimeoutSeconds;
        }
    }

    public static class RequesterRouting {
        private final PlatformType platform;
        private final String chatId;
        private final String userId;
        private final String chatType;
        private final String threadId;
        private final String sourceKey;

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

        private static RequesterRouting empty() {
            return new RequesterRouting(null, "", "", "", "", "");
        }

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

        public PlatformType getPlatform() {
            return platform;
        }

        public String getChatId() {
            return chatId;
        }

        public String getUserId() {
            return userId;
        }

        public String getChatType() {
            return chatType;
        }

        public String getThreadId() {
            return threadId;
        }

        public String getSourceKey() {
            return sourceKey;
        }
    }
}
