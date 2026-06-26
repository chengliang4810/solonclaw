package com.jimuqu.solon.claw.gateway.platform;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.support.constants.GatewayBehaviorConstants;
import java.util.List;

/** 提供渠道允许名单的通用匹配逻辑。 */
public final class ChannelAllowListSupport {
    /** 工具类不允许创建实例。 */
    private ChannelAllowListSupport() {}

    /**
     * 判断允许名单是否包含目标值，支持通配符和大小写不敏感匹配。
     *
     * @param values 允许名单。
     * @param target 待匹配目标。
     * @return 命中允许名单时返回 true。
     */
    public static boolean contains(List<String> values, String target) {
        if (values == null || target == null) {
            return false;
        }
        for (String value : values) {
            String normalized = StrUtil.nullToEmpty(value).trim();
            if (GatewayBehaviorConstants.ALLOW_ALL_MARKER.equals(normalized)
                    || target.equalsIgnoreCase(normalized)) {
                return true;
            }
        }
        return false;
    }
}
