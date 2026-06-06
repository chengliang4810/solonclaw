package com.jimuqu.solon.claw.llm;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import java.util.ArrayList;
import java.util.List;
import org.noear.solon.ai.chat.message.ChatMessage;

/**
 * Prompt Cache 布局策略。
 *
 * <p>根据配置的 layout 决定哪些消息应标记为 cache 断点。
 */
public class PromptCachePolicy {

    /** Cache 布局枚举。 */
    public enum Layout {
        /** 仅缓存 system 消息。 */
        SYSTEM_ONLY,
        /** 缓存 system 消息及最近 3 条用户/助手消息。 */
        SYSTEM_AND_3,
        /** 缓存全部消息。 */
        FULL;

        /** 从配置字符串解析 Layout，未知值默认 SYSTEM_AND_3。 */
        public static Layout fromConfig(String value) {
            String normalized = StrUtil.nullToEmpty(value).trim().toLowerCase();
            if ("system_only".equals(normalized)) {
                return SYSTEM_ONLY;
            }
            if ("full".equals(normalized)) {
                return FULL;
            }
            return SYSTEM_AND_3;
        }
    }

    /** 标记该配置项或记录是否处于启用状态。 */
    private final boolean enabled;

    /** 记录提示词缓存中的layout。 */
    private final Layout layout;

    /**
     * 创建提示词缓存策略实例，并注入运行所需依赖。
     *
     * @param config 当前模块使用的配置对象。
     */
    public PromptCachePolicy(AppConfig.PromptCacheConfig config) {
        this.enabled = config != null && config.isEnabled();
        this.layout = config == null ? Layout.SYSTEM_AND_3 : Layout.fromConfig(config.getLayout());
    }

    /** 是否启用 prompt cache。 */
    public boolean isEnabled() {
        return enabled;
    }

    /** 当前 cache 布局。 */
    public Layout getLayout() {
        return layout;
    }

    /**
     * 根据布局策略，返回应标记为 cache 断点的消息索引列表。
     *
     * @param messages 当前会话消息列表
     * @return 需要标记 cache 的消息索引（0-based）
     */
    public List<Integer> resolveCacheBreakpoints(List<ChatMessage> messages) {
        List<Integer> breakpoints = new ArrayList<Integer>();
        if (!enabled || messages == null || messages.isEmpty()) {
            return breakpoints;
        }

        switch (layout) {
            case SYSTEM_ONLY:
                for (int i = 0; i < messages.size(); i++) {
                    ChatMessage msg = messages.get(i);
                    if (msg != null && "system".equals(msg.getRole())) {
                        breakpoints.add(i);
                    }
                }
                break;

            case FULL:
                for (int i = 0; i < messages.size(); i++) {
                    breakpoints.add(i);
                }
                break;

            case SYSTEM_AND_3:
            default:
                for (int i = 0; i < messages.size(); i++) {
                    ChatMessage msg = messages.get(i);
                    if (msg != null && "system".equals(msg.getRole())) {
                        breakpoints.add(i);
                    }
                }
                int nonSystemCount = 0;
                for (int i = messages.size() - 1; i >= 0; i--) {
                    ChatMessage msg = messages.get(i);
                    if (msg == null || "system".equals(msg.getRole())) {
                        continue;
                    }
                    if (nonSystemCount < 3) {
                        if (!breakpoints.contains(i)) {
                            breakpoints.add(i);
                        }
                        nonSystemCount++;
                    }
                }
                break;
        }

        return breakpoints;
    }
}
