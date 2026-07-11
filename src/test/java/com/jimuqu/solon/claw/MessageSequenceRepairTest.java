package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.storage.session.SqliteAgentSession;
import com.jimuqu.solon.claw.support.MessageSupport;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
                .containsExactly(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.TOOL, ChatRole.USER);
    }

    @Test
    void shouldRemoveAssistantToolCallsWithoutFollowingToolResults() {
        AssistantMessage assistant = assistantWithToolCalls("Plan", "call_kept", "call_orphan");
        List<ChatMessage> messages =
                new ArrayList<ChatMessage>(
                        Arrays.asList(
                                ChatMessage.ofUser("run"),
                                assistant,
                                ChatMessage.ofTool("done", "shell", "call_kept"),
                                ChatMessage.ofUser("continue")));

        int repairs = MessageSupport.repairMessageSequence(messages);

        assertThat(repairs).isEqualTo(1);
        AssistantMessage repaired = (AssistantMessage) messages.get(1);
        assertThat(repaired.getToolCalls())
                .extracting(ToolCall::getId)
                .containsExactly("call_kept");
        assertThat(repaired.getToolCallsRaw())
                .extracting(raw -> raw.get("id"))
                .containsExactly("call_kept");
        assertThat(messages)
                .extracting(ChatMessage::getRole)
                .containsExactly(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.TOOL, ChatRole.USER);
    }

    @Test
    void shouldDropThinkingOnlyAssistantWhenToolCallsArePruned() {
        AssistantMessage duplicate = assistantWithToolCalls("<think>重复的工具计划</think>\n\n", "call_1");
        List<ChatMessage> messages =
                new ArrayList<ChatMessage>(
                        Arrays.asList(
                                ChatMessage.ofUser("run"),
                                assistantWithToolCalls("<think>有效工具计划</think>\n\n", "call_1"),
                                duplicate,
                                ChatMessage.ofTool("done", "todo", "call_1"),
                                ChatMessage.ofUser("continue")));

        int repairs = MessageSupport.repairMessageSequence(messages);

        assertThat(repairs).isGreaterThanOrEqualTo(1);
        assertThat(messages)
                .extracting(ChatMessage::getRole)
                .containsExactly(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.TOOL, ChatRole.USER);
        assertThat(messages)
                .noneMatch(
                        message ->
                                message instanceof AssistantMessage
                                        && ((AssistantMessage) message).getToolCalls() == null
                                        && ((AssistantMessage) message)
                                                .getResultContent()
                                                .isEmpty());
    }

    @Test
    void shouldDemoteSignedThinkingWhenPruningAssistantToolCalls() {
        AssistantMessage assistant =
                assistantWithToolCalls(
                        "<think>\n\nPlan: call both tools.</think>\n\nVisible note",
                        "call_kept",
                        "call_orphan");
        Map<String, Object> raw = new LinkedHashMap<String, Object>();
        raw.put("thinking", "Plan: call both tools.");
        raw.put("thinkingSignature", "sig_dead");
        assistant =
                new AssistantMessage(
                        assistant.getContent(),
                        false,
                        raw,
                        assistant.getToolCallsRaw(),
                        assistant.getToolCalls(),
                        null);
        List<ChatMessage> messages =
                new ArrayList<ChatMessage>(
                        Arrays.asList(
                                ChatMessage.ofUser("run"),
                                assistant,
                                ChatMessage.ofTool("done", "shell", "call_kept")));

        int repairs = MessageSupport.repairMessageSequence(messages);

        assertThat(repairs).isEqualTo(1);
        AssistantMessage repaired = (AssistantMessage) messages.get(1);
        assertThat(repaired.getReasoning()).isEmpty();
        assertThat(repaired.getContent())
                .contains("Plan: call both tools.")
                .contains("Visible note");
        assertThat(repaired.getContent()).doesNotContain("<think>").doesNotContain("</think>");
        assertThat(repaired.getContentRaw()).isNotInstanceOf(Map.class);
        assertThat(repaired.getToolCalls())
                .extracting(ToolCall::getId)
                .containsExactly("call_kept");
    }

    @Test
    void shouldKeepSignedThinkingWhenAllAssistantToolCallsHaveResults() {
        Map<String, Object> raw = new LinkedHashMap<String, Object>();
        raw.put("thinking", "Valid plan.");
        raw.put("thinkingSignature", "sig_live");
        AssistantMessage assistant =
                new AssistantMessage(
                        "<think>\n\nValid plan.</think>\n\n",
                        false,
                        raw,
                        rawToolCalls("call_1"),
                        toolCalls("call_1"),
                        null);
        List<ChatMessage> messages =
                new ArrayList<ChatMessage>(
                        Arrays.asList(
                                ChatMessage.ofUser("run"),
                                assistant,
                                ChatMessage.ofTool("done", "shell", "call_1")));

        int repairs = MessageSupport.repairMessageSequence(messages);

        assertThat(repairs).isEqualTo(0);
        AssistantMessage preserved = (AssistantMessage) messages.get(1);
        assertThat(preserved.getReasoning()).isEqualTo("Valid plan.");
        assertThat(((Map<?, ?>) preserved.getContentRaw()).get("thinkingSignature"))
                .isEqualTo("sig_live");
        assertThat(preserved.getToolCalls()).extracting(ToolCall::getId).containsExactly("call_1");
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
    void shouldDropEmptyAssistantWithoutContentReasoningOrToolCalls() {
        List<ChatMessage> messages =
                new ArrayList<ChatMessage>(
                        Arrays.asList(
                                ChatMessage.ofUser("first"),
                                ChatMessage.ofAssistant(""),
                                ChatMessage.ofUser("second")));

        int repairs = MessageSupport.repairMessageSequence(messages);

        assertThat(repairs).isEqualTo(2);
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getRole()).isEqualTo(ChatRole.USER);
        assertThat(messages.get(0).getContent()).isEqualTo("first\n\nsecond");
    }

    @Test
    void shouldDropPureThinkingAssistantAfterToolCallPrune() {
        AssistantMessage duplicateToolCall =
                assistantWithToolCalls("<think>\n\n只记录工具调用计划。</think>\n\n", "call_duplicated");
        List<ChatMessage> messages =
                new ArrayList<ChatMessage>(
                        Arrays.asList(
                                ChatMessage.ofUser("run"),
                                duplicateToolCall,
                                assistantWithToolCalls(
                                        "<think>\n\n只记录工具调用计划。</think>\n\n", "call_duplicated"),
                                ChatMessage.ofTool("done", "session_search", "call_duplicated"),
                                ChatMessage.ofUser("continue")));

        int repairs = MessageSupport.repairMessageSequence(messages);

        assertThat(repairs).isEqualTo(1);
        assertThat(messages)
                .extracting(ChatMessage::getRole)
                .containsExactly(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.TOOL, ChatRole.USER);
        assertThat(((AssistantMessage) messages.get(1)).getToolCalls())
                .extracting(ToolCall::getId)
                .containsExactly("call_duplicated");
    }

    @Test
    void shouldDropAdjacentDuplicateAssistantToolCallsBeforeToolResult() {
        List<ChatMessage> messages =
                new ArrayList<ChatMessage>(
                        Arrays.asList(
                                ChatMessage.ofUser("read status"),
                                assistantWithToolCalls(
                                        "<think>\n\n调用 session_search。</think>\n\n", "call_search"),
                                assistantWithToolCalls(
                                        "<think>\n\n调用 session_search。</think>\n\n", "call_search"),
                                ChatMessage.ofTool("found", "session_search", "call_search"),
                                ChatMessage.ofAssistant("done")));

        int repairs = MessageSupport.repairMessageSequence(messages);

        assertThat(repairs).isEqualTo(1);
        assertThat(messages)
                .extracting(ChatMessage::getRole)
                .containsExactly(
                        ChatRole.USER, ChatRole.ASSISTANT, ChatRole.TOOL, ChatRole.ASSISTANT);
        assertThat(((AssistantMessage) messages.get(1)).getToolCalls())
                .extracting(ToolCall::getId)
                .containsExactly("call_search");
    }

    @Test
    void shouldNotTreatDifferentToolCallIdsAsDuplicates() {
        List<ChatMessage> messages =
                new ArrayList<ChatMessage>(
                        Arrays.asList(
                                ChatMessage.ofUser("read twice"),
                                assistantWithToolCalls(
                                        "<think>\n\n第一次读取。</think>\n\n", "call_first"),
                                assistantWithToolCalls(
                                        "<think>\n\n第二次读取。</think>\n\n", "call_second")));

        int repairs = MessageSupport.repairMessageSequence(messages, true);

        assertThat(repairs).isEqualTo(0);
        assertThat(messages)
                .filteredOn(message -> message instanceof AssistantMessage)
                .extracting(message -> ((AssistantMessage) message).getToolCalls().get(0).getId())
                .containsExactly("call_first", "call_second");
    }

    @Test
    void shouldKeepAssistantWithThinkingAndVisibleContent() {
        List<ChatMessage> messages =
                new ArrayList<ChatMessage>(
                        Arrays.asList(
                                ChatMessage.ofUser("first"),
                                ChatMessage.ofAssistant("<think>\n\n计划。</think>\n\n可见答复"),
                                ChatMessage.ofUser("second")));

        int repairs = MessageSupport.repairMessageSequence(messages);

        assertThat(repairs).isEqualTo(0);
        assertThat(messages)
                .extracting(ChatMessage::getRole)
                .containsExactly(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER);
    }

    @Test
    void shouldKeepAssistantWithToolCallsEvenWhenVisibleContentIsEmpty() {
        AssistantMessage assistant = assistantWithToolCall("call_1", "shell");
        List<ChatMessage> messages =
                new ArrayList<ChatMessage>(
                        Arrays.asList(
                                ChatMessage.ofUser("run"),
                                assistant,
                                ChatMessage.ofTool("done", "shell", "call_1")));

        int repairs = MessageSupport.repairMessageSequence(messages);

        assertThat(repairs).isEqualTo(0);
        assertThat(messages)
                .extracting(ChatMessage::getRole)
                .containsExactly(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.TOOL);
    }

    @Test
    void shouldNotMergeUserMessagesWithMetadataOrMultipleBlocks() {
        ChatMessage withMetadata =
                ChatMessage.ofUser("first").addMetadata("attachment", "file.txt");
        ChatMessage multiBlock =
                ChatMessage.ofUser(
                        "second", Arrays.asList(TextBlock.of("second"), TextBlock.of("extra")));
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

    @Test
    void shouldLoadUtf8NdjsonWithoutDefaultCharsetRoundTrip() throws Exception {
        String content =
                "<think>\n\n"
                        + "\u8bfb\u53d6\u5305\u542b\u4e2d\u6587\u8def\u5f84\u4e0e\u8f6c\u4e49 JSON \u7684\u5de5\u5177\u53c2\u6570\u3002"
                        + "</think>\n\n"
                        + "\u53c2\u6570={\"path\":\"workspace/logs/\u4e01\\\"loop.json\"}";
        AssistantMessage assistant =
                new AssistantMessage(
                        content,
                        false,
                        null,
                        rawToolCalls("call_utf8"),
                        toolCalls("call_utf8"),
                        null);
        String ndjson =
                MessageSupport.toNdjson(
                        Arrays.asList(
                                assistant,
                                ChatMessage.ofTool(
                                        "{\"status\":\"success\",\"preview\":\"\u957f\u671f\u56de\u5f52\"}",
                                        "read_file",
                                        "call_utf8")));

        String misread =
                new String(ndjson.getBytes(StandardCharsets.UTF_8), Charset.forName("GBK"));
        assertThat(ChatMessage.fromJson(ndjson.split("\\R", -1)[0]).getContent())
                .contains("\u4e01\\\"");
        assertThatThrownBy(() -> ChatMessage.fromJson(misread.split("\\R", -1)[0]))
                .hasMessageContaining("Expected");

        List<ChatMessage> messages = MessageSupport.loadMessages(ndjson);

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).isInstanceOf(AssistantMessage.class);
        assertThat(messages.get(0).getContent()).contains("\u4e01\\\"loop.json");
        assertThat(((AssistantMessage) messages.get(0)).getToolCalls())
                .extracting(ToolCall::getId)
                .containsExactly("call_utf8");
        assertThat(messages.get(1).getContent()).contains("\u957f\u671f\u56de\u5f52");
    }

    @Test
    void shouldLoadToolMessageWithEscapedJsonContent() throws Exception {
        String ndjson =
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofUser("创建 todo"),
                                ChatMessage.ofTool(
                                        "{\"status\":\"success\",\"preview\":\"{\\\"total\\\":3}\"}",
                                        "todo",
                                        "call_todo")));

        List<ChatMessage> messages = MessageSupport.loadMessages(ndjson);

        assertThat(messages).hasSize(2);
        assertThat(messages.get(1)).isInstanceOf(org.noear.solon.ai.chat.message.ToolMessage.class);
        assertThat(messages.get(1).getContent()).contains("\"status\":\"success\"");
        org.noear.solon.ai.chat.message.ToolMessage tool =
                (org.noear.solon.ai.chat.message.ToolMessage) messages.get(1);
        assertThat(tool.getName()).isEqualTo("todo");
        assertThat(tool.getToolCallId()).isEqualTo("call_todo");
    }

    private static AssistantMessage assistantWithToolCall(String id, String name) {
        return new AssistantMessage(
                "",
                false,
                null,
                null,
                Collections.singletonList(
                        new ToolCall("0", id, name, "{}", Collections.<String, Object>emptyMap())),
                null);
    }

    private static AssistantMessage assistantWithToolCalls(String content, String... ids) {
        return new AssistantMessage(content, false, null, rawToolCalls(ids), toolCalls(ids), null);
    }

    private static List<ToolCall> toolCalls(String... ids) {
        List<ToolCall> calls = new ArrayList<ToolCall>();
        for (String id : ids) {
            calls.add(new ToolCall(id, id, "shell", "{}", Collections.<String, Object>emptyMap()));
        }
        return calls;
    }

    private static List<Map> rawToolCalls(String... ids) {
        List<Map> rawCalls = new ArrayList<Map>();
        for (String id : ids) {
            Map<String, Object> function = new HashMap<String, Object>();
            function.put("name", "shell");
            function.put("arguments", "{}");
            Map<String, Object> raw = new HashMap<String, Object>();
            raw.put("id", id);
            raw.put("type", "function");
            raw.put("function", function);
            rawCalls.add(raw);
        }
        return rawCalls;
    }
}
