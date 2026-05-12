package com.jimuqu.solon.claw.scheduler;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.kanban.KanbanNotificationResult;
import com.jimuqu.solon.claw.kanban.KanbanNotificationService;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.util.LinkedHashMap;
import java.util.Map;
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
    private volatile long startedAt;
    private volatile long lastTickAt;
    private volatile long lastSuccessAt;
    private volatile long lastFailureAt;
    private volatile String lastError;
    private volatile KanbanNotificationResult lastResult;

    public void start() {
        if (!appConfig.getScheduler().isEnabled()) {
            return;
        }
        long tickSeconds = Math.max(5L, appConfig.getScheduler().getTickSeconds());
        startedAt = System.currentTimeMillis();
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
            lastTickAt = System.currentTimeMillis();
            KanbanNotificationResult result = notificationService.deliverPending();
            lastResult = result;
            lastSuccessAt = System.currentTimeMillis();
            lastError = null;
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
            lastFailureAt = System.currentTimeMillis();
            lastError = safeError(e);
            log.warn("Kanban notification tick failed: error={}", lastError);
        } finally {
            running.set(false);
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("enabled", Boolean.valueOf(appConfig.getScheduler().isEnabled()));
        result.put("running", Boolean.valueOf(running.get()));
        result.put("started_at", Long.valueOf(startedAt));
        result.put("tick_seconds", Long.valueOf(Math.max(5L, appConfig.getScheduler().getTickSeconds())));
        result.put("last_tick_at", Long.valueOf(lastTickAt));
        result.put("last_success_at", Long.valueOf(lastSuccessAt));
        result.put("last_failure_at", Long.valueOf(lastFailureAt));
        result.put("last_error", lastError);
        result.put("last_result", resultView(lastResult));
        return result;
    }

    public void shutdown() {
        if (executorService != null) {
            executorService.shutdownNow();
            executorService = null;
        }
    }

    private Map<String, Object> resultView(KanbanNotificationResult delivery) {
        if (delivery == null) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("subscriptions", Integer.valueOf(delivery.getSubscriptions()));
        result.put("claimed_events", Integer.valueOf(delivery.getClaimedEvents()));
        result.put("delivered_events", Integer.valueOf(delivery.getDeliveredEvents()));
        result.put("failed_events", Integer.valueOf(delivery.getFailedEvents()));
        result.put("removed_subscriptions", Integer.valueOf(delivery.getRemovedSubscriptions()));
        result.put("errors", delivery.getErrors());
        return result;
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
