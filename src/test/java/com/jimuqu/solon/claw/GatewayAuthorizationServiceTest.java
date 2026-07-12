package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.PlatformAdminRecord;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.support.constants.GatewayBehaviorConstants;
import org.junit.jupiter.api.Test;

public class GatewayAuthorizationServiceTest {
    @Test
    void shouldHonorQqbotChannelAllowAllUsers() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getChannels().getQqbot().setAllowAllUsers(true);

        GatewayMessage message = new GatewayMessage(PlatformType.QQBOT, "chat", "qq-user", "hello");

        assertThat(env.gatewayAuthorizationService.isAuthorized(message)).isTrue();
    }

    @Test
    void shouldHonorYuanbaoChannelAllowlistAndUnauthorizedBehavior() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getChannels().getYuanbao().getAllowedUsers().add("allowed-user");
        env.appConfig
                .getChannels()
                .getYuanbao()
                .setUnauthorizedDmBehavior(
                        GatewayBehaviorConstants.UNAUTHORIZED_DM_BEHAVIOR_IGNORE);
        createAdmin(env, PlatformType.YUANBAO);

        GatewayMessage allowed =
                new GatewayMessage(PlatformType.YUANBAO, "chat", "allowed-user", "hello");
        GatewayMessage stranger =
                new GatewayMessage(PlatformType.YUANBAO, "chat", "stranger", "hello");

        assertThat(env.gatewayAuthorizationService.isAuthorized(allowed)).isTrue();
        GatewayReply preAuth = env.gatewayAuthorizationService.preAuthorize(stranger);
        assertThat(preAuth).isNull();
    }

    /** 同一用户在限流窗口内重复申请时必须获得包含剩余分钟数的明确提示。 */
    @Test
    void shouldReturnRemainingWaitWhenPairingRequestIsRateLimited() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        GatewayMessage message =
                new GatewayMessage(PlatformType.MEMORY, "pairing-chat", "pairing-user", "hello");

        GatewayReply first = env.gatewayAuthorizationService.preAuthorize(message);
        GatewayReply limited = env.gatewayAuthorizationService.preAuthorize(message);

        assertThat(first).isNotNull();
        assertThat(first.getContent()).contains("pairing code");
        assertThat(limited).isNotNull();
        assertThat(limited.getContent()).contains("请求过于频繁").contains("10 分钟后再试");
    }

    private void createAdmin(TestEnvironment env, PlatformType platform) throws Exception {
        PlatformAdminRecord admin = new PlatformAdminRecord();
        admin.setPlatform(platform);
        admin.setUserId("admin-user");
        admin.setUserName("admin");
        admin.setChatId("admin-chat");
        admin.setCreatedAt(System.currentTimeMillis());
        env.gatewayPolicyRepository.createPlatformAdminIfAbsent(admin);
    }
}
