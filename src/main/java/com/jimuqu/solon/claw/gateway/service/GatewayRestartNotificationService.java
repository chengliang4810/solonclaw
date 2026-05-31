package com.jimuqu.solon.claw.gateway.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.RuntimePathConstants;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.noear.snack4.ONode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Delivers the post-restart online notification recorded before gateway exit. */
public class GatewayRestartNotificationService {
    private static final Logger log =
            LoggerFactory.getLogger(GatewayRestartNotificationService.class);
    private static final String ONLINE_MESSAGE = "solon-claw 网关已恢复，之前的重启请求已完成。";

    private final File runtimeHome;
    private final DeliveryService deliveryService;

    public GatewayRestartNotificationService(AppConfig appConfig, DeliveryService deliveryService) {
        this.runtimeHome =
                FileUtil.file(
                                StrUtil.blankToDefault(
                                        appConfig == null ? null : appConfig.getRuntime().getHome(),
                                        RuntimePathConstants.RUNTIME_HOME))
                        .getAbsoluteFile();
        this.deliveryService = deliveryService;
    }

    public boolean deliverPendingRestartOnlineNotification() {
        if (deliveryService == null) {
            return false;
        }
        File marker = markerFile();
        if (!marker.isFile()) {
            return false;
        }
        DeliveryRequest request = readRequest(marker);
        if (request == null) {
            FileUtil.del(marker);
            return false;
        }
        try {
            deliveryService.deliver(request);
            FileUtil.del(marker);
            return true;
        } catch (Exception e) {
            log.warn(
                    "Restart online notification delivery failed: platform={}, chatId={}, error={}",
                    request.getPlatform(),
                    request.getChatId(),
                    safeError(e));
            return false;
        }
    }

    private File markerFile() {
        return FileUtil.file(runtimeHome, GatewayRestartCoordinator.RESTART_REQUESTER_MARKER);
    }

    private DeliveryRequest readRequest(File marker) {
        try {
            Map<?, ?> data =
                    ONode.deserialize(FileUtil.readString(marker, StandardCharsets.UTF_8), Map.class);
            PlatformType platform = PlatformType.fromName(asText(data.get("platform")));
            String chatId = asText(data.get("chat_id"));
            if (platform == null || StrUtil.isBlank(chatId)) {
                return null;
            }
            DeliveryRequest request = new DeliveryRequest();
            request.setPlatform(platform);
            request.setChatId(chatId);
            request.setUserId(blankToNull(asText(data.get("user_id"))));
            request.setChatType(blankToNull(asText(data.get("chat_type"))));
            request.setThreadId(blankToNull(asText(data.get("thread_id"))));
            request.setText(ONLINE_MESSAGE);
            return request;
        } catch (Exception e) {
            log.warn("Restart online notification marker is invalid: error={}", safeError(e));
            return null;
        }
    }

    private static String asText(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String blankToNull(String value) {
        return StrUtil.isBlank(value) ? null : value;
    }

    private String safeError(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        String message = error.getMessage();
        return SecretRedactor.redact(
                StrUtil.isBlank(message) ? error.getClass().getSimpleName() : message,
                1000);
    }
}
