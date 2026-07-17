package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.context.CrossSessionReflectionService;
import com.jimuqu.solon.claw.context.ReflectionState;
import com.jimuqu.solon.claw.core.model.AgentRunStopResult;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.GlobalSettingRepository;
import com.jimuqu.solon.claw.core.service.AgentRunControlService;
import com.jimuqu.solon.claw.core.service.LlmGateway;
import com.jimuqu.solon.claw.scheduler.ReflectionScheduler;
import com.jimuqu.solon.claw.support.FixedSessionRepository;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 跨会话反思调度器测试。 */
class ReflectionSchedulerTest {
    /** 临时工作区。 */
    @TempDir Path tempDir;

    /** Agent 忙碌时不得读取会话或推进反思状态。 */
    @Test
    void shouldSkipWhileAgentIsBusy() throws Exception {
        TestRuntime runtime = runtime(true);

        runtime.scheduler.tick();

        assertThat(runtime.service.state().getLastOutcome())
                .isEqualTo(ReflectionState.OUTCOME_NEVER_RUN);
        runtime.close();
    }

    /** Agent 空闲时应执行反思服务并持久化无证据结果。 */
    @Test
    void shouldRunWhileAgentIsIdle() throws Exception {
        TestRuntime runtime = runtime(false);

        runtime.scheduler.tick();

        assertThat(runtime.service.state().getLastOutcome())
                .isEqualTo(ReflectionState.OUTCOME_NO_EVIDENCE);
        runtime.close();
    }

    /** 创建调度器测试运行时。 */
    private TestRuntime runtime(boolean busy) {
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(tempDir.toString());
        MemorySettings settings = new MemorySettings();
        CrossSessionReflectionService service =
                new CrossSessionReflectionService(
                        config, new FixedSessionRepository(), new NoopGateway(), settings);
        ReflectionScheduler scheduler =
                new ReflectionScheduler(config, service, new FixedRunControl(busy));
        return new TestRuntime(service, scheduler);
    }

    /** 调度器与服务的组合测试夹具。 */
    private static final class TestRuntime {
        /** 反思服务。 */
        private final CrossSessionReflectionService service;

        /** 反思调度器。 */
        private final ReflectionScheduler scheduler;

        /** 创建测试夹具。 */
        private TestRuntime(CrossSessionReflectionService service, ReflectionScheduler scheduler) {
            this.service = service;
            this.scheduler = scheduler;
        }

        /** 关闭测试资源。 */
        private void close() {
            scheduler.shutdown();
            service.shutdown();
        }
    }

    /** 固定忙碌状态的运行控制服务。 */
    private static final class FixedRunControl implements AgentRunControlService {
        /** 是否存在运行中的 Agent。 */
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

        /** 返回固定单来源运行状态。 */
        @Override
        public boolean isRunning(String sourceKey) {
            return busy;
        }

        /** 返回固定全局运行状态。 */
        @Override
        public boolean hasRunningRuns() {
            return busy;
        }
    }

    /** 不应在无证据测试中被调用的模型网关。 */
    private static final class NoopGateway implements LlmGateway {
        /** 拒绝意外聊天调用。 */
        @Override
        public LlmResult chat(
                SessionRecord session,
                String systemPrompt,
                String userMessage,
                List<Object> toolObjects) {
            throw new AssertionError("无证据时不应调用模型");
        }

        /** 拒绝意外恢复调用。 */
        @Override
        public LlmResult resume(
                SessionRecord session, String systemPrompt, List<Object> toolObjects) {
            throw new AssertionError("反思不应恢复会话");
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
