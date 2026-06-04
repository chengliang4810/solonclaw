package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.service.AgentRunControlService;
import com.jimuqu.solon.claw.gateway.service.GatewayRestartCoordinator;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.snack4.ONode;

public class GatewayRestartCoordinatorTest {
    @TempDir java.nio.file.Path tempDir;

    @Test
    void shouldMarkRestartTimeoutReasonWhenDrainForcesRunningRunsToStop() throws Exception {
        AppConfig config = new AppConfig();
        config.getTask().setRestartDrainTimeoutSeconds(1);
        RecordingRunControlService runControlService = new RecordingRunControlService();
        RecordingRestartExitHandler exitHandler = new RecordingRestartExitHandler();
        GatewayRestartCoordinator coordinator =
                new GatewayRestartCoordinator(config, runControlService, exitHandler);

        try {
            GatewayRestartCoordinator.RestartRequest request =
                    coordinator.requestRestartDrain("MEMORY:room:user", 1);

            assertThat(request.isFirstRequest()).isTrue();
            assertThat(exitHandler.finished.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(runControlService.stopAllCount).isEqualTo(1);
            assertThat(runControlService.resumeReason).isEqualTo("restart_timeout");
            assertThat(exitHandler.timedOut).isTrue();
        } finally {
            coordinator.shutdown();
        }
    }

    @Test
    void shouldPersistRestartRequesterRoutingMarker() throws Exception {
        AppConfig config = new AppConfig();
        File runtimeHome = Files.createDirectory(tempDir.resolve("runtime")).toFile();
        config.getRuntime().setHome(runtimeHome.getAbsolutePath());
        RecordingRestartExitHandler exitHandler = new RecordingRestartExitHandler();
        GatewayRestartCoordinator coordinator =
                new GatewayRestartCoordinator(config, null, exitHandler);
        GatewayMessage requester =
                new GatewayMessage(PlatformType.MEMORY, "admin-chat", "admin-user", "/restart");
        requester.setChatType("group");
        requester.setThreadId("topic-7");

        try {
            coordinator.requestRestartDrain(requester, 2);

            File marker = new File(runtimeHome, "restart-requester.json");
            assertThat(marker).isFile();
            Map<?, ?> data =
                    ONode.deserialize(
                            new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8),
                            Map.class);
            assertThat(data.get("platform")).isEqualTo("MEMORY");
            assertThat(data.get("chat_id")).isEqualTo("admin-chat");
            assertThat(data.get("user_id")).isEqualTo("admin-user");
            assertThat(data.get("chat_type")).isEqualTo("group");
            assertThat(data.get("thread_id")).isEqualTo("topic-7");
            assertThat(data.get("source_key")).isEqualTo("MEMORY:admin-chat:topic-7:admin-user");
            assertThat(data.get("active_run_count")).isEqualTo(2);
            assertThat(data.get("drain_timeout_seconds")).isEqualTo(180);
            assertThat((Number) data.get("requested_at"))
                    .satisfies(value -> assertThat(value.longValue()).isPositive());
        } finally {
            coordinator.shutdown();
        }
    }

    private static class RecordingRunControlService implements AgentRunControlService {
        private int stopAllCount;
        private String resumeReason;

        @Override
        public com.jimuqu.solon.claw.core.model.AgentRunStopResult stop(String sourceKey) {
            return com.jimuqu.solon.claw.core.model.AgentRunStopResult.none();
        }

        @Override
        public boolean isRunning(String sourceKey) {
            return true;
        }

        @Override
        public int runningRunCount() {
            return 1;
        }

        @Override
        public int stopAllRunningRuns(String resumeReason) {
            stopAllCount++;
            this.resumeReason = resumeReason;
            return 1;
        }
    }

    private static class RecordingRestartExitHandler
            implements GatewayRestartCoordinator.RestartExitHandler {
        private final CountDownLatch finished = new CountDownLatch(1);
        private volatile boolean timedOut;

        @Override
        public void restartAfterDrain(boolean timedOut) {
            this.timedOut = timedOut;
            finished.countDown();
        }
    }
}
