package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.HomeChannelRecord;
import com.jimuqu.solon.claw.support.TestEnvironment;
import org.junit.jupiter.api.Test;

public class HomeChannelCommandTest {
    @Test
    void shouldAllowOnlyAdminToSetHomeChannel() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        env.send("admin-dm", "admin-user", "hello");
        env.send("admin-dm", "admin-user", "/pairing claim-admin");

        GatewayReply denied =
                env.gatewayService.handle(
                        env.message("group-1", "user-b", "group", "Dev Group", "Bob", "/sethome"));
        assertThat(denied).isNull();

        GatewayReply success =
                env.gatewayService.handle(
                        env.message(
                                "group-1",
                                "admin-user",
                                "group",
                                "Dev Group",
                                "Alice",
                                "/sethome"));
        assertThat(success.getContent()).contains("Home Channel");

        HomeChannelRecord home = env.gatewayPolicyRepository.getHomeChannel(PlatformType.MEMORY);
        assertThat(home).isNotNull();
        assertThat(home.getChatId()).isEqualTo("group-1");
        assertThat(home.getChatName()).isEqualTo("Dev Group");

        GatewayReply platforms = env.send("admin-dm", "admin-user", "/platforms");
        assertThat(platforms.getContent()).contains("home=group-1");
        assertThat(platforms.getContent()).contains("admin=admin-user");
    }

    @Test
    void shouldPreserveThreadWhenSettingHomeChannel() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        env.send("admin-dm", "admin-user", "hello");
        env.send("admin-dm", "admin-user", "/pairing claim-admin");

        com.jimuqu.solon.claw.core.model.GatewayMessage message =
                env.message("group-1", "admin-user", "group", "Dev Group", "Alice", "/sethome");
        message.setThreadId("topic-1");
        GatewayReply success = env.gatewayService.handle(message);
        assertThat(success.getContent()).contains("Home Channel");

        HomeChannelRecord home = env.gatewayPolicyRepository.getHomeChannel(PlatformType.MEMORY);
        assertThat(home).isNotNull();
        assertThat(home.getChatId()).isEqualTo("group-1");
        assertThat(home.getThreadId()).isEqualTo("topic-1");
    }

    @Test
    void shouldSetHomeChannelWithDashedAlias() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        env.send("admin-dm", "admin-user", "hello");
        env.send("admin-dm", "admin-user", "/pairing claim-admin");

        GatewayReply success =
                env.gatewayService.handle(
                        env.message(
                                "group-alias",
                                "admin-user",
                                "group",
                                "Alias Group",
                                "Alice",
                                "/set-home"));
        assertThat(success.getContent()).contains("Home Channel");

        HomeChannelRecord home = env.gatewayPolicyRepository.getHomeChannel(PlatformType.MEMORY);
        assertThat(home).isNotNull();
        assertThat(home.getChatId()).isEqualTo("group-alias");
    }
}
