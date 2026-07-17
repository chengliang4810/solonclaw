package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.context.FileMemoryService;
import com.jimuqu.solon.claw.context.MemoryArchiveService;
import com.jimuqu.solon.claw.context.MemoryArchiveState;
import com.jimuqu.solon.claw.core.model.AgentRunStopResult;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.GlobalSettingRepository;
import com.jimuqu.solon.claw.core.service.AgentRunControlService;
import com.jimuqu.solon.claw.core.service.LlmGateway;
import com.jimuqu.solon.claw.scheduler.MemoryArchiveScheduler;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 记忆归档调度器空闲门控测试。 */
class MemoryArchiveSchedulerTest {
    /** 临时工作区。 */
    @TempDir Path tempDir;

    /** Agent 忙碌时不得推进归档状态。 */
    @Test
    void shouldSkipWhileAgentIsBusy() throws Exception {
        TestRuntime runtime = runtime(true);

        runtime.scheduler.tick();

        assertThat(runtime.service.state().getLastOutcome())
                .isEqualTo(MemoryArchiveState.OUTCOME_NEVER_RUN);
        runtime.close();
    }

    /** Agent 空闲时应执行归档检查并记录无工作结果。 */
    @Test
    void shouldRunWhileAgentIsIdle() throws Exception {
        TestRuntime runtime = runtime(false);

        runtime.scheduler.tick();

        assertThat(runtime.service.state().getLastOutcome())
                .isEqualTo(MemoryArchiveState.OUTCOME_NO_WORK);
        runtime.close();
    }

    /** 创建调度器测试运行时。 */
    private TestRuntime runtime(boolean busy) {
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(tempDir.toString());
        config.getRuntime().setStateDb(tempDir.resolve("data/state.db").toString());
        MemorySettings settings = new MemorySettings();
        MemoryArchiveService service =
                new MemoryArchiveService(
                        config, new FileMemoryService(config), new NoopGateway(), settings);
        MemoryArchiveScheduler scheduler =
                new MemoryArchiveScheduler(config, service, new FixedRunControl(busy));
        return new TestRuntime(service, scheduler);
    }

    /** 调度器与服务组合夹具。 */
    private static final class TestRuntime {
        /** 记忆归档服务。 */
        private final MemoryArchiveService service;

        /** 记忆归档调度器。 */
        private final MemoryArchiveScheduler scheduler;

        /** 创建组合夹具。 */
        private TestRuntime(MemoryArchiveService service, MemoryArchiveScheduler scheduler) {
            this.service = service;
            this.scheduler = scheduler;
        }

        /** 关闭测试资源。 */
        private void close() {
            scheduler.shutdown();
            service.shutdown();
        }
    }

    /** 固定 Agent 忙碌状态。 */
    private static final class FixedRunControl implements AgentRunControlService {
        /** 是否忙碌。 */
        private final boolean busy;

        /** 创建固定状态运行控制服务。 */
        private FixedRunControl(boolean busy) {
            this.busy = busy;
        }

        /** 测试不停止运行。 */
        @Override
        public AgentRunStopResult stop(String sourceKey) {
            return AgentRunStopResult.none();
        }

        /** 返回固定单来源状态。 */
        @Override
        public boolean isRunning(String sourceKey) {
            return busy;
        }

        /** 返回固定全局状态。 */
        @Override
        public boolean hasRunningRuns() {
            return busy;
        }
    }

    /** 不允许意外模型调用的网关。 */
    private static final class NoopGateway implements LlmGateway {
        /** 拒绝聊天调用。 */
        @Override
        public LlmResult chat(
                SessionRecord session,
                String systemPrompt,
                String userMessage,
                List<Object> toolObjects) {
            throw new AssertionError("无归档文件时不应调用模型");
        }

        /** 拒绝恢复调用。 */
        @Override
        public LlmResult resume(
                SessionRecord session, String systemPrompt, List<Object> toolObjects) {
            throw new AssertionError("记忆归档不应恢复模型会话");
        }
    }

    /** 内存全局设置仓储。 */
    private static final class MemorySettings implements GlobalSettingRepository {
        /** 设置值。 */
        private final Map<String, String> values = new LinkedHashMap<String, String>();

        /** 读取设置。 */
        @Override
        public String get(String key) {
            return values.get(key);
        }

        /** 保存设置。 */
        @Override
        public void set(String key, String value) {
            values.put(key, value);
        }

        /** 删除设置。 */
        @Override
        public void remove(String key) {
            values.remove(key);
        }
    }
}
