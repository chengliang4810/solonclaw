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
import com.jimuqu.solon.claw.core.service.LlmGateway;
import com.jimuqu.solon.claw.proactive.ProactiveDiagnosticsService;
import com.jimuqu.solon.claw.proactive.ProactiveReminderScheduler;
import com.jimuqu.solon.claw.proactive.ProactiveReminderState;
import com.jimuqu.solon.claw.proactive.ProactiveReminderStateStore;
import com.jimuqu.solon.claw.support.FakeLlmGateway;
import com.jimuqu.solon.claw.support.MessageSupport;
import com.jimuqu.solon.claw.support.TestEnvironment;
import java.lang.reflect.Method;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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

    /** 关闭状态应写入 DISABLED 结果，供 Dashboard 和 why 命令解释。 */
    @Test
    void shouldPersistDisabledOutcome() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getProactive().setEnabled(false);

        scheduler(env, new ScenarioLlmGateway(), new CountingDeliveryService()).tick();

        ProactiveReminderState state =
                new ProactiveReminderStateStore(env.globalSettingRepository).load();
        assertThat(state.getLastOutcome()).isEqualTo(ProactiveReminderState.OUTCOME_DISABLED);
        assertThat(state.getLastReason()).contains("已暂停", "resume");
    }

    /** 服务以暂停状态启动时仍应创建调度线程，使运行时恢复无需重启。 */
    @Test
    void shouldKeepSchedulerRunningWhenInitiallyDisabled() {
        AppConfig config = new AppConfig();
        config.getProactive().setEnabled(false);
        ProactiveReminderScheduler scheduler =
                new ProactiveReminderScheduler(config, null, null, null, null, null, null, null);

        scheduler.start();

        assertThat(scheduler.isRunning()).isTrue();
        scheduler.shutdown();
        assertThat(scheduler.isRunning()).isFalse();
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
        ProactiveReminderState state =
                new ProactiveReminderStateStore(env.globalSettingRepository).load();
        assertThat(state.getLastOutcome()).isEqualTo(ProactiveReminderState.OUTCOME_MODEL_SILENT);
        assertThat(state.getLastReason()).contains("没有适合发送的内容");
    }

    /** 完整成功路径应持久化分析理由、时间输入、额度和投递结果。 */
    @Test
    void shouldPersistSuccessfulTickAndExposeItThroughDiagnostics() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bindHome(env);
        ScenarioLlmGateway llmGateway =
                new ScenarioLlmGateway("{\"new_level\":1,\"reason\":\"用户近期持续互动\"}", "记得休息一下。");
        CountingDeliveryService deliveryService = new CountingDeliveryService();
        ProactiveReminderScheduler scheduler = scheduler(env, llmGateway, deliveryService);

        scheduler.tick();

        ProactiveReminderState state =
                new ProactiveReminderStateStore(env.globalSettingRepository).load();
        assertThat(deliveryService.calls).isEqualTo(1);
        assertThat(state.getLastOutcome()).isEqualTo(ProactiveReminderState.OUTCOME_DELIVERED);
        assertThat(state.getLastReason()).contains("成功投递");
        assertThat(state.getAnalysisReason()).isEqualTo("用户近期持续互动");
        assertThat(state.getLastTickAt()).isPositive();
        assertThat(state.getLastAnalysisAt()).isPositive();
        assertThat(state.getLastSentAt()).isPositive();
        assertThat(state.getUnansweredCount()).isEqualTo(1);
        assertThat(llmGateway.userPrompts.get(0))
                .contains("current_time:", "last_user_activity_at:", "last_sent_at:");

        ProactiveDiagnosticsService diagnostics =
                new ProactiveDiagnosticsService(
                        env.appConfig,
                        env.sessionRepository,
                        env.gatewayPolicyRepository,
                        env.globalSettingRepository);
        Map<String, Object> status = diagnostics.status();
        assertThat(status)
                .containsEntry("last_outcome", ProactiveReminderState.OUTCOME_DELIVERED)
                .containsEntry("analysis_reason", "用户近期持续互动")
                .containsEntry("unanswered_count", Integer.valueOf(1));
        assertThat(status.get("last_tick_at")).isNotNull();
        assertThat(status.get("last_sent_at")).isNotNull();
    }

    /** 没有主渠道会话时应记录明确跳过原因，而不是静默返回。 */
    @Test
    void shouldPersistMissingMainConversationOutcome() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getProactive().setQuietHoursEnabled(false);
        ProactiveReminderScheduler scheduler =
                scheduler(env, new ScenarioLlmGateway(), new CountingDeliveryService());

        scheduler.tick();

        ProactiveReminderState state =
                new ProactiveReminderStateStore(env.globalSettingRepository).load();
        assertThat(state.getLastOutcome())
                .isEqualTo(ProactiveReminderState.OUTCOME_NO_MAIN_CONVERSATION);
        assertThat(state.getLastReason()).contains("没有找到", "主渠道");
    }

    /** 活跃度额度不足时应保留累计值并记录可解释结果。 */
    @Test
    void shouldPersistLowActivityCreditOutcome() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bindHome(env);
        ScenarioLlmGateway llmGateway =
                new ScenarioLlmGateway("{\"new_level\":0.2,\"reason\":\"用户近期较忙\"}");
        CountingDeliveryService deliveryService = new CountingDeliveryService();

        scheduler(env, llmGateway, deliveryService).tick();

        ProactiveReminderState state =
                new ProactiveReminderStateStore(env.globalSettingRepository).load();
        assertThat(state.getLastOutcome())
                .isEqualTo(ProactiveReminderState.OUTCOME_ACTIVITY_CREDIT_LOW);
        assertThat(state.getActivityCredit()).isEqualTo(0.2D);
        assertThat(state.getAnalysisReason()).isEqualTo("用户近期较忙");
        assertThat(deliveryService.calls).isZero();
    }

    /** 后台 Assistant 消息更新会话时不得伪装成用户回应。 */
    @Test
    void shouldNotResetUnansweredCountForAssistantOnlyUpdate() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bindHome(env);
        SessionRecord session = env.sessionRepository.listRecent(1).get(0);
        session.setNdjson(
                MessageSupport.toNdjson(
                        Collections.singletonList(ChatMessage.ofAssistant("后台通知"))));
        env.sessionRepository.save(session);
        ProactiveReminderState previous = new ProactiveReminderState();
        previous.setUnansweredCount(2);
        new ProactiveReminderStateStore(env.globalSettingRepository).save(previous);

        scheduler(
                        env,
                        new ScenarioLlmGateway("{\"new_level\":0,\"reason\":\"保持安静\"}"),
                        new CountingDeliveryService())
                .tick();

        ProactiveReminderState state =
                new ProactiveReminderStateStore(env.globalSettingRepository).load();
        assertThat(state.getUnansweredCount()).isEqualTo(2);
        assertThat(state.getObservedUserMessageCount()).isZero();
    }

    /** 只有新增的真实用户消息才能清零连续未回应次数。 */
    @Test
    void shouldResetUnansweredCountForNewUserMessage() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bindHome(env);
        SessionRecord session = env.sessionRepository.listRecent(1).get(0);
        session.setNdjson(
                MessageSupport.toNdjson(Collections.singletonList(ChatMessage.ofUser("收到"))));
        env.sessionRepository.save(session);
        ProactiveReminderState previous = new ProactiveReminderState();
        previous.setUnansweredCount(2);
        new ProactiveReminderStateStore(env.globalSettingRepository).save(previous);

        scheduler(
                        env,
                        new ScenarioLlmGateway("{\"new_level\":0,\"reason\":\"保持安静\"}"),
                        new CountingDeliveryService())
                .tick();

        ProactiveReminderState state =
                new ProactiveReminderStateStore(env.globalSettingRepository).load();
        assertThat(state.getUnansweredCount()).isZero();
        assertThat(state.getObservedUserMessageCount()).isEqualTo(1);
    }

    /** 会话压缩导致用户消息数下降后，下一条用户消息仍必须被识别。 */
    @Test
    void shouldRebaseObservedUserCountAfterConversationCompression() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bindHome(env);
        SessionRecord session = env.sessionRepository.listRecent(1).get(0);
        session.setNdjson(
                MessageSupport.toNdjson(Collections.singletonList(ChatMessage.ofUser("压缩后保留"))));
        env.sessionRepository.save(session);
        ProactiveReminderState previous = new ProactiveReminderState();
        previous.setUnansweredCount(2);
        previous.setObservedUserMessageCount(3);
        new ProactiveReminderStateStore(env.globalSettingRepository).save(previous);
        ProactiveReminderScheduler scheduler =
                scheduler(
                        env,
                        new ScenarioLlmGateway(
                                "{\"new_level\":0,\"reason\":\"保持安静\"}",
                                "{\"new_level\":0,\"reason\":\"保持安静\"}"),
                        new CountingDeliveryService());

        scheduler.tick();
        ProactiveReminderState rebased =
                new ProactiveReminderStateStore(env.globalSettingRepository).load();
        assertThat(rebased.getUnansweredCount()).isEqualTo(2);
        assertThat(rebased.getObservedUserMessageCount()).isEqualTo(1);

        session = env.sessionRepository.findById(session.getSessionId());
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(ChatMessage.ofUser("压缩后保留"), ChatMessage.ofUser("新的回复"))));
        env.sessionRepository.save(session);
        scheduler.tick();

        ProactiveReminderState state =
                new ProactiveReminderStateStore(env.globalSettingRepository).load();
        assertThat(state.getUnansweredCount()).isZero();
        assertThat(state.getObservedUserMessageCount()).isEqualTo(2);
    }

    /** 活跃度 JSON 无效时保留旧等级，并把降级原因写入诊断。 */
    @Test
    void shouldPersistInvalidAnalysisFallbackReason() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bindHome(env);
        ScenarioLlmGateway llmGateway = new ScenarioLlmGateway("not-json", "暂时没有合适内容。\n[SILENT]");

        scheduler(env, llmGateway, new CountingDeliveryService()).tick();

        ProactiveReminderState state =
                new ProactiveReminderStateStore(env.globalSettingRepository).load();
        assertThat(state.getActivityLevel()).isEqualTo(1D);
        assertThat(state.getAnalysisReason()).contains("输出无效", "保留");
        assertThat(state.getLastOutcome()).isEqualTo(ProactiveReminderState.OUTCOME_MODEL_SILENT);
    }

    /** 渠道投递异常应记录 DELIVERY_FAILED，且不误记成功发送时间。 */
    @Test
    void shouldPersistDeliveryFailure() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bindHome(env);
        env.appConfig.getProactive().setTopicCooldownHours(0);
        CountingDeliveryService deliveryService = new CountingDeliveryService();
        deliveryService.fail = true;

        scheduler(
                        env,
                        new ScenarioLlmGateway("{\"new_level\":1,\"reason\":\"可以联系\"}", "测试提醒"),
                        deliveryService)
                .tick();

        ProactiveReminderState state =
                new ProactiveReminderStateStore(env.globalSettingRepository).load();
        assertThat(state.getLastOutcome())
                .isEqualTo(ProactiveReminderState.OUTCOME_DELIVERY_FAILED);
        assertThat(state.getLastReason()).contains("渠道投递失败", "simulated failure");
        assertThat(state.getLastSentAt()).isZero();
    }

    /** 相同内容在冷却期内应记录 TOPIC_COOLDOWN 并跳过重复投递。 */
    @Test
    void shouldPersistTopicCooldownOutcome() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bindHome(env);
        ProactiveReminderState previous = new ProactiveReminderState();
        previous.setLastSentAt(System.currentTimeMillis());
        previous.setLastMessage("相同提醒");
        new ProactiveReminderStateStore(env.globalSettingRepository).save(previous);
        CountingDeliveryService deliveryService = new CountingDeliveryService();

        scheduler(
                        env,
                        new ScenarioLlmGateway("{\"new_level\":1,\"reason\":\"可以联系\"}", "相同提醒"),
                        deliveryService)
                .tick();

        ProactiveReminderState state =
                new ProactiveReminderStateStore(env.globalSettingRepository).load();
        assertThat(state.getLastOutcome()).isEqualTo(ProactiveReminderState.OUTCOME_TOPIC_COOLDOWN);
        assertThat(deliveryService.calls).isZero();
    }

    /** 免打扰时段应在调用模型前记录 QUIET_HOURS。 */
    @Test
    void shouldPersistQuietHoursOutcome() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getProactive().setQuietHoursEnabled(true);
        env.appConfig.getProactive().setQuietStart("00:00");
        env.appConfig.getProactive().setQuietEnd("00:00");

        scheduler(env, new ScenarioLlmGateway(), new CountingDeliveryService()).tick();

        ProactiveReminderState state =
                new ProactiveReminderStateStore(env.globalSettingRepository).load();
        assertThat(state.getLastOutcome()).isEqualTo(ProactiveReminderState.OUTCOME_QUIET_HOURS);
    }

    /** 无效发送目标应保留 CONFIG_INVALID 诊断，不被通用异常覆盖。 */
    @Test
    void shouldPersistInvalidDeliveryTargetOutcome() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getProactive().setQuietHoursEnabled(false);
        env.appConfig.getProactive().setDeliveryTarget("unknown");
        ProactiveReminderScheduler scheduler =
                scheduler(env, new ScenarioLlmGateway(), new CountingDeliveryService());

        scheduler.tickSafe();

        ProactiveReminderState state =
                new ProactiveReminderStateStore(env.globalSettingRepository).load();
        assertThat(state.getLastOutcome()).isEqualTo(ProactiveReminderState.OUTCOME_CONFIG_INVALID);
        assertThat(state.getLastReason()).contains("仅支持 main");
    }

    /** 主动提醒每天最多联系三次。 */
    @Test
    void shouldRespectDailyContactLimit() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bindHome(env);
        env.appConfig.getProactive().setTopicCooldownHours(0D);
        CountingDeliveryService deliveryService = new CountingDeliveryService();
        ProactiveReminderScheduler scheduler =
                schedulerAtHour(
                        env,
                        new ScenarioLlmGateway(
                                "{\"new_level\":1,\"reason\":\"可以联系\"}",
                                "第一次主动联系",
                                "{\"new_level\":1,\"reason\":\"可以联系\"}",
                                "第二次主动联系",
                                "{\"new_level\":1,\"reason\":\"可以联系\"}",
                                "第三次主动联系"),
                        deliveryService,
                        9);

        scheduler.tick();
        scheduler.tick();
        scheduler.tick();
        scheduler.tick();

        ProactiveReminderState state =
                new ProactiveReminderStateStore(env.globalSettingRepository).load();
        assertThat(deliveryService.calls).isEqualTo(3);
        assertThat(state.getDailyContactCount()).isEqualTo(3);
        assertThat(state.getLastOutcome()).isEqualTo(ProactiveReminderState.OUTCOME_MODEL_SILENT);
    }

    /** 为测试绑定一个可投递的飞书主渠道和普通会话。 */
    private void bindHome(TestEnvironment env) throws Exception {
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
    }

    /** 按测试替身创建主动提醒调度器。 */
    private ProactiveReminderScheduler scheduler(
            TestEnvironment env, LlmGateway llmGateway, DeliveryService deliveryService) {
        return new ProactiveReminderScheduler(
                env.appConfig,
                env.sessionRepository,
                env.gatewayPolicyRepository,
                env.memoryService,
                llmGateway,
                deliveryService,
                new PersonaWorkspaceService(env.appConfig),
                env.globalSettingRepository);
    }

    /** 按测试替身创建固定时刻的主动提醒调度器。 */
    private ProactiveReminderScheduler schedulerAtHour(
            TestEnvironment env, LlmGateway llmGateway, DeliveryService deliveryService, int hour) {
        return new ProactiveReminderScheduler(
                env.appConfig,
                env.sessionRepository,
                env.gatewayPolicyRepository,
                env.memoryService,
                llmGateway,
                deliveryService,
                new PersonaWorkspaceService(env.appConfig),
                env.globalSettingRepository) {
            @Override
            protected LocalTime currentLocalTime() {
                return LocalTime.of(hour, 0);
            }
        };
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

        /** 是否模拟渠道投递失败。 */
        private boolean fail;

        /** 记录一次投递调用。 */
        @Override
        public void deliver(DeliveryRequest request) {
            calls++;
            if (fail) {
                throw new IllegalStateException("simulated failure");
            }
        }

        /** 当前测试不注册真实渠道。 */
        @Override
        public List<ChannelStatus> statuses() {
            return Collections.emptyList();
        }
    }

    /** 按顺序返回预设文本并记录模型输入。 */
    private static class ScenarioLlmGateway extends FakeLlmGateway {
        /** 依次返回的模型文本。 */
        private final List<String> responses;

        /** 收到的用户提示。 */
        private final List<String> userPrompts = new ArrayList<String>();

        /** 创建没有预设返回值的场景模型。 */
        private ScenarioLlmGateway() {
            this.responses = Collections.emptyList();
        }

        /** 按调用顺序配置模型返回文本。 */
        private ScenarioLlmGateway(String... responses) {
            this.responses = Arrays.asList(responses);
        }

        /** 返回当前调用对应的预设文本。 */
        @Override
        public LlmResult chat(
                SessionRecord session,
                String systemPrompt,
                String userMessage,
                List<Object> toolObjects) {
            userPrompts.add(userMessage);
            int index = userPrompts.size() - 1;
            String content = index < responses.size() ? responses.get(index) : "";
            LlmResult result = new LlmResult();
            result.setAssistantMessage(ChatMessage.ofAssistant(content));
            return result;
        }
    }
}
