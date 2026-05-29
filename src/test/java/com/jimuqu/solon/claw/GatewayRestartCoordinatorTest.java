package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.service.AgentRunControlService;
import com.jimuqu.solon.claw.gateway.service.GatewayRestartCoordinator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

public class GatewayRestartCoordinatorTest {
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
