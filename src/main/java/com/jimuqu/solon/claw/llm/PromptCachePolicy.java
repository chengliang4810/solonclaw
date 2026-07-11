package com.jimuqu.solon.claw.llm;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import java.util.ArrayList;
import java.util.List;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.message.ChatMessage;

/**
 * Prompt Cache 布局策略。
 *
 * <p>根据配置的 layout 决定哪些消息应标记为 cache 断点。
 */
public class PromptCachePolicy {
    /** 在 Solon AI 请求选项中传递缓存策略的内部键，不会进入模型请求正文。 */
    public static final String TOOL_CONTEXT_KEY = "solonclaw.prompt-cache-policy";

    /** Anthropic 单次请求允许的最大缓存断点数。 */
    private static final int MAX_ANTHROPIC_BREAKPOINTS = 4;

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

    /** Anthropic 临时缓存时长，仅接受协议支持的 5m 或 1h。 */
    private final String ttl;

    /**
     * 创建提示词缓存策略实例，并注入运行所需依赖。
     *
     * @param config 当前模块使用的配置对象。
     */
    public PromptCachePolicy(AppConfig.PromptCacheConfig config) {
        this.enabled = config != null && config.isEnabled();
        this.layout = config == null ? Layout.SYSTEM_AND_3 : Layout.fromConfig(config.getLayout());
        this.ttl = config != null && "1h".equalsIgnoreCase(config.getTtl()) ? "1h" : "5m";
    }

    /** 是否启用 prompt cache。 */
    public boolean isEnabled() {
        return enabled;
    }

    /** 当前 cache 布局。 */
    public Layout getLayout() {
        return layout;
    }

    /** 返回 Anthropic 协议实际使用的缓存时长。 */
    public String getTtl() {
        return ttl;
    }

    /**
     * 将缓存断点写入 Solon AI 已生成的 Anthropic 请求。
     *
     * <p>Solon AI 官方 CacheControl 负责启用 Anthropic 临时缓存；这里仅补齐其尚未覆盖的最近三条消息与 1h TTL。
     *
     * @param request Anthropic 最终请求 JSON。
     */
    public void applyToAnthropicRequest(ONode request) {
        if (!enabled || request == null) {
            return;
        }

        int remaining = MAX_ANTHROPIC_BREAKPOINTS;
        if (markContent(request, "system")) {
            remaining--;
        }
        if (layout == Layout.SYSTEM_ONLY || remaining <= 0) {
            return;
        }

        ONode messagesNode = request.getOrNull("messages");
        if (messagesNode == null || !messagesNode.isArray()) {
            return;
        }
        List<ONode> messages = messagesNode.getArray();
        int limit = layout == Layout.FULL ? remaining : Math.min(3, remaining);
        for (int i = messages.size() - 1, marked = 0; i >= 0 && marked < limit; i--) {
            if (markContent(messages.get(i), "content")) {
                marked++;
            }
        }
    }

    /** 给字符串或内容块数组的最后一段添加缓存标记。 */
    private boolean markContent(ONode parent, String field) {
        ONode content = parent == null ? null : parent.getOrNull(field);
        if (content == null) {
            return false;
        }
        if (content.isArray()) {
            List<ONode> blocks = content.getArray();
            if (blocks.isEmpty()) {
                return false;
            }
            blocks.get(blocks.size() - 1).set("cache_control", marker());
            return true;
        }

        String text = content.getString();
        if (StrUtil.isBlank(text)) {
            return false;
        }
        ONode blocks = new ONode().asArray();
        blocks.addNew().set("type", "text").set("text", text).set("cache_control", marker());
        parent.set(field, blocks);
        return true;
    }

    /** 创建独立缓存标记，避免多个内容块共享可变 JSON 节点。 */
    private ONode marker() {
        ONode marker = new ONode().set("type", "ephemeral");
        if ("1h".equals(ttl)) {
            marker.set("ttl", "1h");
        }
        return marker;
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
