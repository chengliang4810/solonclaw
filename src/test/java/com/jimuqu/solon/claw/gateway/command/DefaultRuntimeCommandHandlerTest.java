package com.jimuqu.solon.claw.gateway.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.repository.GlobalSettingRepository;
import com.jimuqu.solon.claw.proactive.ProactiveReminderState;
import com.jimuqu.solon.claw.proactive.ProactiveReminderStateStore;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 验证主动提醒运行时命令与持久化状态保持一致。 */
class DefaultRuntimeCommandHandlerTest {
    /** `/proactive why` 应解释最近结果、原因、分析理由和额度。 */
    @Test
    void shouldExplainLastProactiveOutcome() throws Exception {
        AppConfig config = new AppConfig();
        MemorySettings settings = new MemorySettings();
        ProactiveReminderState state = new ProactiveReminderState();
        state.setLastTickAt(1700000000000L);
        state.setLastOutcome(ProactiveReminderState.OUTCOME_ACTIVITY_CREDIT_LOW);
        state.setLastReason("当前累计额度不足。");
        state.setActivityLevel(0.2D);
        state.setActivityCredit(0.4D);
        state.setAnalysisReason("用户近期较忙。");
        state.setUnansweredCount(2);
        new ProactiveReminderStateStore(settings).save(state);

        GatewayReply reply =
                new DefaultRuntimeCommandHandler(config, settings)
                        .handleProactive("why", "FEISHU:chat:user");

        assertThat(reply.isError()).isFalse();
        assertThat(reply.getContent())
                .contains("ACTIVITY_CREDIT_LOW", "当前累计额度不足", "用户近期较忙", "累计额度：0.4", "连续未回应：2");
        assertThat(reply.getRuntimeMetadata()).containsEntry("action", "why");
    }

    /** 恢复命令应同时更新内存配置和持久化覆盖值。 */
    @Test
    void shouldResumeProactiveConfigurationWithoutRestart() throws Exception {
        AppConfig config = new AppConfig();
        config.getProactive().setEnabled(false);
        MemorySettings settings = new MemorySettings();

        GatewayReply reply =
                new DefaultRuntimeCommandHandler(config, settings)
                        .handleProactive("resume", "FEISHU:chat:user");

        assertThat(reply.isError()).isFalse();
        assertThat(config.getProactive().isEnabled()).isTrue();
        assertThat(settings.get("proactive.enabled")).isEqualTo("true");
        assertThat(reply.getContent()).contains("无需重启");
    }

    /** 供命令测试使用的内存设置仓储。 */
    private static class MemorySettings implements GlobalSettingRepository {
        /** 保存设置键值。 */
        private final Map<String, String> values = new HashMap<String, String>();

        /** 读取设置值。 */
        @Override
        public String get(String key) {
            return values.get(key);
        }

        /** 写入设置值。 */
        @Override
        public void set(String key, String value) {
            values.put(key, value);
        }

        /** 删除设置值。 */
        @Override
        public void remove(String key) {
            values.remove(key);
        }
    }
}
