package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.scheduler.CronJobService;
import com.jimuqu.solon.claw.support.TestEnvironment;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** CronJobService 关键共享常量测试，避免运行时安全边界被外部代码改写。 */
public class CronJobServiceTests {
    /** Cron 模型绑定只接受独立的 provider 与 model 字符串字段。 */
    @Test
    void shouldRejectNestedCronModelObject() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        CronJobService service = new CronJobService(env.appConfig, env.cronJobRepository);
        Map<String, Object> nestedModel = new LinkedHashMap<String, Object>();
        nestedModel.put("providerKey", "default");
        nestedModel.put("name", "gpt-5.2");
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("schedule", "30m");
        body.put("prompt", "check status");
        body.put("model", nestedModel);

        assertThatThrownBy(() -> service.create("MEMORY:test:user", body))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cron model 必须是字符串");
    }

    /** Cron 模型绑定不接受序列化对象字符串。 */
    @Test
    void shouldRejectSerializedCronModelObject() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        CronJobService service = new CronJobService(env.appConfig, env.cronJobRepository);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("schedule", "30m");
        body.put("prompt", "check status");
        body.put("model", "{\"provider\":\"default\",\"model\":\"gpt-5.2\"}");

        assertThatThrownBy(() -> service.create("MEMORY:test:user", body))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cron model 必须是字符串");
    }

    /** 受保护的定时任务禁用工具集必须禁止外部改写元素，防止调度安全边界漂移。 */
    @Test
    void shouldRejectProtectedDisabledToolsetMutation() {
        assertThatThrownBy(() -> CronJobService.PROTECTED_CRON_DISABLED_TOOLSETS.set(0, "terminal"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
