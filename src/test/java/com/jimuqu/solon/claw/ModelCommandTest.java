package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.support.TestEnvironment;
import org.junit.jupiter.api.Test;

public class ModelCommandTest {
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
