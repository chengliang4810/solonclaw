package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Cron 自动投递上下文，用于避免 Agent 在同一目标重复调用 send_message。 */
public final class CronAutoDeliveryContext {
    private static final ThreadLocal<List<Target>> CURRENT =
            new InheritableThreadLocal<List<Target>>();

    private CronAutoDeliveryContext() {}

    public static void set(PlatformType platform, String chatId, String threadId) {
        if (platform == null || StrUtil.isBlank(chatId)) {
            clear();
            return;
        }
        CURRENT.set(
                Collections.singletonList(new Target(platform, chatId.trim(), normalizeBlank(threadId))));
    }

    public static void setAll(List<Target> targets) {
        if (targets == null || targets.isEmpty()) {
            clear();
            return;
        }
        List<Target> normalized = new ArrayList<Target>();
        for (Target target : targets) {
            if (target != null && target.platform != null && StrUtil.isNotBlank(target.chatId)) {
                normalized.add(new Target(target.platform, target.chatId, target.threadId));
            }
        }
        if (normalized.isEmpty()) {
            clear();
        } else {
            CURRENT.set(Collections.unmodifiableList(normalized));
        }
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static Target current() {
        List<Target> targets = CURRENT.get();
        return targets == null || targets.isEmpty() ? null : targets.get(0);
    }

    public static List<Target> currentTargets() {
        List<Target> targets = CURRENT.get();
        return targets == null ? Collections.<Target>emptyList() : targets;
    }

    public static boolean isDuplicateTarget(PlatformType platform, String chatId, String threadId) {
        return matchingTarget(platform, chatId, threadId) != null;
    }

    public static Target matchingTarget(PlatformType platform, String chatId, String threadId) {
        if (platform == null || StrUtil.isBlank(chatId)) {
            return null;
        }
        List<Target> targets = CURRENT.get();
        if (targets == null || targets.isEmpty()) {
            return null;
        }
        String normalizedChatId = chatId.trim();
        String normalizedThreadId = normalizeBlank(threadId);
        for (Target target : targets) {
            if (target.platform == platform
                    && StrUtil.equals(target.chatId, normalizedChatId)
                    && StrUtil.equals(target.threadId, normalizedThreadId)) {
                return target;
            }
        }
        return null;
    }

    private static String normalizeBlank(String value) {
        String text = StrUtil.nullToEmpty(value).trim();
        return text.length() == 0 ? null : text;
    }

    public static final class Target {
        private final PlatformType platform;
        private final String chatId;
        private final String threadId;

        public Target(PlatformType platform, String chatId, String threadId) {
            this.platform = platform;
            this.chatId = StrUtil.nullToEmpty(chatId).trim();
            this.threadId = normalizeBlank(threadId);
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
