package com.jimuqu.solon.claw.gateway.platform;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.constants.GatewayBehaviorConstants;

/** 提供通用渠道入站访问策略判断。 */
public final class ChannelInboundPolicySupport {
    /** 工具类不允许创建实例。 */
    private ChannelInboundPolicySupport() {}

    /**
     * 判断通用私聊/群聊入站策略是否允许当前消息。
     *
     * @param config 渠道配置。
     * @param chatType 聊天类型。
     * @param chatId 聊天标识。
     * @param userId 用户标识。
     * @return 策略允许时返回 true。
     */
    public static boolean allowInbound(
            AppConfig.ChannelConfig config, String chatType, String chatId, String userId) {
        if (config.isAllowAllUsers()) {
            return true;
        }
        if (GatewayBehaviorConstants.CHAT_TYPE_GROUP.equalsIgnoreCase(chatType)) {
            String policy =
                    StrUtil.blankToDefault(
                                    config.getGroupPolicy(),
                                    GatewayBehaviorConstants.GROUP_POLICY_OPEN)
                            .toLowerCase();
            if (GatewayBehaviorConstants.GROUP_POLICY_DISABLED.equals(policy)) {
                return false;
            }
            return !GatewayBehaviorConstants.GROUP_POLICY_ALLOWLIST.equals(policy)
                    || ChannelAllowListSupport.contains(config.getGroupAllowedUsers(), chatId);
        }
        String policy =
                StrUtil.blankToDefault(
                                config.getDmPolicy(), GatewayBehaviorConstants.DM_POLICY_OPEN)
                        .toLowerCase();
        if (GatewayBehaviorConstants.DM_POLICY_DISABLED.equals(policy)) {
            return false;
        }
        return !GatewayBehaviorConstants.DM_POLICY_ALLOWLIST.equals(policy)
                || ChannelAllowListSupport.contains(config.getAllowedUsers(), userId);
    }
}
