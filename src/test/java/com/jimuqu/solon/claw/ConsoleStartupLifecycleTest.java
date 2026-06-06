package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.bootstrap.GatewayConfiguration;
import com.jimuqu.solon.claw.bootstrap.StartupModeContext;
import com.jimuqu.solon.claw.cli.CliMode;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.service.ChannelAdapter;
import com.jimuqu.solon.claw.gateway.service.ChannelConnectionManager;
import com.jimuqu.solon.claw.gateway.service.GatewayRestartNotificationService;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class ConsoleStartupLifecycleTest {
    @AfterEach
    void resetStartupMode() {
        StartupModeContext.set(new CliMode(CliMode.Kind.SERVER, null, null));
    }

    @Test
    void shouldSkipGatewayNetworkLifecycleForConsoleCommands() {
        StartupModeContext.set(new CliMode(CliMode.Kind.CLI, "/setup model", null));
        RecordingChannelConnectionManager manager = new RecordingChannelConnectionManager();
        RecordingGatewayRestartNotificationService notification =
                new RecordingGatewayRestartNotificationService();

        new GatewayConfiguration()
                .gatewayService(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Collections.<PlatformType, ChannelAdapter>emptyMap(),
                        manager,
                        notification);

        assertThat(manager.bound).isTrue();
        assertThat(manager.started).isFalse();
        assertThat(notification.delivered).isFalse();
    }

    @Test
    void shouldStartGatewayNetworkLifecycleForServerMode() {
        StartupModeContext.set(new CliMode(CliMode.Kind.SERVER, null, null));
        RecordingChannelConnectionManager manager = new RecordingChannelConnectionManager();
        RecordingGatewayRestartNotificationService notification =
                new RecordingGatewayRestartNotificationService();

        new GatewayConfiguration()
                .gatewayService(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Collections.<PlatformType, ChannelAdapter>emptyMap(),
                        manager,
                        notification);

        assertThat(manager.bound).isTrue();
        assertThat(manager.started).isTrue();
        assertThat(notification.delivered).isTrue();
    }

    private static final class RecordingChannelConnectionManager
            extends ChannelConnectionManager {
        private boolean bound;
        private boolean started;

        private RecordingChannelConnectionManager() {
            super(Collections.<PlatformType, ChannelAdapter>emptyMap());
        }

        @Override
        public void bindInboundHandler(
                com.jimuqu.solon.claw.core.service.InboundMessageHandler handler) {
            bound = true;
        }

        @Override
        public void startAll() {
            started = true;
        }
    }

    private static final class RecordingGatewayRestartNotificationService
            extends GatewayRestartNotificationService {
        private boolean delivered;

        private RecordingGatewayRestartNotificationService() {
            super(null, null);
        }

        @Override
        public boolean deliverPendingRestartOnlineNotification() {
            delivered = true;
            return true;
        }
    }
}
