package com.jimuqu.solon.claw.core.model;

import com.jimuqu.solon.claw.support.MessageSupport;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.Getter;
import org.noear.solon.ai.chat.message.ChatMessage;

/** Completed-turn context passed to memory providers after a successful run. */
@Getter
public class MemoryTurnContext {
    private final String sourceKey;
    private final String sessionId;
    private final String userMessage;
    private final String assistantMessage;
    private final String conversationNdjson;
    private final List<ChatMessage> messages;
    private final String provider;
    private final String model;
    private final boolean streamed;
    private final long inputTokens;
    private final long outputTokens;
    private final long reasoningTokens;
    private final long cacheReadTokens;
    private final long cacheWriteTokens;
    private final long totalTokens;

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

    public static Builder builder() {
        return new Builder();
    }

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

    public static class Builder {
        private String sourceKey;
        private String sessionId;
        private String userMessage;
        private String assistantMessage;
        private String conversationNdjson;
        private List<ChatMessage> messages;
        private String provider;
        private String model;
        private boolean streamed;
        private long inputTokens;
        private long outputTokens;
        private long reasoningTokens;
        private long cacheReadTokens;
        private long cacheWriteTokens;
        private long totalTokens;

        public Builder sourceKey(String sourceKey) {
            this.sourceKey = sourceKey;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder userMessage(String userMessage) {
            this.userMessage = userMessage;
            return this;
        }

        public Builder assistantMessage(String assistantMessage) {
            this.assistantMessage = assistantMessage;
            return this;
        }

        public Builder conversationNdjson(String conversationNdjson) {
            this.conversationNdjson = conversationNdjson;
            return this;
        }

        public Builder messages(List<ChatMessage> messages) {
            this.messages = messages == null ? null : new ArrayList<ChatMessage>(messages);
            return this;
        }

        public Builder provider(String provider) {
            this.provider = provider;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder streamed(boolean streamed) {
            this.streamed = streamed;
            return this;
        }

        public Builder inputTokens(long inputTokens) {
            this.inputTokens = inputTokens;
            return this;
        }

        public Builder outputTokens(long outputTokens) {
            this.outputTokens = outputTokens;
            return this;
        }

        public Builder reasoningTokens(long reasoningTokens) {
            this.reasoningTokens = reasoningTokens;
            return this;
        }

        public Builder cacheReadTokens(long cacheReadTokens) {
            this.cacheReadTokens = cacheReadTokens;
            return this;
        }

        public Builder cacheWriteTokens(long cacheWriteTokens) {
            this.cacheWriteTokens = cacheWriteTokens;
            return this;
        }

        public Builder totalTokens(long totalTokens) {
            this.totalTokens = totalTokens;
            return this;
        }

        public MemoryTurnContext build() {
            return new MemoryTurnContext(this);
        }
    }
}
