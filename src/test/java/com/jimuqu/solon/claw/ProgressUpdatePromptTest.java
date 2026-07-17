package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.engine.DefaultConversationOrchestrator;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/** 校验阶段说明规则通过统一编排入口注入。 */
public class ProgressUpdatePromptTest {
    /** 统一规则必须覆盖多步任务、频率、安全和最终答复边界。 */
    @Test
    void shouldAppendCompleteProgressUpdatePolicy() throws Exception {
        Method method =
                DefaultConversationOrchestrator.class.getDeclaredMethod(
                        "appendProgressUpdateSystemNote", String.class);
        method.setAccessible(true);

        String prompt = (String) method.invoke(null, "base");

        assertThat(prompt)
                .startsWith("base")
                .contains("只有需要调用工具的多步骤任务")
                .contains("以【阶段说明】开头")
                .contains("普通工具前文本不会发送")
                .contains("简单任务不要说明")
                .contains("最多 3 条")
                .contains("至少间隔 5 秒")
                .contains("不要输出思维链")
                .contains("最终回复只总结结果");
    }
}
