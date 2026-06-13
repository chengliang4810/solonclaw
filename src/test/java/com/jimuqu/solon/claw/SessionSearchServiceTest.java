package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.model.AgentRunEventRecord;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.model.SessionSearchEntry;
import com.jimuqu.solon.claw.core.model.SessionSearchQuery;
import com.jimuqu.solon.claw.core.model.ToolCallRecord;
import com.jimuqu.solon.claw.support.FakeLlmGateway;
import com.jimuqu.solon.claw.core.service.SessionSearchService;
import com.jimuqu.solon.claw.support.IdSupport;
import com.jimuqu.solon.claw.support.MessageSupport;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.tool.runtime.SessionSearchTools;
import com.jimuqu.solon.claw.web.DashboardSearchController;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.ContextEmpty;

public class SessionSearchServiceTest {
    @Test
    void shouldListRecentSessionsAndExcludeCurrentSession() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        SessionRecord current = env.sessionRepository.bindNewSession("MEMORY:current-room:user");
        current.setTitle("current session");
        current.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofUser("current"),
                                ChatMessage.ofAssistant("current reply"))));
        env.sessionRepository.save(current);

        SessionRecord previous = env.sessionRepository.bindNewSession("MEMORY:history-room:user");
        previous.setTitle("history session");
        previous.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofUser("older"),
                                ChatMessage.ofAssistant("older reply"))));
        env.sessionRepository.save(previous);

        List<SessionSearchEntry> entries =
                env.sessionSearchService.search("MEMORY:current-room:user", "", 3);

        assertThat(entries)
                .extracting(SessionSearchEntry::getSessionId)
                .doesNotContain(current.getSessionId());
        assertThat(entries).extracting(SessionSearchEntry::getTitle).contains("history session");
    }

    @Test
    void shouldFoldDelegatedChildSessionsIntoParentAndAvoidPersistingSearchSummary()
            throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        SessionRecord current = env.sessionRepository.bindNewSession("MEMORY:current-room:user");
        current.setTitle("current");
        current.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofUser("current"),
                                ChatMessage.ofAssistant("current reply"))));
        env.sessionRepository.save(current);

        SessionRecord parent = env.sessionRepository.bindNewSession("MEMORY:history-room:user");
        parent.setTitle("parent session");
        parent.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofUser("setup context"),
                                ChatMessage.ofAssistant("setup done"))));
        env.sessionRepository.save(parent);

        SessionRecord child =
                env.sessionRepository.cloneSession(
                        "MEMORY:history-room:user", parent.getSessionId(), "delegate");
        child.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofUser("investigate bug-123"),
                                ChatMessage.ofAssistant("fixed bug-123 with file update"))));
        env.sessionRepository.save(child);

        String parentNdjson = env.sessionRepository.findById(parent.getSessionId()).getNdjson();
        String childNdjson = env.sessionRepository.findById(child.getSessionId()).getNdjson();

        List<SessionSearchEntry> entries =
                env.sessionSearchService.search("MEMORY:current-room:user", "bug-123", 3);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getSessionId()).isEqualTo(parent.getSessionId());
        assertThat(entries.get(0).getSummary()).isNotBlank();
        assertThat(env.sessionRepository.findById(parent.getSessionId()).getNdjson())
                .isEqualTo(parentNdjson);
        assertThat(env.sessionRepository.findById(child.getSessionId()).getNdjson())
                .isEqualTo(childNdjson);
    }

    @Test
    void shouldSearchToolNamesAndToolCalls() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        SessionRecord current = env.sessionRepository.bindNewSession("MEMORY:current-room:user");
        current.setTitle("current");
        current.setNdjson(MessageSupport.toNdjson(Arrays.asList(ChatMessage.ofUser("current"))));
        env.sessionRepository.save(current);

        SessionRecord previous = env.sessionRepository.bindNewSession("MEMORY:history-room:user");
        previous.setTitle("tool session");
        previous.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofUser("run command"),
                                assistantWithToolCall(
                                        "execute_shell", "{\"command\":\"git status\"}"))));
        env.sessionRepository.save(previous);

        List<SessionSearchEntry> entries =
                env.sessionSearchService.search("MEMORY:current-room:user", "execute_shell", 3);

        assertThat(entries).extracting(SessionSearchEntry::getTitle).contains("tool session");
    }

    @Test
    void shouldSearchChineseLongQueriesWithHyphenatedTaskNamesAcrossSources() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        SessionRecord current = env.sessionRepository.bindNewSession("FEISHU:fs-chat:fs-user");
        current.setTitle("current feishu");
        current.setNdjson(
                MessageSupport.toNdjson(Arrays.asList(ChatMessage.ofUser("在飞书里查微信创建的定时任务"))));
        env.sessionRepository.save(current);

        SessionRecord previous = env.sessionRepository.bindNewSession("WEIXIN:wx-chat:wx-user");
        previous.setTitle("关闭 BLOCKED: URL 安全策略阻止访问");
        previous.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofUser("创建 Sub2Api账号状态巡检-v2 定时任务"),
                                ChatMessage.ofAssistant("已创建 cron job Sub2Api账号状态巡检-v2 ACTIVE"))));
        env.sessionRepository.save(previous);

        List<SessionSearchEntry> entries =
                env.sessionSearchService.search(
                        "FEISHU:fs-chat:fs-user", "Sub2Api账号状态巡检-v2 定时任务 cron 微信渠道 停止 暂停", 5);

        assertThat(entries)
                .extracting(SessionSearchEntry::getSessionId)
                .contains(previous.getSessionId());
    }

    @Test
    void shouldExcludeCurrentLineageRootAndChildrenFromRecent() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        SessionRecord root = env.sessionRepository.bindNewSession("MEMORY:room:user");
        root.setTitle("root current");
        root.setNdjson(MessageSupport.toNdjson(Arrays.asList(ChatMessage.ofUser("root"))));
        env.sessionRepository.save(root);

        SessionRecord child =
                env.sessionRepository.cloneSession(
                        "MEMORY:room:user", root.getSessionId(), "child");
        child.setTitle("child current");
        child.setNdjson(MessageSupport.toNdjson(Arrays.asList(ChatMessage.ofUser("child"))));
        env.sessionRepository.save(child);
        env.sessionRepository.bindSource("MEMORY:room:user", child.getSessionId());

        SessionRecord other = env.sessionRepository.bindNewSession("MEMORY:other:user");
        other.setTitle("other");
        other.setNdjson(MessageSupport.toNdjson(Arrays.asList(ChatMessage.ofUser("other"))));
        env.sessionRepository.save(other);

        List<SessionSearchEntry> entries =
                env.sessionSearchService.search("MEMORY:room:user", "", 5);

        assertThat(entries).extracting(SessionSearchEntry::getTitle).contains("other");
        assertThat(entries)
                .extracting(SessionSearchEntry::getTitle)
                .doesNotContain("root current", "child current");
    }

    @Test
    void shouldRecallCurrentSessionCompressedSummaryWhenQueryMatchesHistoricalMarker()
            throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        SessionRecord current = env.sessionRepository.bindNewSession("MEMORY:long-loop:user");
        current.setSessionId("web-loop-current-session");
        current.setSourceKey("MEMORY:long-loop:user");
        current.setTitle("long loop current session");
        current.setCompressedSummary(
                "长期回归 Loop 已压缩摘要，历史 marker=web-loop-session-recovery-readback-20260613-1758");
        current.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofUser("压缩后的新一轮问题"),
                                ChatMessage.ofAssistant("继续执行下一步"))));
        env.sessionRepository.save(current);
        env.sessionRepository.bindSource("MEMORY:long-loop:user", current.getSessionId());

        SessionRecord previous = env.sessionRepository.bindNewSession("MEMORY:other-room:user");
        previous.setTitle("unrelated older session");
        previous.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(ChatMessage.ofUser("没有目标 marker 的历史会话"))));
        env.sessionRepository.save(previous);

        List<SessionSearchEntry> entries =
                env.sessionSearchService.search(
                        "MEMORY:long-loop:user",
                        "web-loop-session-recovery-readback-20260613-1758",
                        3);

        assertThat(entries)
                .extracting(SessionSearchEntry::getSessionId)
                .contains(current.getSessionId());
        assertThat(entries.get(0).getMatchPreview())
                .contains("web-loop-session-recovery-readback-20260613-1758");
        assertThat(entries.get(0).getMode()).isEqualTo("discovery");
        assertThat(entries.get(0).getMessageId()).isEqualTo("compressed_summary");
        assertThat(entries.get(0).getScore()).isGreaterThan(0L);
    }

    @Test
    void shouldPreferCompressedSummaryPreviewOverLaterDiagnosticMessages() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String marker = "web-loop-session-recovery-readback-20260613-1758";

        SessionRecord current = env.sessionRepository.bindNewSession("MEMORY:long-loop:user");
        current.setSessionId("web-loop-current-session");
        current.setSourceKey("MEMORY:long-loop:user");
        current.setTitle("long loop current session");
        current.setCompressedSummary("压缩摘要保留的真实历史 marker=" + marker + "，并记录 todo 读回成功。");
        current.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofUser("后续复盘再次提到 " + marker),
                                ChatMessage.ofAssistant("这是后续诊断文本，不应抢占压缩摘要锚点"))));
        env.sessionRepository.save(current);
        env.sessionRepository.bindSource("MEMORY:long-loop:user", current.getSessionId());

        List<SessionSearchEntry> entries =
                env.sessionSearchService.search("MEMORY:long-loop:user", marker, 3);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getSessionId()).isEqualTo(current.getSessionId());
        assertThat(entries.get(0).getMatchPreview()).contains("压缩摘要保留的真实历史 marker");
        assertThat(entries.get(0).getMatchPreview()).doesNotContain("后续复盘");
        assertThat(entries.get(0).getSnippet()).contains(marker);
        assertThat(entries.get(0).getMessageId()).isEqualTo("compressed_summary");
        assertThat(entries.get(0).getScore()).isGreaterThan(0L);
    }

    @Test
    void shouldPreferCurrentCompressedMarkerOverBroadFtsNoise()
            throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String marker = "web-loop-session-search-fastpath-recheck";

        SessionRecord current = env.sessionRepository.bindNewSession("MEMORY:long-loop:user");
        current.setSessionId("web-loop-compressed-current");
        current.setSourceKey("MEMORY:long-loop:user");
        current.setTitle("long loop compressed current");
        current.setCompressedSummary("压缩摘要保留历史 marker=" + marker + "，用于恢复 session_search 快速路径复测。");
        current.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofUser("压缩后的下一轮"),
                                ChatMessage.ofAssistant("继续验证"))));
        env.sessionRepository.save(current);
        env.sessionRepository.bindSource("MEMORY:long-loop:user", current.getSessionId());

        for (int i = 0; i < 6; i++) {
            SessionRecord noise =
                    env.sessionRepository.bindNewSession("MEMORY:noise-room-" + i + ":user");
            noise.setTitle("web loop session search fastpath noise " + i);
            noise.setNdjson(
                    MessageSupport.toNdjson(
                            Arrays.asList(
                                    ChatMessage.ofUser("web loop session search fastpath"),
                                    ChatMessage.ofAssistant("只有拆词命中，没有完整 marker"))));
            env.sessionRepository.save(noise);
        }

        List<SessionSearchEntry> entries =
                env.sessionSearchService.search("MEMORY:long-loop:user", marker, 5);

        assertThat(entries).isNotEmpty();
        assertThat(entries.get(0).getSessionId()).isEqualTo(current.getSessionId());
        assertThat(entries.get(0).getMatchPreview()).contains(marker);
        assertThat(entries.get(0).getMessageId()).isEqualTo("compressed_summary");
        assertThat(entries.get(0).getScore()).isGreaterThan(0L);
    }

    @Test
    void shouldSearchRealRunRecordsWhenRunIdIsProvided() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord session = env.sessionRepository.bindNewSession("MEMORY:run-room:user");
        session.setTitle("run backed session");
        env.sessionRepository.save(session);
        com.jimuqu.solon.claw.core.model.AgentRunRecord run =
                new com.jimuqu.solon.claw.core.model.AgentRunRecord();
        run.setRunId("run-search-1");
        run.setSessionId(session.getSessionId());
        run.setSourceKey("MEMORY:run-room:user");
        run.setStatus("success");
        run.setInputPreview("needle input");
        run.setFinalReplyPreview("needle output");
        run.setStartedAt(System.currentTimeMillis());
        run.setLastActivityAt(run.getStartedAt());
        env.agentRunRepository.saveRun(run);

        SessionSearchQuery query = new SessionSearchQuery();
        query.setRunId("run-search-1");
        query.setQuery("needle");
        query.setLimit(10);

        List<SessionSearchEntry> entries = env.sessionSearchService.search(query);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getRunId()).isEqualTo("run-search-1");
        assertThat(entries.get(0).getSessionId()).isEqualTo(session.getSessionId());
    }

    @Test
    void shouldSearchRealToolCallsWhenToolNameIsProvided() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord session = env.sessionRepository.bindNewSession("MEMORY:tool-room:user");
        session.setTitle("tool backed session");
        env.sessionRepository.save(session);
        com.jimuqu.solon.claw.core.model.AgentRunRecord run =
                new com.jimuqu.solon.claw.core.model.AgentRunRecord();
        run.setRunId("run-tool-1");
        run.setSessionId(session.getSessionId());
        run.setSourceKey("MEMORY:tool-room:user");
        run.setStatus("success");
        run.setStartedAt(System.currentTimeMillis());
        run.setLastActivityAt(run.getStartedAt());
        env.agentRunRepository.saveRun(run);
        ToolCallRecord toolCall = new ToolCallRecord();
        toolCall.setToolCallId(IdSupport.newId());
        toolCall.setRunId(run.getRunId());
        toolCall.setSessionId(session.getSessionId());
        toolCall.setSourceKey("MEMORY:tool-room:user");
        toolCall.setToolName("execute_shell");
        toolCall.setStatus("completed");
        toolCall.setArgsPreview("git status");
        toolCall.setResultPreview("clean");
        toolCall.setStartedAt(System.currentTimeMillis());
        env.agentRunRepository.saveToolCall(toolCall);

        SessionSearchQuery query = new SessionSearchQuery();
        query.setToolName("execute_shell");
        query.setQuery("git status");
        query.setLimit(10);

        List<SessionSearchEntry> entries = env.sessionSearchService.search(query);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getRunId()).isEqualTo(run.getRunId());
        assertThat(entries.get(0).getToolName()).isEqualTo("execute_shell");
    }

    @Test
    void shouldRedactResultRefsFromToolResultSearchIndex() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord session = env.sessionRepository.bindNewSession("MEMORY:tool-room:user");
        session.setTitle("tool backed session");
        env.sessionRepository.save(session);
        com.jimuqu.solon.claw.core.model.AgentRunRecord run =
                new com.jimuqu.solon.claw.core.model.AgentRunRecord();
        run.setRunId("run-tool-redact-1");
        run.setSessionId(session.getSessionId());
        run.setSourceKey("MEMORY:tool-room:user");
        run.setStatus("success");
        run.setStartedAt(System.currentTimeMillis());
        run.setLastActivityAt(run.getStartedAt());
        env.agentRunRepository.saveRun(run);
        ToolCallRecord toolCall = new ToolCallRecord();
        toolCall.setToolCallId(IdSupport.newId());
        toolCall.setRunId(run.getRunId());
        toolCall.setSessionId(session.getSessionId());
        toolCall.setSourceKey("MEMORY:tool-room:user");
        toolCall.setToolName("execute_shell");
        toolCall.setStatus("completed");
        toolCall.setArgsPreview("git status");
        toolCall.setResultPreview("clean");
        toolCall.setResultRef("/tmp/output-token=secret123-ghp_1234567890abcdef.txt");
        toolCall.setResultIndexable(true);
        toolCall.setStartedAt(System.currentTimeMillis());
        env.agentRunRepository.saveToolCall(toolCall);

        String metadata = readToolResultMetadata(env, run.getRunId());

        assertThat(metadata).contains("\"result_ref\":\"[REDACTED_PATH]\"");
        assertThat(metadata)
                .doesNotContain("secret123")
                .doesNotContain("ghp_1234567890abcdef")
                .doesNotContain("output-token");
    }

    @Test
    void shouldRedactToolCallPreviewsBeforeStorageAndSearchIndex() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord session = env.sessionRepository.bindNewSession("MEMORY:tool-secret:user");
        env.sessionRepository.save(session);
        com.jimuqu.solon.claw.core.model.AgentRunRecord run =
                new com.jimuqu.solon.claw.core.model.AgentRunRecord();
        run.setRunId("run-tool-secret-preview-1");
        run.setSessionId(session.getSessionId());
        run.setSourceKey("MEMORY:tool-secret:user");
        run.setStatus("success");
        run.setStartedAt(System.currentTimeMillis());
        run.setLastActivityAt(run.getStartedAt());
        env.agentRunRepository.saveRun(run);
        ToolCallRecord toolCall = new ToolCallRecord();
        toolCall.setToolCallId(IdSupport.newId());
        toolCall.setRunId(run.getRunId());
        toolCall.setSessionId(session.getSessionId());
        toolCall.setSourceKey("MEMORY:tool-secret:user");
        toolCall.setToolName("execute_shell");
        toolCall.setStatus("completed");
        toolCall.setArgsPreview("curl https://example.test?api_key=sk-toolarg-secret12345");
        toolCall.setResultPreview("Authorization: Bearer ghp_toolresultsecret12345");
        toolCall.setError("password=tool-error-secret");
        toolCall.setResultIndexable(true);
        toolCall.setStartedAt(System.currentTimeMillis());

        env.agentRunRepository.saveToolCall(toolCall);

        List<ToolCallRecord> stored = env.agentRunRepository.listToolCalls(run.getRunId());
        assertThat(stored).hasSize(1);
        String storedPayload =
                stored.get(0).getArgsPreview()
                        + "\n"
                        + stored.get(0).getResultPreview()
                        + "\n"
                        + stored.get(0).getError();
        assertThat(storedPayload)
                .contains("api_key=***")
                .contains("Bearer ***")
                .contains("password=***")
                .doesNotContain("sk-toolarg-secret12345")
                .doesNotContain("ghp_toolresultsecret12345")
                .doesNotContain("tool-error-secret");

        String fts = readRunEventFts(env, run.getRunId(), "tool.result");
        assertThat(fts)
                .contains("api_key=***")
                .contains("Bearer ***")
                .doesNotContain("sk-toolarg-secret12345")
                .doesNotContain("ghp_toolresultsecret12345");
    }

    @Test
    void shouldRedactEventSummariesAndMetadataFromRunSearchIndex() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord session = env.sessionRepository.bindNewSession("MEMORY:event-room:user");
        env.sessionRepository.save(session);
        com.jimuqu.solon.claw.core.model.AgentRunRecord run =
                new com.jimuqu.solon.claw.core.model.AgentRunRecord();
        run.setRunId("run-event-redact-1");
        run.setSessionId(session.getSessionId());
        run.setSourceKey("MEMORY:event-room:user");
        run.setStatus("success");
        run.setStartedAt(System.currentTimeMillis());
        run.setLastActivityAt(run.getStartedAt());
        env.agentRunRepository.saveRun(run);
        AgentRunEventRecord event = new AgentRunEventRecord();
        event.setEventId(IdSupport.newId());
        event.setRunId(run.getRunId());
        event.setSessionId(session.getSessionId());
        event.setSourceKey("MEMORY:event-room:user");
        event.setEventType("attempt.error");
        event.setSummary("failed with Authorization: Bearer ghp_eventsummary12345");
        event.setMetadataJson(
                ONode.serialize(
                        java.util.Collections.singletonMap(
                                "callback",
                                "https://u:p@example.com/cb?token=event-token-secret")));
        event.setCreatedAt(System.currentTimeMillis());
        env.agentRunRepository.appendEvent(event);

        String indexed = readRunEventFts(env, run.getRunId(), "attempt.error");

        assertThat(indexed)
                .contains("Bearer ***")
                .doesNotContain("ghp_eventsummary12345")
                .doesNotContain("event-token-secret")
                .doesNotContain("u:p@example.com");
    }

    @Test
    void shouldRedactSecretsFromSessionSearchToolErrors() throws Exception {
        SessionSearchTools tools =
                new SessionSearchTools(
                        new FailingSessionSearchService(), "MEMORY:search-room:user");

        ONode result =
                ONode.ofJson(tools.sessionSearch("query-ghp_1234567890abcdef", Integer.valueOf(3)));

        assertThat(result.get("success").getBoolean()).isFalse();
        assertThat(result.get("error").getString())
                .contains("query-ghp_***")
                .doesNotContain("ghp_1234567890abcdef");
    }

    @Test
    void shouldRedactSecretsFromSessionSearchToolResults() throws Exception {
        SessionSearchTools tools =
                new SessionSearchTools(
                        new SecretBearingSessionSearchService(), "MEMORY:search-room:user");

        String response = tools.sessionSearch("token", Integer.valueOf(1));

        assertRedactedSearchResponse(response);
    }

    @Test
    void shouldRedactSecretsFromDashboardSearchResults() throws Exception {
        DashboardSearchController controller =
                new DashboardSearchController(new SecretBearingSessionSearchService());
        Context context = ContextEmpty.create();
        context.paramMap().put("q", "token");

        String response = ONode.serialize(controller.search(context));

        assertRedactedSearchResponse(response);
    }

    @Test
    void shouldReturnAnchoredWindowForScrollMode() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord session = env.sessionRepository.bindNewSession("MEMORY:scroll-room:user");
        session.setTitle("scroll session");
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofUser("first message")
                                        .addMetadata("platformMessageId", "pm-1"),
                                ChatMessage.ofAssistant("second message")
                                        .addMetadata("platformMessageId", "pm-2"),
                                ChatMessage.ofUser("third message")
                                        .addMetadata("platformMessageId", "pm-3"))));
        env.sessionRepository.save(session);

        SessionSearchQuery query = new SessionSearchQuery();
        query.setSessionId(session.getSessionId());
        query.setAroundMessageId("pm-2");
        query.setLimit(3);

        List<SessionSearchEntry> entries = env.sessionSearchService.search(query);

        assertThat(entries).hasSize(3);
        assertThat(entries).extracting(SessionSearchEntry::getMode).containsOnly("scroll");
        assertThat(entries)
                .extracting(SessionSearchEntry::getMessageId)
                .containsExactly("pm-1", "pm-2", "pm-3");
        assertThat(entries.get(1).isAnchor()).isTrue();
        assertThat(entries.get(1).getMatchPreview()).contains("second message");
    }

    @Test
    void shouldBrowseRecentSessionsWhenStructuredQueryHasNoQueryOrAnchor() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord older = env.sessionRepository.bindNewSession("MEMORY:browse-old:user");
        older.setTitle("older browse");
        older.setNdjson(MessageSupport.toNdjson(Arrays.asList(ChatMessage.ofUser("old"))));
        older.setUpdatedAt(System.currentTimeMillis() - 10_000L);
        env.sessionRepository.save(older);
        SessionRecord newer = env.sessionRepository.bindNewSession("MEMORY:browse-new:user");
        newer.setTitle("newer browse");
        newer.setNdjson(MessageSupport.toNdjson(Arrays.asList(ChatMessage.ofUser("new"))));
        env.sessionRepository.save(newer);

        SessionSearchQuery query = new SessionSearchQuery();
        query.setLimit(2);

        List<SessionSearchEntry> entries = env.sessionSearchService.search(query);

        assertThat(entries).hasSize(2);
        assertThat(entries).extracting(SessionSearchEntry::getMode).containsOnly("browse");
        assertThat(entries)
                .extracting(SessionSearchEntry::getTitle)
                .contains("newer browse", "older browse");
    }

    @Test
    void shouldExposeDiscoveryModeAndSnippetForQuerySearch() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord session = env.sessionRepository.bindNewSession("MEMORY:discover-room:user");
        session.setTitle("discover session");
        session.setPlatformMessageId("pm-discover-session");
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofUser("alpha target snippet omega")
                                        .addMetadata("platformMessageId", "pm-discover-message"))));
        env.sessionRepository.save(session);

        SessionSearchQuery query = new SessionSearchQuery();
        query.setQuery("target");
        query.setLimit(3);

        List<SessionSearchEntry> entries = env.sessionSearchService.search(query);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getMode()).isEqualTo("discovery");
        assertThat(entries.get(0).getSnippet()).contains("target snippet");
        assertThat(entries.get(0).getMessageId()).isEqualTo("pm-discover-message");
        assertThat(entries.get(0).getPlatformMessageId()).isEqualTo("pm-discover-session");
    }

    @Test
    void shouldAvoidModelSummaryByDefaultForDiscoverySearch() throws Exception {
        CountingLlmGateway llmGateway = new CountingLlmGateway();
        TestEnvironment env = TestEnvironment.withLlm(llmGateway);
        SessionRecord session = env.sessionRepository.bindNewSession("MEMORY:fast-search:user");
        session.setTitle("fast search session");
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(ChatMessage.ofUser("alpha performance-marker omega"))));
        env.sessionRepository.save(session);

        SessionSearchQuery query = new SessionSearchQuery();
        query.setQuery("performance-marker");
        query.setLimit(3);

        List<SessionSearchEntry> entries = env.sessionSearchService.search(query);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getSummary()).contains("搜索主题：performance-marker");
        assertThat(entries.get(0).getSummary()).contains("匹配片段：");
        assertThat(llmGateway.chatCalls).isZero();
    }

    @Test
    void shouldUseModelSummaryOnlyWhenExplicitlyRequested() throws Exception {
        CountingLlmGateway llmGateway = new CountingLlmGateway();
        TestEnvironment env = TestEnvironment.withLlm(llmGateway);
        SessionRecord session = env.sessionRepository.bindNewSession("MEMORY:summary-search:user");
        session.setTitle("summary search session");
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(ChatMessage.ofUser("alpha explicit-summary-marker omega"))));
        env.sessionRepository.save(session);

        SessionSearchQuery query = new SessionSearchQuery();
        query.setQuery("explicit-summary-marker");
        query.setSummarize(true);
        query.setLimit(3);

        List<SessionSearchEntry> entries = env.sessionSearchService.search(query);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getSummary()).contains("echo:Search topic: explicit-summary-marker");
        assertThat(llmGateway.chatCalls).isEqualTo(1);
    }

    @Test
    void shouldPassScrollParametersThroughSessionSearchTool() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord session = env.sessionRepository.bindNewSession("MEMORY:tool-scroll:user");
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofUser("before")
                                        .addMetadata("platformMessageId", "pm-before"),
                                ChatMessage.ofAssistant("anchor")
                                        .addMetadata("platformMessageId", "pm-anchor"))));
        env.sessionRepository.save(session);
        SessionSearchTools tools =
                new SessionSearchTools(env.sessionSearchService, "MEMORY:tool-scroll:user");

        String response =
                tools.sessionSearch(null, session.getSessionId(), "pm-anchor", Integer.valueOf(2));

        assertThat(response).contains("\"mode\":\"scroll\"");
        assertThat(response).contains("pm-anchor");
    }

    private AssistantMessage assistantWithToolCall(String name, String arguments) {
        Map<String, Object> function = new LinkedHashMap<String, Object>();
        function.put("name", name);
        function.put("arguments", arguments);
        Map<String, Object> raw = new LinkedHashMap<String, Object>();
        raw.put("id", "call-1");
        raw.put("type", "function");
        raw.put("function", function);
        List<Map> rawCalls = new ArrayList<Map>();
        rawCalls.add(raw);
        return new AssistantMessage("", false, rawCalls);
    }

    private static void assertRedactedSearchResponse(String response) {
        assertThat(response)
                .contains("Bearer ***")
                .doesNotContain("ghp_sessionid12345")
                .doesNotContain("ghp_branchsecret12345")
                .doesNotContain("sk-test-titlesecret")
                .doesNotContain("ghp_previewsecret12345")
                .doesNotContain("ghp_summarysecret12345")
                .doesNotContain("ghp_runidsecret12345")
                .doesNotContain("ghp_toolnamesecret12345")
                .doesNotContain("ghp_channelsecret12345");
    }

    private String readToolResultMetadata(TestEnvironment env, String runId) throws Exception {
        return readRunEventFtsColumn(env, runId, "tool.result", "metadata_json");
    }

    private String readRunEventFts(TestEnvironment env, String runId, String eventType)
            throws Exception {
        String summary = readRunEventFtsColumn(env, runId, eventType, "summary");
        String metadata = readRunEventFtsColumn(env, runId, eventType, "metadata_json");
        return summary + "\n" + metadata;
    }

    private String readRunEventFtsColumn(
            TestEnvironment env, String runId, String eventType, String column) throws Exception {
        Connection connection = env.sqliteDatabase.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select "
                                    + column
                                    + " from agent_run_events_fts where run_id = ? and event_type = ?");
            statement.setString(1, runId);
            statement.setString(2, eventType);
            ResultSet resultSet = statement.executeQuery();
            try {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getString(column);
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    private static class FailingSessionSearchService implements SessionSearchService {
        @Override
        public List<SessionSearchEntry> search(String sourceKey, String query, int limit)
                throws Exception {
            throw new IllegalArgumentException("search failed: " + query);
        }
    }

    private static class SecretBearingSessionSearchService implements SessionSearchService {
        @Override
        public List<SessionSearchEntry> search(String sourceKey, String query, int limit) {
            SessionSearchEntry entry = new SessionSearchEntry();
            entry.setSessionId("session-ghp_sessionid12345");
            entry.setBranchName("branch-ghp_branchsecret12345");
            entry.setTitle("title api_key=sk-test-titlesecret");
            entry.setMatchPreview("preview Authorization: Bearer ghp_previewsecret12345");
            entry.setSummary("summary token=ghp_summarysecret12345");
            entry.setRunId("run-ghp_runidsecret12345");
            entry.setToolName("tool-ghp_toolnamesecret12345");
            entry.setChannel("channel-ghp_channelsecret12345");
            return java.util.Collections.singletonList(entry);
        }
    }

    private static class CountingLlmGateway extends FakeLlmGateway {
        /** 记录会话搜索测试中模型摘要调用次数。 */
        private int chatCalls;

        @Override
        public LlmResult chat(
                SessionRecord session,
                String systemPrompt,
                String userMessage,
                List<Object> toolObjects)
                throws Exception {
            chatCalls++;
            return super.chat(session, systemPrompt, userMessage, toolObjects);
        }
    }
}
