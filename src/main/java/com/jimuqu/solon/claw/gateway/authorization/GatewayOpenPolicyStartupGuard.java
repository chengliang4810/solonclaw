package com.jimuqu.solon.claw.gateway.authorization;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.support.constants.GatewayBehaviorConstants;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/** 在渠道启动前校验开放访问策略必须有显式全量放行确认，避免误配置把 Agent 暴露给所有用户。 */
public final class GatewayOpenPolicyStartupGuard {
    /** 仅对自身实现私聊/群聊策略的国内渠道执行启动门禁。 */
    private static final Map<PlatformType, String> GUARDED_CHANNELS = guardedChannels();

    /** 工具类不保存实例状态。 */
    private GatewayOpenPolicyStartupGuard() {}

    /**
     * 校验指定 Profile 的已启用渠道；发现未确认的 open 策略时抛出不含凭据的配置异常。
     *
     * @param appConfig 当前 Profile 配置。
     * @param profileName 当前 Profile 名。
     */
    public static void requireAllowed(AppConfig appConfig, String profileName) {
        PlatformType violation = firstViolation(appConfig);
        if (violation == null) {
            return;
        }
        String channelName = GUARDED_CHANNELS.get(violation);
        String profile = StrUtil.blankToDefault(profileName, "default").trim();
        throw new ViolationException(
                "Profile '"
                        + profile
                        + "' enables open access policy for "
                        + channelName
                        + " without explicit solonclaw.gateway.allowAllUsers or "
                        + "solonclaw.channels."
                        + channelName
                        + ".allowAllUsers.");
    }

    /**
     * 返回首个违反开放策略启动门禁的平台。
     *
     * @param appConfig 当前 Profile 配置。
     * @return 首个违规平台；没有违规时返回 null。
     */
    public static PlatformType firstViolation(AppConfig appConfig) {
        if (appConfig == null || appConfig.getChannels() == null) {
            return null;
        }
        boolean globallyAllowed =
                appConfig.getGateway() != null && appConfig.getGateway().isAllowAllUsers();
        for (PlatformType platform : GUARDED_CHANNELS.keySet()) {
            AppConfig.ChannelConfig channel = channelConfig(appConfig, platform);
            if (channel == null || !channel.isEnabled()) {
                continue;
            }
            boolean open = isOpen(channel.getDmPolicy()) || isOpen(channel.getGroupPolicy());
            if (open && !globallyAllowed && !channel.isAllowAllUsers()) {
                return platform;
            }
        }
        return null;
    }

    /** 返回门禁覆盖的国内渠道及其当前配置名称。 */
    private static Map<PlatformType, String> guardedChannels() {
        EnumMap<PlatformType, String> channels =
                new EnumMap<PlatformType, String>(PlatformType.class);
        channels.put(PlatformType.WECOM, "wecom");
        channels.put(PlatformType.WEIXIN, "weixin");
        channels.put(PlatformType.QQBOT, "qqbot");
        channels.put(PlatformType.YUANBAO, "yuanbao");
        return channels;
    }

    /** 返回目标国内渠道配置。 */
    private static AppConfig.ChannelConfig channelConfig(
            AppConfig appConfig, PlatformType platform) {
        switch (platform) {
            case WECOM:
                return appConfig.getChannels().getWecom();
            case WEIXIN:
                return appConfig.getChannels().getWeixin();
            case QQBOT:
                return appConfig.getChannels().getQqbot();
            case YUANBAO:
                return appConfig.getChannels().getYuanbao();
            default:
                return null;
        }
    }

    /** 判断策略值是否为开放访问。 */
    private static boolean isOpen(String policy) {
        return GatewayBehaviorConstants.DM_POLICY_OPEN.equals(
                StrUtil.nullToEmpty(policy).trim().toLowerCase(Locale.ROOT));
    }

    /** 表示需要中止网关启动的开放策略配置错误。 */
    public static final class ViolationException extends IllegalStateException {
        /** 创建不包含渠道凭据的启动门禁异常。 */
        public ViolationException(String message) {
            super(message);
        }
    }
}
