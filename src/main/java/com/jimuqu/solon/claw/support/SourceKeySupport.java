package com.jimuqu.solon.claw.support;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;

/** 来源键转换辅助类。 */
public final class SourceKeySupport {
    /** 创建来源键辅助实例。 */
    private SourceKeySupport() {}

    /** 将来源键转换为投递请求。 */
    public static DeliveryRequest toDeliveryRequest(String sourceKey, String text) {
        String[] parts = split(sourceKey);
        DeliveryRequest request = new DeliveryRequest();
        request.setProfile(profile(sourceKey));
        request.setPlatform(PlatformType.fromName(parts[0]));
        request.setChatId(parts[1]);
        request.setUserId(parts[2]);
        request.setThreadId(StrUtil.blankToDefault(parts[3], null));
        request.setText(text);
        return request;
    }

    /** 拆分 `platform:chatId:userId` 或 `platform:chatId:threadId:userId` 结构的来源键。 */
    public static String[] split(String sourceKey) {
        String[] out = new String[] {PlatformType.MEMORY.name(), "", "", ""};
        if (sourceKey == null) {
            return out;
        }

        String normalized = stripProfile(sourceKey);
        String[] parts = normalized.split(":", 4);
        if (parts.length > 0) {
            out[0] = parts[0];
        }
        if (parts.length > 1) {
            out[1] = parts[1];
        }
        if (parts.length == 3) {
            out[2] = parts[2];
        } else if (parts.length >= 4) {
            out[2] = parts[3];
            out[3] = parts[2];
        }

        return out;
    }

    /** 返回来源键携带的命名 Profile；默认 Profile 返回 null。 */
    public static String profile(String sourceKey) {
        if (sourceKey == null || !sourceKey.startsWith("profile:")) {
            return null;
        }
        int end = sourceKey.indexOf(':', "profile:".length());
        return end < 0 ? null : StrUtil.trimToNull(sourceKey.substring("profile:".length(), end));
    }

    /** 去掉命名 Profile 路由前缀，保留标准渠道来源键。 */
    private static String stripProfile(String sourceKey) {
        String profile = profile(sourceKey);
        return profile == null
                ? sourceKey
                : sourceKey.substring("profile:".length() + profile.length() + 1);
    }

    /** 构造指定线程的来源键前缀，末尾包含分隔符以避免 thread id 前缀碰撞。 */
    public static String threadPrefix(PlatformType platform, String chatId, String threadId) {
        if (platform == null || StrUtil.isBlank(chatId) || StrUtil.isBlank(threadId)) {
            return "";
        }
        return platform.name() + ":" + chatId + ":" + threadId.trim() + ":";
    }
}
