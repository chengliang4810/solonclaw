package com.jimuqu.solon.claw.bootstrap;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.gateway.service.DefaultGatewayService;
import com.jimuqu.solon.claw.gateway.service.GatewayInjectionAuthService;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.MethodType;

/** HTTP 网关入口，主要用于内存网关和调试场景下的消息注入。 */
@Controller
public class GatewayController {
    /** 网关服务。 */
    private final DefaultGatewayService gatewayService;

    private final GatewayInjectionAuthService injectionAuthService;

    public GatewayController(
            DefaultGatewayService gatewayService,
            GatewayInjectionAuthService injectionAuthService) {
        this.gatewayService = gatewayService;
        this.injectionAuthService = injectionAuthService;
    }

    /**
     * 接收统一网关消息并转发到主处理链。
     *
     * @param context HTTP 上下文
     * @return 处理结果
     */
    @Mapping(value = "/api/gateway/message", method = MethodType.POST)
    public GatewayReply message(Context context) throws Exception {
        String body;
        try {
            body = context.body();
        } catch (Exception e) {
            context.status(400);
            return errorReply("请求体读取失败 / Request body read failed");
        }
        try {
            injectionAuthService.verify(context, body);
        } catch (IllegalStateException e) {
            return errorReply(e.getMessage());
        }

        GatewayMessage message;
        try {
            if (isIncompleteJson(body)) {
                context.status(400);
                return errorReply("请求体 JSON 解析失败 / Request body JSON parse failed");
            }
            ONode node = ONode.ofJson(body);
            if (!(node.toData() instanceof Map)) {
                context.status(400);
                return errorReply("请求体必须是 JSON 对象 / Request body must be a JSON object");
            }
            message = ONode.deserialize(node.toJson(), GatewayMessage.class);
            validateMessage(message);
        } catch (IllegalArgumentException e) {
            context.status(400);
            return errorReply(e.getMessage());
        } catch (Exception e) {
            context.status(400);
            return errorReply("请求体 JSON 解析失败 / Request body JSON parse failed");
        }
        return gatewayService.handle(message);
    }

    private void validateMessage(GatewayMessage message) {
        if (message == null || message.getPlatform() == null) {
            throw new IllegalArgumentException(
                    "网关消息 platform 不能为空 / Gateway message platform is required");
        }
        switch (message.getPlatform()) {
            case MEMORY:
            case FEISHU:
            case DINGTALK:
            case WECOM:
            case WEIXIN:
                break;
            default:
                throw new IllegalArgumentException("不支持的网关平台 / Unsupported gateway platform");
        }
        if (isBlank(message.getChatId()) || isBlank(message.getUserId())) {
            throw new IllegalArgumentException(
                    "网关消息 chatId 和 userId 不能为空 / Gateway message chatId and userId are required");
        }
    }

    private GatewayReply errorReply(String message) {
        GatewayReply reply = new GatewayReply();
        reply.setError(true);
        reply.setContent(SecretRedactor.redact(message, 1000));
        return reply;
    }

    private boolean isIncompleteJson(String body) {
        String text = body == null ? "" : body.trim();
        return (text.startsWith("{") && !text.endsWith("}"))
                || (text.startsWith("[") && !text.endsWith("]"));
    }

    private boolean isBlank(String value) {
        return StrUtil.isBlank(value);
    }
}
