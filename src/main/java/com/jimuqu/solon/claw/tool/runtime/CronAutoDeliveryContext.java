package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Cron 自动投递上下文，用于避免 Agent 在同一目标重复调用 send_message。 */
public final class CronAutoDeliveryContext {
    /** 当前的统一常量值。 */
    private static final ThreadLocal<List<Target>> CURRENT =
            new InheritableThreadLocal<List<Target>>();

    /** 创建定时任务Auto投递上下文实例。 */
    private CronAutoDeliveryContext() {}

    /**
     * 执行set相关逻辑。
     *
     * @param platform 平台参数。
     * @param chatId 聊天标识。
     * @param threadId thread标识。
     */
    public static void set(PlatformType platform, String chatId, String threadId) {
        if (platform == null || StrUtil.isBlank(chatId)) {
            clear();
            return;
        }
        CURRENT.set(
                Collections.singletonList(
                        new Target(platform, chatId.trim(), normalizeBlank(threadId))));
    }

    /**
     * 写入全部。
     *
     * @param targets targets 参数。
     */
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

    /** 执行clear相关逻辑。 */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * 执行当前Targets相关逻辑。
     *
     * @return 返回当前Targets结果。
     */
    public static List<Target> currentTargets() {
        List<Target> targets = CURRENT.get();
        return targets == null ? Collections.<Target>emptyList() : targets;
    }

    /**
     * 执行matchingTarget相关逻辑。
     *
     * @param platform 平台参数。
     * @param chatId 聊天标识。
     * @param threadId thread标识。
     * @return 返回matching Target结果。
     */
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

    /**
     * 规范化Blank。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回Blank结果。
     */
    private static String normalizeBlank(String value) {
        String text = StrUtil.nullToEmpty(value).trim();
        return text.length() == 0 ? null : text;
    }

    /** 承载Target相关状态和辅助逻辑。 */
    public static final class Target {
        /** 记录Target中的平台。 */
        private final PlatformType platform;

        /** 记录Target中的聊天标识。 */
        private final String chatId;

        /** 记录Target中的thread标识。 */
        private final String threadId;

        /**
         * 创建Target实例，并注入运行所需依赖。
         *
         * @param platform 平台参数。
         * @param chatId 聊天标识。
         * @param threadId thread标识。
         */
        public Target(PlatformType platform, String chatId, String threadId) {
            this.platform = platform;
            this.chatId = StrUtil.nullToEmpty(chatId).trim();
            this.threadId = normalizeBlank(threadId);
        }

        /**
         * 读取平台。
         *
         * @return 返回读取到的平台。
         */
        public PlatformType getPlatform() {
            return platform;
        }

        /**
         * 读取Chat标识。
         *
         * @return 返回读取到的Chat标识。
         */
        public String getChatId() {
            return chatId;
        }

        /**
         * 读取Thread标识。
         *
         * @return 返回读取到的Thread标识。
         */
        public String getThreadId() {
            return threadId;
        }

        /**
         * 执行label相关逻辑。
         *
         * @return 返回label结果。
         */
        public String label() {
            return platform.name() + ":" + chatId + (threadId == null ? "" : ":" + threadId);
        }
    }
}
