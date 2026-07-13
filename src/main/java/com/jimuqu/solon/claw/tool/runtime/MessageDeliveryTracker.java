package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 跟踪 send_message 向当前来源会话发起的直接投递，避免随后再次发送最终答复。 */
public final class MessageDeliveryTracker {
    /** 直接投递标记的最长保留时间，防止异常中断后的记录长期占用内存。 */
    private static final long TTL_MILLIS = 5 * 60 * 1000L;

    /** 按来源键记录最近一次向当前会话直接投递的时间。 */
    private static final Map<String, Long> RECORDS = new ConcurrentHashMap<String, Long>();

    /** 创建消息投递Tracker实例。 */
    private MessageDeliveryTracker() {}

    /**
     * 记录向当前来源会话完成的直接投递。
     *
     * @param sourceKey 渠道来源键。
     * @param sourcePlatform 来源平台。
     * @param sourceChatId 来源聊天标识。
     * @param sourceThreadId 来源线程标识。
     * @param targetPlatform 投递目标平台。
     * @param targetChatId 投递目标聊天标识。
     * @param targetThreadId 投递目标线程标识。
     */
    public static void recordDirectDelivery(
            String sourceKey,
            PlatformType sourcePlatform,
            String sourceChatId,
            String sourceThreadId,
            PlatformType targetPlatform,
            String targetChatId,
            String targetThreadId) {
        if (StrUtil.isBlank(sourceKey) || sourcePlatform == null || targetPlatform == null) {
            return;
        }
        if (sourcePlatform != targetPlatform) {
            return;
        }
        if (!StrUtil.equals(
                StrUtil.nullToEmpty(sourceChatId).trim(),
                StrUtil.nullToEmpty(targetChatId).trim())) {
            return;
        }
        if (!StrUtil.equals(
                StrUtil.nullToEmpty(sourceThreadId).trim(),
                StrUtil.nullToEmpty(targetThreadId).trim())) {
            return;
        }
        pruneExpired();
        RECORDS.put(sourceKey, Long.valueOf(System.currentTimeMillis()));
    }

    /**
     * 消费当前来源会话的一次直接投递标记。
     *
     * @param sourceKey 渠道来源键。
     * @return 已存在本轮直接投递时返回 true，否则返回 false。
     */
    public static boolean consumeDirectDelivery(String sourceKey) {
        if (StrUtil.isBlank(sourceKey)) {
            return false;
        }
        pruneExpired();
        return RECORDS.remove(sourceKey) != null;
    }

    /**
     * 清理指定来源键在上一轮异常中遗留的直接投递标记。
     *
     * @param sourceKey 渠道来源键。
     */
    public static void clearDirectDelivery(String sourceKey) {
        if (StrUtil.isNotBlank(sourceKey)) {
            RECORDS.remove(sourceKey);
        }
    }

    /** 执行pruneExpired相关逻辑。 */
    private static void pruneExpired() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : RECORDS.entrySet()) {
            if (now - entry.getValue().longValue() >= TTL_MILLIS) {
                RECORDS.remove(entry.getKey(), entry.getValue());
            }
        }
    }
}
