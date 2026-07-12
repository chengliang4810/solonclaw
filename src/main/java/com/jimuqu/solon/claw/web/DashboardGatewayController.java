package com.jimuqu.solon.claw.web;

import com.jimuqu.solon.claw.web.profile.DashboardProfileContext;
import java.util.Map;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.MethodType;

/** Dashboard 网关 setup 接口。 */
@Controller
public class DashboardGatewayController {
    /** 保存微信二维码配置引导服务集合，维持调用顺序或去重语义。 */
    private final WeixinQrSetupService weixinQrSetupService;

    /** 保存国内二维码配置引导服务集合，维持调用顺序或去重语义。 */
    private final DomesticQrSetupService domesticQrSetupService;

    /**
     * 创建控制台消息网关控制器实例，并注入运行所需依赖。
     *
     * @param weixinQrSetupService weixinQrSetup服务依赖。
     * @param domesticQrSetupService domesticQrSetup服务依赖。
     */
    public DashboardGatewayController(
            WeixinQrSetupService weixinQrSetupService,
            DomesticQrSetupService domesticQrSetupService) {
        this.weixinQrSetupService = weixinQrSetupService;
        this.domesticQrSetupService = domesticQrSetupService;
    }

    /**
     * 启动微信二维码。
     *
     * @return 返回微信二维码结果。
     */
    @Mapping(value = "/api/gateway/setup/weixin/qr", method = MethodType.POST)
    public Map<String, Object> startWeixinQr() {
        return DashboardResponse.ok(weixinQrSetupService.start());
    }

    /**
     * 读取微信二维码。
     *
     * @param ticket ticket 参数。
     * @return 返回读取到的微信二维码。
     */
    @Mapping(value = "/api/gateway/setup/weixin/qr/{ticket}", method = MethodType.GET)
    public Map<String, Object> getWeixinQr(String ticket) {
        return DashboardResponse.ok(weixinQrSetupService.get(ticket));
    }

    /**
     * 启动飞书二维码。
     *
     * @return 返回飞书二维码结果。
     */
    @Mapping(value = "/api/gateway/setup/feishu/qr", method = MethodType.POST)
    public Map<String, Object> startFeishuQr(Context context) {
        return DashboardResponse.ok(
                domesticQrSetupService.start(
                        "feishu", DashboardProfileContext.requestedProfile(context)));
    }

    /**
     * 读取飞书二维码。
     *
     * @param ticket ticket 参数。
     * @return 返回读取到的飞书二维码。
     */
    @Mapping(value = "/api/gateway/setup/feishu/qr/{ticket}", method = MethodType.GET)
    public Map<String, Object> getFeishuQr(Context context, String ticket) {
        return DashboardResponse.ok(
                domesticQrSetupService.get(
                        ticket, DashboardProfileContext.requestedProfile(context)));
    }

    /**
     * 启动Ding Talk二维码。
     *
     * @return 返回Ding Talk二维码结果。
     */
    @Mapping(value = "/api/gateway/setup/dingtalk/qr", method = MethodType.POST)
    public Map<String, Object> startDingTalkQr(Context context) {
        return DashboardResponse.ok(
                domesticQrSetupService.start(
                        "dingtalk", DashboardProfileContext.requestedProfile(context)));
    }

    /**
     * 读取Ding Talk二维码。
     *
     * @param ticket ticket 参数。
     * @return 返回读取到的Ding Talk二维码。
     */
    @Mapping(value = "/api/gateway/setup/dingtalk/qr/{ticket}", method = MethodType.GET)
    public Map<String, Object> getDingTalkQr(Context context, String ticket) {
        return DashboardResponse.ok(
                domesticQrSetupService.get(
                        ticket, DashboardProfileContext.requestedProfile(context)));
    }

    /** 启动企微二维码。 */
    @Mapping(value = "/api/gateway/setup/wecom/qr", method = MethodType.POST)
    public Map<String, Object> startWecomQr(Context context) {
        return DashboardResponse.ok(
                domesticQrSetupService.start(
                        "wecom", DashboardProfileContext.requestedProfile(context)));
    }

    /** 读取企微二维码。 */
    @Mapping(value = "/api/gateway/setup/wecom/qr/{ticket}", method = MethodType.GET)
    public Map<String, Object> getWecomQr(Context context, String ticket) {
        return DashboardResponse.ok(
                domesticQrSetupService.get(
                        ticket, DashboardProfileContext.requestedProfile(context)));
    }

    /** 启动 QQBot 扫码配置。 */
    @Mapping(value = "/api/gateway/setup/qqbot/qr", method = MethodType.POST)
    public Map<String, Object> startQqbotQr(Context context) {
        return DashboardResponse.ok(
                domesticQrSetupService.start(
                        "qqbot", DashboardProfileContext.requestedProfile(context)));
    }

    /** 读取 QQBot 扫码配置状态。 */
    @Mapping(value = "/api/gateway/setup/qqbot/qr/{ticket}", method = MethodType.GET)
    public Map<String, Object> getQqbotQr(Context context, String ticket) {
        return DashboardResponse.ok(
                domesticQrSetupService.get(
                        ticket, DashboardProfileContext.requestedProfile(context)));
    }
}
