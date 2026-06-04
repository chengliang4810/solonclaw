package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.ChannelStatus;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.service.ChannelAdapter;
import com.jimuqu.solon.claw.gateway.service.ChannelConnectionManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;

public class ChannelConnectionManagerTest {
    @Test
    void shouldExposeReconnectStateWhenEnabledChannelConnectFails() {
        FailingChannelAdapter adapter =
                new FailingChannelAdapter(
                        PlatformType.FEISHU, "network failed token=ghp_channelretry12345");
        Map<PlatformType, ChannelAdapter> adapters =
                new LinkedHashMap<PlatformType, ChannelAdapter>();
        adapters.put(adapter.platform(), adapter);
        ChannelConnectionManager manager = new ChannelConnectionManager(adapters);

        try {
            manager.startAll();
            List<ChannelStatus> statuses = manager.statusSnapshots();
            String json = ONode.serialize(statuses.get(0));

            assertThat(json)
                    .contains("\"reconnecting\":true")
                    .contains("\"reconnectAttempt\":1")
                    .contains("\"nextReconnectAt\":")
                    .contains("\"lastReconnectError\":\"network failed token=***\"")
                    .doesNotContain("ghp_channelretry12345");
        } finally {
            manager.shutdown();
        }
    }

    private static class FailingChannelAdapter implements ChannelAdapter {
        private final PlatformType platform;
        private final String failureMessage;

        private FailingChannelAdapter(PlatformType platform, String failureMessage) {
            this.platform = platform;
            this.failureMessage = failureMessage;
        }

        @Override
        public PlatformType platform() {
            return platform;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public boolean connect() {
            throw new IllegalStateException(failureMessage);
        }

        @Override
        public void disconnect() {}

        @Override
        public boolean isConnected() {
            return false;
        }

        @Override
        public String detail() {
            return "connect failed";
        }

        @Override
        public void send(DeliveryRequest request) {}
    }
}
