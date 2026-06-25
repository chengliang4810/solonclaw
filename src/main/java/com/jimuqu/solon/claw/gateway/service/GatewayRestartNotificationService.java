package com.jimuqu.solon.claw.gateway.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.RuntimePathConstants;

import org.noear.snack4.ONode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** 读取重启前落盘的请求方信息，并在网关恢复后向原渠道发送上线通知。 */
public class GatewayRestartNotificationService {
    /** 记录重启上线通知投递失败或 marker 解析失败。 */
    private static final Logger log =
            LoggerFactory.getLogger(GatewayRestartNotificationService.class);

    /** 重启完成后发送给原请求会话的固定提示文本。 */
    private static final String ONLINE_MESSAGE = "solonclaw 网关已恢复，之前的重启请求已完成。";

    /** 运行时目录，marker 文件随 java -jar 或 Docker 运行环境落在这里。 */
    private final File workspaceHome;

    /** 国内消息渠道投递入口，用于把上线通知发回原会话。 */
    private final DeliveryService deliveryService;

    /**
     * 创建重启上线通知服务。
     *
     * @param appConfig 应用运行配置。
     * @param deliveryService 投递服务依赖。
     */
    public GatewayRestartNotificationService(AppConfig appConfig, DeliveryService deliveryService) {
        this.workspaceHome =
                FileUtil.file(
                                StrUtil.blankToDefault(
                                        appConfig == null ? null : appConfig.getRuntime().getHome(),
                                        RuntimePathConstants.WORKSPACE_HOME))
                        .getAbsoluteFile();
        this.deliveryService = deliveryService;
    }

    /**
     * 投递上一次重启请求遗留的上线通知。
     *
     * @return 成功投递并删除 marker 时返回 true。
     */
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

    /**
     * 定位重启请求方 marker 文件。
     *
     * @return runtime home 下的 marker 文件路径。
     */
    private File markerFile() {
        return FileUtil.file(workspaceHome, GatewayRestartCoordinator.RESTART_REQUESTER_MARKER);
    }

    /**
     * 从 marker 文件恢复投递目标。
     *
     * @return marker 合法时返回可投递请求，否则返回 null 并交由调用方清理。
     */
    private DeliveryRequest readRequest(File marker) {
        try {
            Map<?, ?> data =
                    ONode.deserialize(
                            FileUtil.readString(marker, StandardCharsets.UTF_8), Map.class);
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

    /**
     * 将 marker 中的任意字段安全转换为去空格文本。
     *
     * @return 空值返回空字符串，非空值返回 trim 后文本。
     */
    private static String asText(Object value) {
        return StrUtil.nullToEmpty(value == null ? null : String.valueOf(value)).trim();
    }

    /**
     * 将空白字符串归一为空值。
     *
     * @param value 待规范化或校验的原始值。
     * @return 空白文本返回 null，否则返回原文本。
     */
    private static String blankToNull(String value) {
        return StrUtil.isBlank(value) ? null : value;
    }

    /**
     * 将异常转换为可展示且不泄漏敏感信息的错误文本。
     *
     * @param error 投递或 marker 解析过程捕获到的异常。
     * @return 可写入日志的脱敏错误摘要。
     */
    private String safeError(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        String message = error.getMessage();
        return SecretRedactor.redact(
                StrUtil.isBlank(message) ? error.getClass().getSimpleName() : message, 1000);
    }
}
