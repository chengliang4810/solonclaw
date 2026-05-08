package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.support.TestEnvironment;
import org.junit.jupiter.api.Test;

public class DeliveryHomeChannelFallbackTest {
    @Test
    void shouldUseHomeChannelWhenChatIdIsEmpty() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        env.send("admin-dm", "admin-user", "hello");
        env.send("admin-dm", "admin-user", "/pairing claim-admin");
        env.gatewayService.handle(
                env.message("group-1", "admin-user", "group", "Dev Group", "Alice", "/sethome"));

        DeliveryRequest request = new DeliveryRequest();
        request.setPlatform(PlatformType.MEMORY);
        request.setText("scheduled");
        env.deliveryService.deliver(request);
        assertThat(env.memoryChannelAdapter.getLastRequest().getChatId()).isEqualTo("group-1");
        assertThat(env.memoryChannelAdapter.getLastRequest().getText()).isEqualTo("scheduled");
    }

    @Test
    void shouldUseHomeChannelThreadWhenChatIdIsEmpty() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        env.send("admin-dm", "admin-user", "hello");
        env.send("admin-dm", "admin-user", "/pairing claim-admin");
        com.jimuqu.solon.claw.core.model.GatewayMessage home =
                env.message("group-1", "admin-user", "group", "Dev Group", "Alice", "/sethome");
        home.setThreadId("topic-1");
        env.gatewayService.handle(home);

        DeliveryRequest request = new DeliveryRequest();
        request.setPlatform(PlatformType.MEMORY);
        request.setText("scheduled");
        env.deliveryService.deliver(request);
        assertThat(env.memoryChannelAdapter.getLastRequest().getChatId()).isEqualTo("group-1");
        assertThat(env.memoryChannelAdapter.getLastRequest().getThreadId()).isEqualTo("topic-1");
    }
}
