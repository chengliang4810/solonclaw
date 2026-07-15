package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.support.TestEnvironment;
import org.junit.jupiter.api.Test;

public class GatewayAuthorizationFlowTest {
    /** 平台已有主人后，第二个私聊用户必须静默，且不能再产生可审批请求。 */
    @Test
    void shouldSilentlyIgnoreSecondUserAfterOwnerBinding() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        env.gatewayAuthorizationService.setPlatformAdmin(
                com.jimuqu.solon.claw.core.enums.PlatformType.MEMORY,
                "admin-user",
                "admin",
                "admin-chat");

        GatewayReply strangerDirect = env.send("user-chat", "user-2", "hi there");
        assertThat(strangerDirect).isNull();

        GatewayReply isolatedGroupReply =
                env.gatewayService.handle(
                        env.message(
                                "group-1", "user-2", "group", "Dev Group", "Bob", "hello group"));
        assertThat(isolatedGroupReply.getContent()).contains("echo:hello group");

        GatewayReply pending = env.send("admin-chat", "admin-user", "/pairing pending memory");
        assertThat(pending.getContent()).contains("渠道内不支持 pairing 管理");
    }

    /** 主人仍可查看和清理绑定队列，但陌生私聊不能进入该队列。 */
    @Test
    void shouldKeepPairingManagementQueueEmptyForUnknownDirectUsers() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        env.gatewayAuthorizationService.setPlatformAdmin(
                com.jimuqu.solon.claw.core.enums.PlatformType.MEMORY,
                "admin-user",
                "admin",
                "admin-chat");
        assertThat(env.send("user-chat", "user-2", "hi there")).isNull();

        GatewayReply pairing = env.send("admin-chat", "admin-user", "/pairing pending memory");
        assertThat(pairing.getContent()).contains("渠道内不支持 pairing 管理");
    }
}
