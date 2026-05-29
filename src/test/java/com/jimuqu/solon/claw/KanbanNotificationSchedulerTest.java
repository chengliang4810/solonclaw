package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.kanban.KanbanNotificationResult;
import com.jimuqu.solon.claw.kanban.KanbanNotificationService;
import com.jimuqu.solon.claw.scheduler.KanbanNotificationScheduler;
import org.junit.jupiter.api.Test;

public class KanbanNotificationSchedulerTest {
    @Test
    void shouldSkipWhenSchedulerDisabled() {
        AppConfig config = new AppConfig();
        config.getScheduler().setEnabled(false);
        RecordingNotificationService notificationService = new RecordingNotificationService();

        KanbanNotificationScheduler scheduler =
                new KanbanNotificationScheduler(config, notificationService);
        scheduler.tick();

        assertThat(notificationService.calls).isEqualTo(0);
    }

    @Test
    void shouldDeliverPendingNotificationsOnTick() {
        AppConfig config = enabledConfig();
        RecordingNotificationService notificationService = new RecordingNotificationService();
        notificationService.result.addClaimedEvents(1);
        notificationService.result.addDeliveredEvent();

        KanbanNotificationScheduler scheduler =
                new KanbanNotificationScheduler(config, notificationService);
        scheduler.tick();

        assertThat(notificationService.calls).isEqualTo(1);
        assertThat(scheduler.status().get("last_result").toString()).contains("delivered_events=1");
        assertThat(scheduler.status().get("last_success_at")).isNotEqualTo(Long.valueOf(0L));
    }

    @Test
    void shouldKeepRunningAfterDeliveryFailure() {
        AppConfig config = enabledConfig();
        RecordingNotificationService notificationService = new RecordingNotificationService();
        notificationService.failOnce = true;

        KanbanNotificationScheduler scheduler =
                new KanbanNotificationScheduler(config, notificationService);
        scheduler.tick();
        assertThat(String.valueOf(scheduler.status().get("last_error"))).contains("***");
        assertThat(scheduler.status().get("last_failure_at")).isNotEqualTo(Long.valueOf(0L));

        scheduler.tick();

        assertThat(notificationService.calls).isEqualTo(2);
        assertThat(scheduler.status().get("last_error")).isNull();
    }

    @Test
    void shouldSkipOverlappingTick() {
        AppConfig config = enabledConfig();
        final KanbanNotificationScheduler[] holder = new KanbanNotificationScheduler[1];
        RecordingNotificationService notificationService =
                new RecordingNotificationService() {
                    @Override
                    public KanbanNotificationResult deliverPending() throws Exception {
                        calls++;
                        holder[0].tick();
                        return result;
                    }
                };
        holder[0] = new KanbanNotificationScheduler(config, notificationService);

        holder[0].tick();

        assertThat(notificationService.calls).isEqualTo(1);
    }

    private AppConfig enabledConfig() {
        AppConfig config = new AppConfig();
        config.getScheduler().setEnabled(true);
        config.getScheduler().setTickSeconds(60);
        return config;
    }

    private static class RecordingNotificationService extends KanbanNotificationService {
        protected int calls;
        private boolean failOnce;
        protected final KanbanNotificationResult result = new KanbanNotificationResult();

        RecordingNotificationService() {
            super(null, null);
        }

        @Override
        public KanbanNotificationResult deliverPending() throws Exception {
            calls++;
            if (failOnce) {
                failOnce = false;
                throw new IllegalStateException("delivery token=secret-value failed");
            }
            return result;
        }
    }
}
