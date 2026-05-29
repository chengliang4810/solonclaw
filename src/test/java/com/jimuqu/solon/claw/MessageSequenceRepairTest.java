package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.storage.session.SqliteAgentSession;
import com.jimuqu.solon.claw.support.MessageSupport;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.tool.ToolCall;

/** 对齐 Jimuqu 请求前消息序列修复，避免孤儿 tool 消息触发空响应循环。 */
public class MessageSequenceRepairTest {
    @Test
    void shouldDropStrayToolWithUnknownToolCallId() {
        List<ChatMessage> messages =
                new ArrayList<ChatMessage>(
                        Arrays.asList(
                                ChatMessage.ofUser("hi"),
                                ChatMessage.ofAssistant("hello"),
                                ChatMessage.ofTool("stray", "shell", "orphan"),
                                ChatMessage.ofUser("real")));

        int repairs = MessageSupport.repairMessageSequence(messages);

        assertThat(repairs).isEqualTo(1);
        assertThat(messages).hasSize(3);
        assertThat(messages).allMatch(message -> message.getRole() != ChatRole.TOOL);
    }

    @Test
    void shouldPreserveValidAssistantToolAndUserRedirectSequence() {
        AssistantMessage assistant = assistantWithToolCall("call_1", "shell");
        List<ChatMessage> messages =
                new ArrayList<ChatMessage>(
                        Arrays.asList(
                                ChatMessage.ofUser("run"),
                                assistant,
                                ChatMessage.ofTool("done", "shell", "call_1"),
                                ChatMessage.ofUser("continue another way")));

        int repairs = MessageSupport.repairMessageSequence(messages);

        assertThat(repairs).isEqualTo(0);
        assertThat(messages)
                .extracting(ChatMessage::getRole)
                .containsExactly(
                        ChatRole.USER, ChatRole.ASSISTANT, ChatRole.TOOL, ChatRole.USER);
    }

    @Test
    void shouldMergeConsecutivePlainTextUserMessages() {
        List<ChatMessage> messages =
                new ArrayList<ChatMessage>(
                        Arrays.asList(ChatMessage.ofUser("first"), ChatMessage.ofUser("second")));

        int repairs = MessageSupport.repairMessageSequence(messages);

        assertThat(repairs).isEqualTo(1);
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getRole()).isEqualTo(ChatRole.USER);
        assertThat(messages.get(0).getContent()).isEqualTo("first\n\nsecond");
    }

    @Test
    void shouldNotMergeUserMessagesWithMetadataOrMultipleBlocks() {
        ChatMessage withMetadata = ChatMessage.ofUser("first").addMetadata("attachment", "file.txt");
        ChatMessage multiBlock =
                ChatMessage.ofUser(
                        "second",
                        Arrays.asList(TextBlock.of("second"), TextBlock.of("extra")));
        List<ChatMessage> messages =
                new ArrayList<ChatMessage>(Arrays.asList(withMetadata, multiBlock));

        int repairs = MessageSupport.repairMessageSequence(messages);

        assertThat(repairs).isEqualTo(0);
        assertThat(messages).hasSize(2);
    }

    @Test
    void shouldRepairPersistedHistoryWhenLoadingSqliteAgentSession() throws Exception {
        SessionRecord record = new SessionRecord();
        record.setSessionId("session-repair");
        record.setSourceKey("MEMORY:room:user");
        record.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofUser("first"),
                                ChatMessage.ofAssistant("hello"),
                                ChatMessage.ofTool("stray", "shell", "orphan"),
                                ChatMessage.ofUser("second"))));

        SqliteAgentSession agentSession = new SqliteAgentSession(record);

        assertThat(agentSession.getMessages()).hasSize(3);
        assertThat(agentSession.getMessages().get(0).getContent()).isEqualTo("first");
        assertThat(agentSession.getMessages().get(1).getContent()).isEqualTo("hello");
        assertThat(agentSession.getMessages().get(2).getContent()).isEqualTo("second");
    }

    private static AssistantMessage assistantWithToolCall(String id, String name) {
        return new AssistantMessage(
                "",
                false,
                null,
                null,
                Collections.singletonList(
                        new ToolCall(
                                "0",
                                id,
                                name,
                                "{}",
                                Collections.<String, Object>emptyMap())),
                null);
    }
}
