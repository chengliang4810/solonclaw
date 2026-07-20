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
    void shouldNotLetAllowAllUsersBypassPersonalOwnerBoundary() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getChannels().getQqbot().setAllowAllUsers(true);
        env.appConfig.getGateway().setAllowAllUsers(true);
        createAdmin(env, PlatformType.QQBOT);

        GatewayMessage message = new GatewayMessage(PlatformType.QQBOT, "chat", "qq-user", "hello");

        assertThat(env.gatewayAuthorizationService.isAuthorized(message)).isFalse();
        assertThat(env.gatewayAuthorizationService.preAuthorize(message)).isNull();
    }

    @Test
    void shouldNotGrantPersonalContextThroughChannelAllowlist() throws Exception {
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

        assertThat(env.gatewayAuthorizationService.isAuthorized(allowed)).isFalse();
        GatewayReply preAuth = env.gatewayAuthorizationService.preAuthorize(stranger);
        assertThat(preAuth).isNull();
    }

    /** 同一用户在限流窗口内重复申请时不得发送无法迁入未来主人会话的额外提示。 */
    @Test
    void shouldSuppressRepeatedPairingRequestDuringRateLimit() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        GatewayMessage message =
                new GatewayMessage(PlatformType.MEMORY, "pairing-chat", "pairing-user", "hello");

        GatewayReply first = env.gatewayAuthorizationService.preAuthorize(message);
        GatewayReply limited = env.gatewayAuthorizationService.preAuthorize(message);

        assertThat(first).isNotNull();
        assertThat(first.getContent())
                .contains("pairing code", "Dashboard", "绑定本人")
                .doesNotContain("联系平台管理员", "/pairing approve");
        assertThat(limited).isNull();
    }

    /** 平台已有主人后，陌生私聊必须静默忽略且不能再创建 pairing 请求。 */
    @Test
    void shouldIgnoreUnknownDmAfterOwnerClaim() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        createAdmin(env, PlatformType.WEIXIN);
        GatewayMessage stranger =
                new GatewayMessage(PlatformType.WEIXIN, "stranger-chat", "stranger", "hello");

        assertThat(env.gatewayAuthorizationService.isAuthorized(stranger)).isFalse();
        assertThat(env.gatewayAuthorizationService.preAuthorize(stranger)).isNull();
        assertThat(env.gatewayPolicyRepository.listPairingRequests(PlatformType.WEIXIN)).isEmpty();
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
