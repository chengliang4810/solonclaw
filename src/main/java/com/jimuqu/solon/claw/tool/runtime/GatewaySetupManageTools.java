package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.web.DomesticQrSetupService;
import com.jimuqu.solon.claw.web.WeixinQrSetupService;
import java.util.Locale;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** 提供国内渠道二维码 setup 查询与启动工具。 */
public class GatewaySetupManageTools {
    /** 微信二维码 setup 服务，用于复用 Dashboard 微信配置引导流程。 */
    private final WeixinQrSetupService weixinQrSetupService;

    /** 国内渠道二维码 setup 服务，用于复用飞书和钉钉配置引导流程。 */
    private final DomesticQrSetupService domesticQrSetupService;

    /**
     * 创建网关 setup 管理工具。
     *
     * @param weixinQrSetupService 微信二维码 setup 服务。
     * @param domesticQrSetupService 国内渠道二维码 setup 服务。
     */
    public GatewaySetupManageTools(
            WeixinQrSetupService weixinQrSetupService,
            DomesticQrSetupService domesticQrSetupService) {
        this.weixinQrSetupService = weixinQrSetupService;
        this.domesticQrSetupService = domesticQrSetupService;
    }

    /**
     * 启动或查询国内渠道二维码 setup。
     *
     * @param action 操作名称。
     * @param channel 渠道名称。
     * @param ticket setup ticket。
     * @param profile 可选 Profile；空值表示当前运行 Profile。
     * @return 返回工具结果 JSON。
     */
    @ToolMapping(
            name = "gateway_setup_manage",
            description =
                    "Start or inspect dashboard QR setup for weixin, feishu, dingtalk, wecom,"
                            + " qqbot. Actions: start, get.")
    public String gatewaySetupManage(
            @Param(name = "action", description = "start, get") String action,
            @Param(name = "channel", description = "weixin, feishu, dingtalk, wecom, qqbot")
                    String channel,
            @Param(name = "ticket", required = false, description = "QR setup ticket")
                    String ticket,
            @Param(name = "profile", required = false, description = "Target profile")
                    String profile) {
        try {
            if (weixinQrSetupService == null || domesticQrSetupService == null) {
                return ToolResultEnvelope.error("gateway setup service unavailable").toJson();
            }
            Map<String, Object> result = run(action, channel, ticket, profile);
            return ToolResultEnvelope.ok("网关配置引导查询完成")
                    .preview(SecretRedactor.redact(ONode.serialize(result), 3000))
                    .data("result", result)
                    .toJson();
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ToolResultEnvelope.error(SecretRedactor.redact(message, 1000)).toJson();
        }
    }

    /**
     * 执行二维码 setup 动作。
     *
     * @param action 操作名称。
     * @param channel 渠道名称。
     * @param ticket setup ticket。
     * @return 返回 Dashboard setup 服务结果。
     */
    private Map<String, Object> run(String action, String channel, String ticket, String profile) {
        String normalizedAction = action == null ? "get" : action.trim().toLowerCase(Locale.ROOT);
        String normalizedChannel =
                StrUtil.blankToDefault(channel, "weixin").trim().toLowerCase(Locale.ROOT);
        if ("start".equals(normalizedAction)) {
            if ("weixin".equals(normalizedChannel)) {
                return weixinQrSetupService.start();
            }
            return domesticQrSetupService.start(normalizedChannel, profile);
        }
        if ("weixin".equals(normalizedChannel)) {
            return weixinQrSetupService.get(ticket);
        }
        return domesticQrSetupService.get(ticket, profile);
    }
}
