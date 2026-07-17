package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.ChannelStatus;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.gateway.service.GatewayRestartCoordinator;
import com.jimuqu.solon.claw.gateway.service.GatewayRestartNotificationService;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.snack4.ONode;

public class GatewayRestartNotificationServiceTest {
    @TempDir Path tempDir;

    @Test
    void shouldDeliverRestartOnlineNotificationToPersistedThreadTarget() throws Exception {
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(tempDir.toFile().getAbsolutePath());
        File marker =
                new File(tempDir.toFile(), GatewayRestartCoordinator.RESTART_REQUESTER_MARKER);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("platform", "MEMORY");
        payload.put("chat_id", "admin-chat");
        payload.put("user_id", "admin-user");
        payload.put("chat_type", "group");
        payload.put("thread_id", "topic-7");
        payload.put("source_key", "MEMORY:admin-chat:topic-7:admin-user");
        FileUtil.writeString(ONode.serialize(payload), marker, StandardCharsets.UTF_8);
        CapturingDeliveryService deliveryService = new CapturingDeliveryService();
        GatewayRestartNotificationService service =
                new GatewayRestartNotificationService(config, deliveryService);

        boolean delivered = service.deliverPendingRestartOnlineNotification();

        assertThat(delivered).isTrue();
        assertThat(marker).doesNotExist();
        assertThat(deliveryService.requests).hasSize(1);
        DeliveryRequest request = deliveryService.requests.get(0);
        assertThat(request.getPlatform()).isEqualTo(PlatformType.MEMORY);
        assertThat(request.getChatId()).isEqualTo("admin-chat");
        assertThat(request.getUserId()).isEqualTo("admin-user");
        assertThat(request.getChatType()).isEqualTo("group");
        assertThat(request.getThreadId()).isEqualTo("topic-7");
        assertThat(request.getText()).contains("网关已恢复").contains("solonclaw");
        assertThat(request.isRecordInConversation()).isTrue();
    }

    private static class CapturingDeliveryService implements DeliveryService {
        private final List<DeliveryRequest> requests = new ArrayList<DeliveryRequest>();

        @Override
        public void deliver(DeliveryRequest request) {
            requests.add(request);
        }

        @Override
        public List<ChannelStatus> statuses() {
            return new ArrayList<ChannelStatus>();
        }
    }
}
