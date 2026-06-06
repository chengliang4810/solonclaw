package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 跟踪当前来源键上由 send_message 发起的回送，避免最终答复重复发送。 */
public final class MessageDeliveryTracker {
    /** TTLMILLIS的统一常量值。 */
    private static final long TTL_MILLIS = 5 * 60 * 1000L;

    /** RECORDS的统一常量值。 */
    private static final Map<String, DeliveryEchoRecord> RECORDS =
            new ConcurrentHashMap<String, DeliveryEchoRecord>();

    /** 创建消息投递Tracker实例。 */
    private MessageDeliveryTracker() {}

    /**
     * 记录Echo。
     *
     * @param sourceKey 渠道来源键。
     * @param sourcePlatform 来源平台参数。
     * @param sourceChatId 来源聊天标识。
     * @param targetPlatform target平台参数。
     * @param targetChatId target聊天标识。
     * @param text 待处理文本。
     * @param hasAttachments hasAttachments 参数。
     */
    public static void recordEcho(
            String sourceKey,
            PlatformType sourcePlatform,
            String sourceChatId,
            PlatformType targetPlatform,
            String targetChatId,
            String text,
            boolean hasAttachments) {
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
        if (!hasAttachments) {
            return;
        }
        pruneExpired();
        DeliveryEchoRecord record = new DeliveryEchoRecord();
        record.sourceKey = sourceKey;
        record.normalizedText = normalize(text);
        record.hasAttachments = true;
        record.createdAt = System.currentTimeMillis();
        RECORDS.put(sourceKey, record);
    }

    /**
     * 消费Duplicate最终回复。
     *
     * @param sourceKey 渠道来源键。
     * @param finalReply 最终回复参数。
     * @return 返回consume Duplicate Final Reply结果。
     */
    public static boolean consumeDuplicateFinalReply(String sourceKey, String finalReply) {
        if (StrUtil.isBlank(sourceKey)) {
            return false;
        }
        pruneExpired();
        DeliveryEchoRecord record = RECORDS.get(sourceKey);
        if (record == null || !record.hasAttachments) {
            return false;
        }
        String normalizedReply = normalize(finalReply);
        if (normalizedReply.length() == 0) {
            return false;
        }
        if (!normalizedReply.equals(record.normalizedText)) {
            return false;
        }
        RECORDS.remove(sourceKey);
        return true;
    }

    /** 执行pruneExpired相关逻辑。 */
    private static void pruneExpired() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, DeliveryEchoRecord> entry : RECORDS.entrySet()) {
            if (now - entry.getValue().createdAt >= TTL_MILLIS) {
                RECORDS.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * 执行规范化相关逻辑。
     *
     * @param text 待处理文本。
     * @return 返回规范化结果。
     */
    private static String normalize(String text) {
        return StrUtil.nullToEmpty(text)
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** 表示投递Echo数据，在服务、仓储和接口之间传递。 */
    private static class DeliveryEchoRecord {
        /** 记录投递Echo中的来源键。 */
        private String sourceKey;

        /** 记录投递Echo中的normalized文本。 */
        private String normalizedText;

        /** 标记是否附件。 */
        private boolean hasAttachments;

        /** 记录投递Echo中的创建时间。 */
        private long createdAt;
    }
}
