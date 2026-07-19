package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.enums.ProcessingOutcome;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.service.AgentRunCancelledException;
import com.jimuqu.solon.claw.core.service.ChannelAdapter;
import com.jimuqu.solon.claw.core.service.CommandService;
import com.jimuqu.solon.claw.core.service.ConversationOrchestrator;
import com.jimuqu.solon.claw.core.service.InboundMessageHandler;
import com.jimuqu.solon.claw.gateway.delivery.AdapterBackedDeliveryService;
import com.jimuqu.solon.claw.gateway.service.DefaultGatewayService;
import com.jimuqu.solon.claw.support.TestEnvironment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 验证网关在授权后的主处理链前后触发渠道处理状态表情回应生命周期。 */
public class GatewayProcessingReactionLifecycleTest {
    @Test
    void shouldMarkProcessingStartThenSuccessAroundAuthorizedConversation() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        claimAdmin(env, "reaction-room", "reaction-user");
        TrackingChannelAdapter adapter = new TrackingChannelAdapter();
        DefaultGatewayService gatewayService = gatewayServiceWith(env, adapter);

        GatewayMessage message = env.message("reaction-room", "reaction-user", "hello status");
        message.setThreadId("msg-1");
        GatewayReply reply = gatewayService.handle(message);

        assertThat(reply.getContent()).contains("echo:hello status");
        assertThat(adapter.events()).containsExactly("start:msg-1", "complete:msg-1:SUCCESS");
    }

    @Test
    void shouldMarkFailureWhenConversationThrows() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        claimAdmin(env, "failure-room", "failure-user");
        TrackingChannelAdapter adapter = new TrackingChannelAdapter();
        DefaultGatewayService gatewayService =
                gatewayServiceWith(env, adapter, new FailingConversation(), env.commandService);

        GatewayMessage message = env.message("failure-room", "failure-user", "explode");
        message.setThreadId("msg-failure");
        GatewayReply reply = gatewayService.handle(message);

        assertThat(reply.isError()).isTrue();
        assertThat(adapter.events())
                .containsExactly("start:msg-failure", "complete:msg-failure:FAILURE");
    }

    @Test
    void shouldMarkCancelledWhenAgentRunIsCancelled() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        claimAdmin(env, "cancel-room", "cancel-user");
        TrackingChannelAdapter adapter = new TrackingChannelAdapter();
        DefaultGatewayService gatewayService =
                gatewayServiceWith(env, adapter, new CancelledConversation(), env.commandService);

        GatewayMessage message = env.message("cancel-room", "cancel-user", "stop me");
        message.setThreadId("msg-cancel");
        GatewayReply reply = gatewayService.handle(message);

        assertThat(reply).as("被取消 run 的旧终态必须由单写者租约抑制，不能在外围补发").isNull();
        assertThat(adapter.events())
                .containsExactly("start:msg-cancel", "complete:msg-cancel:CANCELLED");
    }

    @Test
    void shouldNotMarkProcessingForDuplicateMessages() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        claimAdmin(env, "admin-room", "admin-user");
        TrackingChannelAdapter adapter = new TrackingChannelAdapter();
        DefaultGatewayService gatewayService = gatewayServiceWith(env, adapter);

        GatewayMessage first = env.message("admin-room", "admin-user", "dedupe");
        first.setThreadId("msg-duplicate");
        GatewayMessage duplicate = env.message("admin-room", "admin-user", "dedupe");
        duplicate.setThreadId("msg-duplicate");
        assertThat(gatewayService.handle(first)).isNotNull();
        assertThat(gatewayService.handle(duplicate)).isNull();

        assertThat(adapter.events())
                .containsExactly("start:msg-duplicate", "complete:msg-duplicate:SUCCESS");
    }

    @Test
    void shouldIgnoreProcessingHookFailuresAndStillReply() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        claimAdmin(env, "hook-room", "hook-user");
        TrackingChannelAdapter adapter = new TrackingChannelAdapter();
        adapter.failStart = true;
        adapter.failComplete = true;
        DefaultGatewayService gatewayService = gatewayServiceWith(env, adapter);

        GatewayMessage message = env.message("hook-room", "hook-user", "hello hook");
        message.setThreadId("msg-hook");
        GatewayReply reply = gatewayService.handle(message);

        assertThat(reply.getContent()).contains("echo:hello hook");
        assertThat(adapter.events()).containsExactly("start:msg-hook", "complete:msg-hook:SUCCESS");
    }

    @Test
    void shouldSkipProcessingReactionsWhenGatewayToggleIsDisabled() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getGateway().setProcessingReactionsEnabled(false);
        claimAdmin(env, "disabled-room", "disabled-user");
        TrackingChannelAdapter adapter = new TrackingChannelAdapter();
        DefaultGatewayService gatewayService = gatewayServiceWith(env, adapter);

        GatewayMessage message = env.message("disabled-room", "disabled-user", "hello disabled");
        message.setThreadId("msg-disabled");
        GatewayReply reply = gatewayService.handle(message);

        assertThat(reply.getContent()).contains("echo:hello disabled");
        assertThat(adapter.events()).isEmpty();
    }

    private void claimAdmin(TestEnvironment env, String chatId, String userId) throws Exception {
        env.send(chatId, userId, "hello");
        env.send(chatId, userId, "/pairing claim-admin");
    }

    private DefaultGatewayService gatewayServiceWith(
            TestEnvironment env, TrackingChannelAdapter adapter) {
        return gatewayServiceWith(env, adapter, env.conversationOrchestrator, env.commandService);
    }

    private DefaultGatewayService gatewayServiceWith(
            TestEnvironment env,
            TrackingChannelAdapter adapter,
            ConversationOrchestrator conversationOrchestrator,
            CommandService commandService) {
        Map<PlatformType, ChannelAdapter> adapters =
                new LinkedHashMap<PlatformType, ChannelAdapter>();
        adapters.put(PlatformType.MEMORY, adapter);
        AdapterBackedDeliveryService deliveryService =
                new AdapterBackedDeliveryService(
                        env.appConfig, adapters, env.gatewayPolicyRepository);
        return new DefaultGatewayService(
                commandService,
                conversationOrchestrator,
                deliveryService,
                env.sessionRepository,
                env.gatewayAuthorizationService,
                new NoopSkillLearningService(),
                null,
                adapters,
                env.appConfig);
    }

    /** 记录处理生命周期调用的内存渠道适配器。 */
    private static class TrackingChannelAdapter implements ChannelAdapter {
        private final List<String> events = Collections.synchronizedList(new ArrayList<String>());
        private boolean failStart;
        private boolean failComplete;

        @Override
        public PlatformType platform() {
            return PlatformType.MEMORY;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public boolean connect() {
            return true;
        }

        @Override
        public void disconnect() {}

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public String detail() {
            return "tracking";
        }

        @Override
        public void send(DeliveryRequest request) {}

        @Override
        public void setInboundMessageHandler(InboundMessageHandler inboundMessageHandler) {}

        @Override
        public void onProcessingStart(GatewayMessage message) {
            events.add("start:" + message.getThreadId());
            if (failStart) {
                throw new IllegalStateException("start failed");
            }
        }

        @Override
        public void onProcessingComplete(GatewayMessage message, ProcessingOutcome outcome) {
            events.add("complete:" + message.getThreadId() + ":" + outcome.name());
            if (failComplete) {
                throw new IllegalStateException("complete failed");
            }
        }

        List<String> events() {
            return new ArrayList<String>(events);
        }
    }

    /** 测试中不需要触发任务后学习。 */
    private static class NoopSkillLearningService
            implements com.jimuqu.solon.claw.core.service.SkillLearningService {
        @Override
        public void schedulePostReplyLearning(
                com.jimuqu.solon.claw.core.model.SessionRecord session,
                GatewayMessage message,
                GatewayReply reply) {}
    }

    /** 测试用失败对话编排器。 */
    private static class FailingConversation implements ConversationOrchestrator {
        @Override
        public GatewayReply handleIncoming(GatewayMessage message) {
            throw new IllegalStateException("model failed");
        }

        @Override
        public GatewayReply runScheduled(GatewayMessage syntheticMessage) {
            throw new IllegalStateException("model failed");
        }

        @Override
        public GatewayReply resumePending(String sourceKey) {
            throw new IllegalStateException("model failed");
        }
    }

    /** 测试用取消对话编排器。 */
    private static class CancelledConversation implements ConversationOrchestrator {
        @Override
        public GatewayReply handleIncoming(GatewayMessage message) {
            throw new AgentRunCancelledException();
        }

        @Override
        public GatewayReply runScheduled(GatewayMessage syntheticMessage) {
            throw new AgentRunCancelledException();
        }

        @Override
        public GatewayReply resumePending(String sourceKey) {
            throw new AgentRunCancelledException();
        }
    }
}
