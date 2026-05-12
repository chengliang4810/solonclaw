package com.jimuqu.solon.claw.scheduler;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.kanban.KanbanNotificationResult;
import com.jimuqu.solon.claw.kanban.KanbanNotificationService;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Background delivery loop for subscribed Kanban task events. */
@RequiredArgsConstructor
public class KanbanNotificationScheduler {
    private static final Logger log = LoggerFactory.getLogger(KanbanNotificationScheduler.class);

    private final AppConfig appConfig;
    private final KanbanNotificationService notificationService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService executorService;

    public void start() {
        if (!appConfig.getScheduler().isEnabled()) {
            return;
        }
        long tickSeconds = Math.max(5L, appConfig.getScheduler().getTickSeconds());
        executorService = Executors.newSingleThreadScheduledExecutor();
        executorService.scheduleWithFixedDelay(
                new Runnable() {
                    @Override
                    public void run() {
                        tick();
                    }
                },
                5L,
                tickSeconds,
                TimeUnit.SECONDS);
    }

    public void tick() {
        if (!appConfig.getScheduler().isEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            KanbanNotificationResult result = notificationService.deliverPending();
            if (result.getClaimedEvents() > 0
                    || result.getDeliveredEvents() > 0
                    || result.getFailedEvents() > 0
                    || result.getRemovedSubscriptions() > 0) {
                log.info(
                        "Kanban notifications delivered: subscriptions={}, claimed={}, delivered={}, failed={}, removed={}",
                        result.getSubscriptions(),
                        result.getClaimedEvents(),
                        result.getDeliveredEvents(),
                        result.getFailedEvents(),
                        result.getRemovedSubscriptions());
            }
        } catch (Exception e) {
            log.warn("Kanban notification tick failed: error={}", safeError(e));
        } finally {
            running.set(false);
        }
    }

    public void shutdown() {
        if (executorService != null) {
            executorService.shutdownNow();
            executorService = null;
        }
    }

    private String safeError(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        String message = error.getMessage();
        String value = StrUtil.isBlank(message) ? error.getClass().getSimpleName() : message;
        return SecretRedactor.redact(value, 1000);
    }
}
