package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.support.TestEnvironment;
import org.junit.jupiter.api.Test;

public class GatewayAuthorizationFlowTest {
    @Test
    void shouldRequirePairingForSecondUserAfterAdminBootstrap() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        env.gatewayAuthorizationService.setPlatformAdmin(
                com.jimuqu.solon.claw.core.enums.PlatformType.MEMORY,
                "admin-user",
                "admin",
                "admin-chat");

        GatewayReply pairPrompt = env.send("user-chat", "user-2", "hi there");
        assertThat(pairPrompt.getContent()).contains("pairing code");
        String code = pairPrompt.getContent().split("`")[1];

        GatewayReply groupUnauthorized =
                env.gatewayService.handle(
                        env.message(
                                "group-1", "user-2", "group", "Dev Group", "Bob", "hello group"));
        assertThat(groupUnauthorized).isNull();

        GatewayReply pending = env.send("admin-chat", "admin-user", "/pairing pending memory");
        assertThat(pending.getContent()).contains("user-2").doesNotContain(code);

        GatewayReply approve =
                env.send("admin-chat", "admin-user", "/pairing approve memory " + code);
        assertThat(approve.getContent()).contains("已批准");

        GatewayReply normalReply = env.send("user-chat", "user-2", "hello after pairing");
        assertThat(normalReply.getContent()).contains("echo:hello after pairing");
    }

    @Test
    void shouldSupportPairingListAndClearPendingAliases() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        env.gatewayAuthorizationService.setPlatformAdmin(
                com.jimuqu.solon.claw.core.enums.PlatformType.MEMORY,
                "admin-user",
                "admin",
                "admin-chat");
        GatewayReply pairPrompt = env.send("user-chat", "user-2", "hi there");
        String code = pairPrompt.getContent().split("`")[1];

        GatewayReply list = env.send("admin-chat", "admin-user", "/pairing list");
        assertThat(list.getContent())
                .contains("待处理 pairing")
                .contains("user-2")
                .doesNotContain(code)
                .contains("已批准用户")
                .contains("无");

        GatewayReply cleared = env.send("admin-chat", "admin-user", "/pairing clear-pending");
        assertThat(cleared.getContent()).contains("memory 平台待处理 pairing 请求已清理");

        GatewayReply pending = env.send("admin-chat", "admin-user", "/pairing pending memory");
        assertThat(pending.getContent()).contains("当前没有待处理的 pairing 请求");
    }
}
