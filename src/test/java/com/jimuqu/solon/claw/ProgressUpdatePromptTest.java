package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.ChannelStatus;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.engine.DefaultConversationOrchestrator;
import com.jimuqu.solon.claw.gateway.feedback.GatewayConversationFeedbackSink;
import com.jimuqu.solon.claw.support.DisplaySettingsService;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
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
                .contains("第一人称表达")
                .contains("我先确认当前版本和 GitHub 仓库信息")
                .contains("不要添加‘进度’等展示标签")
                .contains("不要添加步骤序号或‘第 N 步’")
                .contains("简单任务不要说明")
                .contains("最多 3 条")
                .contains("至少间隔 5 秒")
                .contains("不要输出思维链")
                .contains("最终回复只总结结果");
    }

    /** 阶段说明提示词只注入用户直接对话，不进入 Cron、Heartbeat 或 Profile 后台运行。 */
    @Test
    void shouldOnlyAppendProgressPolicyForUserConversation() throws Exception {
        Method method =
                DefaultConversationOrchestrator.class.getDeclaredMethod(
                        "appendProgressUpdateSystemNote", String.class, GatewayMessage.class);
        method.setAccessible(true);
        Method classifier =
                DefaultConversationOrchestrator.class.getDeclaredMethod(
                        "isUserConversationProgress", GatewayMessage.class);
        classifier.setAccessible(true);

        GatewayMessage conversation =
                new GatewayMessage(PlatformType.WEIXIN, "chat", "user", "hello");
        GatewayMessage cron = new GatewayMessage(PlatformType.WEIXIN, "chat", "user", "cron");
        cron.setRunKind("cron");
        GatewayMessage heartbeat =
                new GatewayMessage(PlatformType.WEIXIN, "chat", "user", "heartbeat");
        heartbeat.setHeartbeat(true);
        GatewayMessage profileTask =
                new GatewayMessage(PlatformType.MEMORY, "profile", "task", "work");
        profileTask.setSourceKeyOverride("PROFILE_TASK:task-1");

        assertThat((String) method.invoke(null, "base", conversation)).contains("[任务执行中的阶段说明]");
        assertThat((String) method.invoke(null, "base", cron)).isEqualTo("base");
        assertThat((String) method.invoke(null, "base", heartbeat)).isEqualTo("base");
        assertThat((String) method.invoke(null, "base", profileTask)).isEqualTo("base");
        assertThat((Boolean) classifier.invoke(null, conversation)).isTrue();
        assertThat((Boolean) classifier.invoke(null, cron)).isFalse();
        assertThat((Boolean) classifier.invoke(null, heartbeat)).isFalse();
        assertThat((Boolean) classifier.invoke(null, profileTask)).isFalse();
    }

    /** 消息渠道必须直接发送自然语言阶段说明，不得再拼接机械的进度标签。 */
    @Test
    void shouldDeliverNaturalProgressTextWithoutLabel() {
        AtomicReference<DeliveryRequest> delivered = new AtomicReference<DeliveryRequest>();
        DeliveryService deliveryService =
                new DeliveryService() {
                    /** 记录阶段说明投递请求。 */
                    @Override
                    public void deliver(DeliveryRequest request) {
                        delivered.set(request);
                    }

                    /** 当前测试不需要渠道状态。 */
                    @Override
                    public List<ChannelStatus> statuses() {
                        return Collections.emptyList();
                    }
                };
        GatewayMessage message =
                new GatewayMessage(PlatformType.WEIXIN, "progress-chat", "progress-user", "hello");
        GatewayConversationFeedbackSink sink =
                new GatewayConversationFeedbackSink(
                        message,
                        deliveryService,
                        new DisplaySettingsService(new AppConfig(), null));

        sink.onProgressUpdate("我先确认当前版本和 GitHub 仓库信息。");

        assertThat(delivered.get()).isNotNull();
        assertThat(delivered.get().getText()).isEqualTo("我先确认当前版本和 GitHub 仓库信息。");
        assertThat(delivered.get().getText()).doesNotContain("【进度】");
        assertThat(delivered.get().isRecordInConversation()).isFalse();
    }
}
