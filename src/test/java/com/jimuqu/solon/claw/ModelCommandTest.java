package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.support.TestEnvironment;
import org.junit.jupiter.api.Test;

public class ModelCommandTest {
    /** 持久化会话模型必须包含 Provider，不能继续接受裸模型名。 */
    @Test
    void shouldRejectBarePersistedSessionModelOverride() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord session = new SessionRecord();
        session.setModelOverride("gpt-5.2");

        assertThatThrownBy(
                        () ->
                                new LlmProviderService(env.appConfig)
                                        .resolveEffectiveProvider(session))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("provider:model");
    }

    @Test
    void shouldShowAndSetSessionAndGlobalModel() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.send("admin-chat", "admin-user", "hello");
        env.send("admin-chat", "admin-user", "/pairing claim-admin");

        GatewayReply showReply = env.send("admin-chat", "admin-user", "/model");
        assertThat(showReply.getContent()).contains("current.provider=").contains("global.model=");

        GatewayReply sessionReply = env.send("admin-chat", "admin-user", "/model default:gpt-5.2");
        assertThat(sessionReply.getContent()).contains("default:gpt-5.2");
        assertThat(
                        env.sessionRepository
                                .getBoundSession("MEMORY:admin-chat:admin-user")
                                .getModelOverride())
                .isEqualTo("default:gpt-5.2");

        GatewayReply globalReply =
                env.send("admin-chat", "admin-user", "/model --global default:claude-sonnet-4");
        assertThat(globalReply.getContent()).contains("default:claude-sonnet-4");
        assertThat(env.appConfig.getLlm().getProvider()).isEqualTo("default");
        assertThat(env.appConfig.getLlm().getModel()).isEqualTo("claude-sonnet-4");
    }

    @Test
    void shouldSetSessionModelWithCurrentModelCommand() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.send("provider-chat", "provider-user", "hello");
        env.send("provider-chat", "provider-user", "/pairing claim-admin");

        GatewayReply sessionReply =
                env.send("provider-chat", "provider-user", "/model default:gpt-5.3");

        assertThat(sessionReply.getContent()).contains("default:gpt-5.3");
        assertThat(
                        env.sessionRepository
                                .getBoundSession("MEMORY:provider-chat:provider-user")
                                .getModelOverride())
                .isEqualTo("default:gpt-5.3");
    }

    /** 会话模型命令不得把未登记模型写入会话。 */
    @Test
    void shouldRejectUnregisteredSessionModel() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.send("invalid-model-chat", "invalid-model-user", "hello");
        env.send("invalid-model-chat", "invalid-model-user", "/pairing claim-admin");

        GatewayReply reply =
                env.send(
                        "invalid-model-chat",
                        "invalid-model-user",
                        "/model default:not-registered");

        assertThat(reply.isError()).isTrue();
        assertThat(reply.getContent()).contains("未在 Provider default 中登记");
        assertThat(
                        env.sessionRepository
                                .getBoundSession("MEMORY:invalid-model-chat:invalid-model-user")
                                .getModelOverride())
                .isNull();
    }

    /** 模型名包含冒号时只在冒号前缀确为 Provider 时才拆分。 */
    @Test
    void shouldKeepColonInsideRegisteredModelName() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig
                .getProviders()
                .get("default")
                .setModels(
                        new java.util.ArrayList<String>(
                                env.appConfig.getProviders().get("default").getModels()));
        env.appConfig.getProviders().get("default").getModels().add("qwen3:8b");
        env.send("colon-model-chat", "colon-model-user", "hello");
        env.send("colon-model-chat", "colon-model-user", "/pairing claim-admin");

        GatewayReply reply = env.send("colon-model-chat", "colon-model-user", "/model qwen3:8b");

        assertThat(reply.isError()).isFalse();
        assertThat(
                        env.sessionRepository
                                .getBoundSession("MEMORY:colon-model-chat:colon-model-user")
                                .getModelOverride())
                .isEqualTo("default:qwen3:8b");
    }

    @Test
    void shouldToggleSessionFastMode() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.send("admin-chat", "admin-user", "hello");
        env.send("admin-chat", "admin-user", "/pairing claim-admin");

        GatewayReply initial = env.send("admin-chat", "admin-user", "/fast status");
        assertThat(initial.getContent()).contains("fast_mode=normal");

        GatewayReply fast = env.send("admin-chat", "admin-user", "/fast fast");
        assertThat(fast.getContent()).contains("fast_mode=fast");
        assertThat(
                        env.sessionRepository
                                .getBoundSession("MEMORY:admin-chat:admin-user")
                                .getServiceTierOverride())
                .isEqualTo("priority");

        GatewayReply enabledStatus = env.send("admin-chat", "admin-user", "/fast status");
        assertThat(enabledStatus.getContent()).contains("service_tier=priority");

        GatewayReply normal = env.send("admin-chat", "admin-user", "/fast normal");
        assertThat(normal.getContent()).contains("fast_mode=normal");
        assertThat(
                        env.sessionRepository
                                .getBoundSession("MEMORY:admin-chat:admin-user")
                                .getServiceTierOverride())
                .isNull();
    }

    @Test
    void shouldToggleSessionReasoningEffort() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.send("reasoning-chat", "reasoning-user", "hello");
        env.send("reasoning-chat", "reasoning-user", "/pairing claim-admin");

        GatewayReply high = env.send("reasoning-chat", "reasoning-user", "/reasoning high");
        assertThat(high.getContent()).contains("reasoning_effort=high");
        assertThat(
                        env.sessionRepository
                                .getBoundSession("MEMORY:reasoning-chat:reasoning-user")
                                .getReasoningEffortOverride())
                .isEqualTo("high");

        GatewayReply status = env.send("reasoning-chat", "reasoning-user", "/reasoning");
        assertThat(status.getContent()).contains("reasoning_effort=high");

        GatewayReply reset = env.send("reasoning-chat", "reasoning-user", "/reasoning reset");
        assertThat(reset.getContent()).contains("reasoning_effort=medium");
        assertThat(
                        env.sessionRepository
                                .getBoundSession("MEMORY:reasoning-chat:reasoning-user")
                                .getReasoningEffortOverride())
                .isNull();
    }
}
