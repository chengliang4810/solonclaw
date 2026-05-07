package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.enums.PlatformType;

/** Cron 自动投递上下文，用于避免 Agent 在同一目标重复调用 send_message。 */
public final class CronAutoDeliveryContext {
    private static final ThreadLocal<Target> CURRENT = new InheritableThreadLocal<Target>();

    private CronAutoDeliveryContext() {}

    public static void set(PlatformType platform, String chatId, String threadId) {
        if (platform == null || StrUtil.isBlank(chatId)) {
            clear();
            return;
        }
        CURRENT.set(new Target(platform, chatId.trim(), normalizeBlank(threadId)));
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static Target current() {
        return CURRENT.get();
    }

    public static boolean isDuplicateTarget(PlatformType platform, String chatId, String threadId) {
        Target target = current();
        if (target == null || platform == null || StrUtil.isBlank(chatId)) {
            return false;
        }
        return target.platform == platform
                && StrUtil.equals(target.chatId, chatId.trim())
                && StrUtil.equals(target.threadId, normalizeBlank(threadId));
    }

    private static String normalizeBlank(String value) {
        String text = StrUtil.nullToEmpty(value).trim();
        return text.length() == 0 ? null : text;
    }

    public static final class Target {
        private final PlatformType platform;
        private final String chatId;
        private final String threadId;

        private Target(PlatformType platform, String chatId, String threadId) {
            this.platform = platform;
            this.chatId = chatId;
            this.threadId = threadId;
        }

        public PlatformType getPlatform() {
            return platform;
        }

        public String getChatId() {
            return chatId;
        }

        public String getThreadId() {
            return threadId;
        }

        public String label() {
            return platform.name() + ":" + chatId + (threadId == null ? "" : ":" + threadId);
        }
    }
}
