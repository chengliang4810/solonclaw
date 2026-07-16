package com.jimuqu.solon.claw.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.AgentRunStopResult;
import com.jimuqu.solon.claw.core.service.AgentRunControlService;
import java.nio.file.Path;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.core.AppContext;
import org.noear.solon.core.Props;

/** 验证 Profile 运行时将停止操作路由到自己的 Agent 运行控制器。 */
class ProfileRuntimeBundleTest {
    /** 测试运行时工作区。 */
    @TempDir Path home;

    /** 停止操作必须使用子容器控制器和原始来源键。 */
    @Test
    void shouldStopRunThroughProfileContext() {
        AppContext context =
                new AppContext(null, Thread.currentThread().getContextClassLoader(), new Props());
        RecordingRunControlService controlService = new RecordingRunControlService();
        context.wrapAndPut(AgentRunControlService.class, controlService);
        ProfileRuntimeBundle bundle =
                new ProfileRuntimeBundle(
                        "worker", home, Collections.emptyMap(), new AppConfig(), context, null);

        AgentRunStopResult result = bundle.stopRun("PROFILE_TASK:task-1");

        assertThat(result.isActiveRun()).isTrue();
        assertThat(controlService.sourceKey).isEqualTo("profile:worker:PROFILE_TASK:task-1");
    }

    /** 记录收到的停止来源键。 */
    private static final class RecordingRunControlService implements AgentRunControlService {
        /** 最近一次停止来源键。 */
        private String sourceKey;

        /** 记录停止调用并返回活动运行结果。 */
        @Override
        public AgentRunStopResult stop(String sourceKey) {
            this.sourceKey = sourceKey;
            return "profile:worker:PROFILE_TASK:task-1".equals(sourceKey)
                    ? AgentRunStopResult.stopped("run-1", "session-1", true, 1L)
                    : AgentRunStopResult.none();
        }

        /** 测试控制器没有其他运行。 */
        @Override
        public boolean isRunning(String sourceKey) {
            return false;
        }
    }
}
