package com.jimuqu.solon.claw.support;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** 有界消息去重器，用于抑制渠道重投或网络抖动产生的重复入站消息。 */
public final class BoundedMessageDeduplicator {
    /** 默认去重窗口为五分钟。 */
    private static final long DEFAULT_TTL_MILLIS = 5L * 60L * 1000L;

    /** 默认最多保留 512 个消息标识。 */
    private static final int DEFAULT_MAX_ENTRIES = 512;

    /** 消息标识到最近接收时间的有序映射。 */
    private final LinkedHashMap<String, Long> seen =
            new LinkedHashMap<String, Long>(16, 0.75F, true);

    /** 创建使用默认窗口和容量的消息去重器。 */
    public BoundedMessageDeduplicator() {}

    /**
     * 记录消息标识并判断是否仍处于去重窗口内。
     *
     * @param messageId 渠道原始消息标识；空值不参与去重。
     * @return 同一标识仍在去重窗口内时返回 true。
     */
    public synchronized boolean isDuplicate(String messageId) {
        String normalized = messageId == null ? "" : messageId.trim();
        if (normalized.length() == 0) {
            return false;
        }
        long now = System.currentTimeMillis();
        pruneExpired(now);
        Long previous = seen.put(normalized, Long.valueOf(now));
        trimToCapacity();
        return previous != null && now - previous.longValue() < DEFAULT_TTL_MILLIS;
    }

    /** 清理超过去重窗口的历史消息标识。 */
    private void pruneExpired(long now) {
        Iterator<Map.Entry<String, Long>> iterator = seen.entrySet().iterator();
        while (iterator.hasNext()) {
            if (now - iterator.next().getValue().longValue() >= DEFAULT_TTL_MILLIS) {
                iterator.remove();
            }
        }
    }

    /** 从最早记录开始淘汰，确保消息标识表不会无限增长。 */
    private void trimToCapacity() {
        Iterator<String> iterator = seen.keySet().iterator();
        while (seen.size() > DEFAULT_MAX_ENTRIES && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }
}
