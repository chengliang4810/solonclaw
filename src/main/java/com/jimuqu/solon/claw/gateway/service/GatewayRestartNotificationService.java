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

/** 提供消息网关重启Notification相关业务能力，封装调用方不需要感知的运行细节。 */
public class GatewayRestartNotificationService {
    /** 日志的统一常量值。 */
    private static final Logger log =
            LoggerFactory.getLogger(GatewayRestartNotificationService.class);

    /** ONLINE消息的统一常量值。 */
    private static final String ONLINE_MESSAGE = "solon-claw 网关已恢复，之前的重启请求已完成。";

    /** 记录消息网关重启Notification中的运行时主渠道。 */
    private final File runtimeHome;

    /** 注入投递服务，用于调用对应业务能力。 */
    private final DeliveryService deliveryService;

    /**
     * 创建消息网关重启Notification服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param deliveryService 投递服务依赖。
     */
    public GatewayRestartNotificationService(AppConfig appConfig, DeliveryService deliveryService) {
        this.runtimeHome =
                FileUtil.file(
                                StrUtil.blankToDefault(
                                        appConfig == null ? null : appConfig.getRuntime().getHome(),
                                        RuntimePathConstants.RUNTIME_HOME))
                        .getAbsoluteFile();
        this.deliveryService = deliveryService;
    }

    /**
     * 投递Pending重启Online Notification。
     *
     * @return 返回Pending重启Online Notification结果。
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
     * 执行marker文件相关逻辑。
     *
     * @return 返回marker文件结果。
     */
    private File markerFile() {
        return FileUtil.file(runtimeHome, GatewayRestartCoordinator.RESTART_REQUESTER_MARKER);
    }

    /**
     * 读取请求。
     *
     * @param marker marker 参数。
     * @return 返回读取到的请求。
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
     * 执行as文本相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回as Text结果。
     */
    private static String asText(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /**
     * 将空白字符串归一为空值。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回blank To Null结果。
     */
    private static String blankToNull(String value) {
        return StrUtil.isBlank(value) ? null : value;
    }

    /**
     * 将异常转换为可展示且不泄漏敏感信息的错误文本。
     *
     * @param error 错误参数。
     * @return 返回safe Error结果。
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
