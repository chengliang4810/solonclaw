package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.support.TestEnvironment;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** 验证平台管理员不能由未认证的渠道用户自举。 */
public class PlatformAdminBootstrapTest {
    /** 首个私聊用户只能获得 pairing code，不能获得管理员权限。 */
    @Test
    void shouldNotBootstrapAdminFromFirstPrivateUser() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        GatewayReply firstPrompt =
                env.gatewayService.handle(
                        env.message(
                                "dm-1",
                                "user-a",
                                "dm",
                                "Alice DM",
                                "Alice",
                                "/pairing claim-admin"));

        assertThat(firstPrompt.getContent()).contains("pairing code");
        assertThat(firstPrompt.getContent()).doesNotContain("唯一管理员");
        assertThat(env.gatewayPolicyRepository.getPlatformAdmin(PlatformType.MEMORY)).isNull();
    }

    /** 并发的管理员认领命令也必须全部被拒绝，不得产生管理员记录。 */
    @Test
    void shouldRejectConcurrentInboundAdminClaims() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<GatewayReply> first =
                    () -> env.gatewayAuthorizationService.claimAdmin(claimMessage("user-a"));
            Callable<GatewayReply> second =
                    () -> env.gatewayAuthorizationService.claimAdmin(claimMessage("user-b"));
            List<Future<GatewayReply>> replies = executor.invokeAll(List.of(first, second));

            assertThat(replies)
                    .allSatisfy(
                            future ->
                                    assertThat(future.get().getContent())
                                            .contains("不允许通过渠道消息认领平台管理员"));
            assertThat(env.gatewayPolicyRepository.getPlatformAdmin(PlatformType.MEMORY)).isNull();
        } finally {
            executor.shutdownNow();
        }
    }

    /** 创建用于并发安全回归的认领消息。 */
    private GatewayMessage claimMessage(String userId) {
        GatewayMessage message =
                new GatewayMessage(
                        PlatformType.MEMORY, "dm-" + userId, userId, "/pairing claim-admin");
        message.setChatType("dm");
        message.setUserName(userId);
        return message;
    }
}
