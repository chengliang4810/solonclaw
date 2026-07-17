package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.context.PersonaWorkspaceService;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.ChannelStatus;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.model.HomeChannelRecord;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.PlatformAdminRecord;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.proactive.ProactiveReminderScheduler;
import com.jimuqu.solon.claw.support.FakeLlmGateway;
import com.jimuqu.solon.claw.support.TestEnvironment;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.message.ChatMessage;

/** 验证主动提醒关闭时不会读取会话、调用模型或投递消息。 */
class ProactiveReminderSchedulerTest {
    /** 关闭主动提醒后一次 tick 应直接结束。 */
    @Test
    void shouldSkipEverythingWhenDisabled() throws Exception {
        AppConfig config = new AppConfig();
        config.getProactive().setEnabled(false);
        new ProactiveReminderScheduler(config, null, null, null, null, null, null, null).tick();
    }

    /** 主动提醒只能选择与显式 home channel 完全匹配的平台、聊天和线程。 */
    @Test
    void shouldMatchOnlyConfiguredHomeConversation() throws Exception {
        HomeChannelRecord home = new HomeChannelRecord();
        home.setPlatform(PlatformType.FEISHU);
        home.setChatId("chat-main");
        home.setThreadId("thread-main");
        SessionRecord matching = new SessionRecord();
        matching.setSourceKey("FEISHU:chat-main:thread-main:user-1");
        SessionRecord otherChat = new SessionRecord();
        otherChat.setSourceKey("FEISHU:chat-other:thread-main:user-1");

        Method matcher =
                ProactiveReminderScheduler.class.getDeclaredMethod(
                        "matchesHome", SessionRecord.class, HomeChannelRecord.class);
        matcher.setAccessible(true);
        org.assertj.core.api.Assertions.assertThat(matcher.invoke(null, matching, home))
                .isEqualTo(Boolean.TRUE);
        org.assertj.core.api.Assertions.assertThat(matcher.invoke(null, otherChat, home))
                .isEqualTo(Boolean.FALSE);
    }

    /** 主动提醒在内部摘要末尾输出静默标记时，不应投递任何消息。 */
    @Test
    void shouldSkipDeliveryWhenLastNonBlankLineIsSilentMarker() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getProactive().setQuietHoursEnabled(false);
        env.sessionRepository.bindNewSession("FEISHU:chat-main:user-1");
        PlatformAdminRecord admin = new PlatformAdminRecord();
        admin.setPlatform(PlatformType.FEISHU);
        admin.setUserId("user-1");
        admin.setChatId("chat-main");
        env.gatewayPolicyRepository.savePlatformAdmin(admin);
        HomeChannelRecord home = new HomeChannelRecord();
        home.setPlatform(PlatformType.FEISHU);
        home.setChatId("chat-main");
        home.setPrimary(true);
        env.gatewayPolicyRepository.saveHomeChannel(home);
        SilentTailLlmGateway llmGateway = new SilentTailLlmGateway();
        CountingDeliveryService deliveryService = new CountingDeliveryService();
        ProactiveReminderScheduler scheduler =
                new ProactiveReminderScheduler(
                        env.appConfig,
                        env.sessionRepository,
                        env.gatewayPolicyRepository,
                        env.memoryService,
                        llmGateway,
                        deliveryService,
                        new PersonaWorkspaceService(env.appConfig),
                        env.globalSettingRepository);

        scheduler.tick();

        assertThat(llmGateway.calls).isEqualTo(2);
        assertThat(deliveryService.calls).isZero();
    }

    /** 依次返回活跃度 JSON 和以静默标记收尾的主动提醒。 */
    private static class SilentTailLlmGateway extends FakeLlmGateway {
        /** 模型调用次数。 */
        private int calls;

        /** 返回当前测试阶段需要的模型文本。 */
        @Override
        public LlmResult chat(
                SessionRecord session,
                String systemPrompt,
                String userMessage,
                List<Object> toolObjects) {
            calls++;
            String content = calls == 1 ? "{\"newLevel\":1}" : "内部分析已完成。\n\n[SILENT]\n";
            LlmResult result = new LlmResult();
            result.setAssistantMessage(ChatMessage.ofAssistant(content));
            return result;
        }
    }

    /** 只记录主动提醒是否尝试投递。 */
    private static class CountingDeliveryService implements DeliveryService {
        /** 投递调用次数。 */
        private int calls;

        /** 记录一次投递调用。 */
        @Override
        public void deliver(DeliveryRequest request) {
            calls++;
        }

        /** 当前测试不注册真实渠道。 */
        @Override
        public List<ChannelStatus> statuses() {
            return Collections.emptyList();
        }
    }
}
