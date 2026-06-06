package com.jimuqu.solon.claw.core.model;

import com.jimuqu.solon.claw.support.MessageSupport;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.Getter;
import org.noear.solon.ai.chat.message.ChatMessage;

/** 承载记忆Turn上下文相关状态和辅助逻辑。 */
@Getter
public class MemoryTurnContext {
    /** 记录记忆Turn上下文中的来源键。 */
    private final String sourceKey;

    /** 记录记忆Turn上下文中的会话标识。 */
    private final String sessionId;

    /** 记录记忆Turn上下文中的用户消息。 */
    private final String userMessage;

    /** 记录记忆Turn上下文中的assistant消息。 */
    private final String assistantMessage;

    /** 记录记忆Turn上下文中的对话NDJSON。 */
    private final String conversationNdjson;

    /** 保存messages集合，维持调用顺序或去重语义。 */
    private final List<ChatMessage> messages;

    /** 记录记忆Turn上下文中的提供方。 */
    private final String provider;

    /** 记录记忆Turn上下文中的模型。 */
    private final String model;

    /** 是否启用streamed。 */
    private final boolean streamed;

    /** 记录记忆Turn上下文中的输入 token。 */
    private final long inputTokens;

    /** 记录记忆Turn上下文中的输出 token。 */
    private final long outputTokens;

    /** 记录记忆Turn上下文中的推理 token。 */
    private final long reasoningTokens;

    /** 记录记忆Turn上下文中的缓存读取 token。 */
    private final long cacheReadTokens;

    /** 记录记忆Turn上下文中的缓存写入 token。 */
    private final long cacheWriteTokens;

    /** 记录记忆Turn上下文中的totaltoken。 */
    private final long totalTokens;

    /**
     * 创建记忆Turn上下文实例，并注入运行所需依赖。
     *
     * @param builder 构建器参数。
     */
    private MemoryTurnContext(Builder builder) {
        this.sourceKey = builder.sourceKey;
        this.sessionId = builder.sessionId;
        this.userMessage = builder.userMessage;
        this.assistantMessage = builder.assistantMessage;
        this.conversationNdjson = builder.conversationNdjson;
        this.messages = immutableMessages(builder.messages, builder.conversationNdjson);
        this.provider = builder.provider;
        this.model = builder.model;
        this.streamed = builder.streamed;
        this.inputTokens = builder.inputTokens;
        this.outputTokens = builder.outputTokens;
        this.reasoningTokens = builder.reasoningTokens;
        this.cacheReadTokens = builder.cacheReadTokens;
        this.cacheWriteTokens = builder.cacheWriteTokens;
        this.totalTokens = builder.totalTokens;
    }

    /**
     * 创建当前类型的构建器。
     *
     * @return 返回builder结果。
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 执行immutableMessages相关逻辑。
     *
     * @param messages messages 参数。
     * @param conversationNdjson conversationNdjson 参数。
     * @return 返回immutable Messages结果。
     */
    private static List<ChatMessage> immutableMessages(
            List<ChatMessage> messages, String conversationNdjson) {
        List<ChatMessage> value = messages;
        if ((value == null || value.isEmpty()) && conversationNdjson != null) {
            try {
                value = MessageSupport.loadMessages(conversationNdjson);
            } catch (Exception ignored) {
                value = Collections.emptyList();
            }
        }
        if (value == null || value.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<ChatMessage>(value));
    }

    /** 承载构建器相关状态和辅助逻辑。 */
    public static class Builder {
        /** 记录构建器中的来源键。 */
        private String sourceKey;

        /** 记录构建器中的会话标识。 */
        private String sessionId;

        /** 记录构建器中的用户消息。 */
        private String userMessage;

        /** 记录构建器中的assistant消息。 */
        private String assistantMessage;

        /** 记录构建器中的对话NDJSON。 */
        private String conversationNdjson;

        /** 保存messages集合，维持调用顺序或去重语义。 */
        private List<ChatMessage> messages;

        /** 记录构建器中的提供方。 */
        private String provider;

        /** 记录构建器中的模型。 */
        private String model;

        /** 是否启用streamed。 */
        private boolean streamed;

        /** 记录构建器中的输入 token。 */
        private long inputTokens;

        /** 记录构建器中的输出 token。 */
        private long outputTokens;

        /** 记录构建器中的推理 token。 */
        private long reasoningTokens;

        /** 记录构建器中的缓存读取 token。 */
        private long cacheReadTokens;

        /** 记录构建器中的缓存写入 token。 */
        private long cacheWriteTokens;

        /** 记录构建器中的totaltoken。 */
        private long totalTokens;

        /**
         * 执行来源键相关逻辑。
         *
         * @param sourceKey 渠道来源键。
         * @return 返回来源键结果。
         */
        public Builder sourceKey(String sourceKey) {
            this.sourceKey = sourceKey;
            return this;
        }

        /**
         * 执行会话标识相关逻辑。
         *
         * @param sessionId 当前会话标识。
         * @return 返回会话标识。
         */
        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        /**
         * 执行用户消息相关逻辑。
         *
         * @param userMessage 用户消息参数。
         * @return 返回用户消息结果。
         */
        public Builder userMessage(String userMessage) {
            this.userMessage = userMessage;
            return this;
        }

        /**
         * 执行assistant消息相关逻辑。
         *
         * @param assistantMessage assistant消息参数。
         * @return 返回assistant消息结果。
         */
        public Builder assistantMessage(String assistantMessage) {
            this.assistantMessage = assistantMessage;
            return this;
        }

        /**
         * 执行对话NDJSON相关逻辑。
         *
         * @param conversationNdjson conversationNdjson 参数。
         * @return 返回对话NDJSON结果。
         */
        public Builder conversationNdjson(String conversationNdjson) {
            this.conversationNdjson = conversationNdjson;
            return this;
        }

        /**
         * 执行messages相关逻辑。
         *
         * @param messages messages 参数。
         * @return 返回messages结果。
         */
        public Builder messages(List<ChatMessage> messages) {
            this.messages = messages == null ? null : new ArrayList<ChatMessage>(messages);
            return this;
        }

        /**
         * 执行提供方相关逻辑。
         *
         * @param provider 模型或能力提供方。
         * @return 返回提供方结果。
         */
        public Builder provider(String provider) {
            this.provider = provider;
            return this;
        }

        /**
         * 执行模型相关逻辑。
         *
         * @param model 模型名称。
         * @return 返回模型结果。
         */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /**
         * 执行streamed相关逻辑。
         *
         * @param streamed streamed 参数。
         * @return 返回streamed结果。
         */
        public Builder streamed(boolean streamed) {
            this.streamed = streamed;
            return this;
        }

        /**
         * 执行输入 token相关逻辑。
         *
         * @param inputTokens 输入 token 数。
         * @return 返回输入 token结果。
         */
        public Builder inputTokens(long inputTokens) {
            this.inputTokens = inputTokens;
            return this;
        }

        /**
         * 执行输出 token相关逻辑。
         *
         * @param outputTokens 输出 token 数。
         * @return 返回输出 token结果。
         */
        public Builder outputTokens(long outputTokens) {
            this.outputTokens = outputTokens;
            return this;
        }

        /**
         * 执行推理 token相关逻辑。
         *
         * @param reasoningTokens 推理 token 数。
         * @return 返回推理 token结果。
         */
        public Builder reasoningTokens(long reasoningTokens) {
            this.reasoningTokens = reasoningTokens;
            return this;
        }

        /**
         * 执行缓存读取 token相关逻辑。
         *
         * @param cacheReadTokens 缓存读取 token 数。
         * @return 返回缓存读取 token结果。
         */
        public Builder cacheReadTokens(long cacheReadTokens) {
            this.cacheReadTokens = cacheReadTokens;
            return this;
        }

        /**
         * 执行缓存写入 token相关逻辑。
         *
         * @param cacheWriteTokens 缓存写入 token 数。
         * @return 返回缓存写入 token结果。
         */
        public Builder cacheWriteTokens(long cacheWriteTokens) {
            this.cacheWriteTokens = cacheWriteTokens;
            return this;
        }

        /**
         * 执行totaltoken相关逻辑。
         *
         * @param totalTokens totaltoken参数。
         * @return 返回总 token结果。
         */
        public Builder totalTokens(long totalTokens) {
            this.totalTokens = totalTokens;
            return this;
        }

        /**
         * 构建当前对象并返回不可变结果。
         *
         * @return 返回build结果。
         */
        public MemoryTurnContext build() {
            return new MemoryTurnContext(this);
        }
    }
}
