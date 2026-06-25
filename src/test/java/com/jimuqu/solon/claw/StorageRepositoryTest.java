package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.model.AgentRunRecord;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.model.ToolCallRecord;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.storage.session.SqliteAgentSession;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;

public class StorageRepositoryTest {
    @Test
    void shouldPersistAndSearchSessions() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord session = env.sessionRepository.bindNewSession("MEMORY:room-a:user-a");
        session.setNdjson("hello world");
        session.setTitle("alpha session");
        session.setCompressedSummary("beta summary");
        session.setLastInputTokens(12);
        session.setLastOutputTokens(8);
        session.setLastCacheReadTokens(3);
        session.setLastCacheWriteTokens(2);
        session.setLastTotalTokens(25);
        session.setCumulativeInputTokens(42);
        session.setCumulativeOutputTokens(18);
        session.setCumulativeCacheReadTokens(5);
        session.setCumulativeCacheWriteTokens(4);
        session.setCumulativeTotalTokens(69);
        session.setLastResolvedProvider("openai-responses");
        session.setLastResolvedModel("gpt-5.4");
        session.setPlatformMessageId("pm-storage-1");
        session.setMetadataJson("{\"topic\":\"canonical\"}");
        env.sessionRepository.save(session);

        SessionRecord stored = env.sessionRepository.findById(session.getSessionId());
        assertThat(stored).isNotNull();
        assertThat(stored.getLastInputTokens()).isEqualTo(12);
        assertThat(stored.getLastOutputTokens()).isEqualTo(8);
        assertThat(stored.getLastCacheReadTokens()).isEqualTo(3);
        assertThat(stored.getLastCacheWriteTokens()).isEqualTo(2);
        assertThat(stored.getLastTotalTokens()).isEqualTo(25);
        assertThat(stored.getCumulativeInputTokens()).isEqualTo(42);
        assertThat(stored.getCumulativeOutputTokens()).isEqualTo(18);
        assertThat(stored.getCumulativeCacheReadTokens()).isEqualTo(5);
        assertThat(stored.getCumulativeCacheWriteTokens()).isEqualTo(4);
        assertThat(stored.getCumulativeTotalTokens()).isEqualTo(69);
        assertThat(stored.getLastResolvedProvider()).isEqualTo("openai-responses");
        assertThat(stored.getLastResolvedModel()).isEqualTo("gpt-5.4");
        assertThat(stored.getPlatformMessageId()).isEqualTo("pm-storage-1");
        assertThat(stored.getMetadataJson()).contains("canonical");
        assertThat(env.sessionRepository.search("hello", 10)).hasSize(1);
        assertThat(env.sessionRepository.search("alpha", 10)).hasSize(1);
        assertThat(env.sessionRepository.search("beta", 10)).hasSize(1);

        SessionRecord clone =
                env.sessionRepository.cloneSession(
                        "MEMORY:room-a:user-a", session.getSessionId(), "review");
        assertThat(clone.getParentSessionId()).isEqualTo(session.getSessionId());
        assertThat(env.sessionRepository.findBySourceAndBranch("MEMORY:room-a:user-a", "review"))
                .isNotNull();
    }

    @Test
    void shouldApplyDurabilityAndSafetyPragmasOnEveryConnection() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        assertStoragePragmas(env.sqliteDatabase.openConnection());
        assertStoragePragmas(env.sqliteDatabase.openConnection());
    }

    @Test
    void shouldPersistSessionsWhenSearchIndexUnavailable() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        dropSessionSearchIndex(env.sqliteDatabase);

        SessionRecord session = env.sessionRepository.bindNewSession("MEMORY:no-fts-room:user");
        session.setNdjson("fallback searchable transcript");
        session.setTitle("fallback title");
        session.setCompressedSummary("fallback summary");
        env.sessionRepository.save(session);

        assertThat(env.sessionRepository.findById(session.getSessionId())).isNotNull();
        assertThat(env.sessionRepository.search("fallback", 10))
                .extracting(SessionRecord::getSessionId)
                .contains(session.getSessionId());
    }

    @Test
    void shouldDeleteSessionsWhenSearchIndexUnavailable() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord session = env.sessionRepository.bindNewSession("MEMORY:no-fts-delete:user");
        dropSessionSearchIndex(env.sqliteDatabase);

        env.sessionRepository.delete(session.getSessionId());

        assertThat(env.sessionRepository.findById(session.getSessionId())).isNull();
    }

    @Test
    void shouldKeepToolCallCountWhenRunIsSavedAfterToolCompletion() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord session = env.sessionRepository.bindNewSession("MEMORY:run-count:user");
        AgentRunRecord run = new AgentRunRecord();
        run.setRunId("run-tool-count-1");
        run.setSessionId(session.getSessionId());
        run.setSourceKey(session.getSourceKey());
        run.setStatus("running");
        run.setStartedAt(System.currentTimeMillis());
        run.setLastActivityAt(run.getStartedAt());
        env.agentRunRepository.saveRun(run);

        ToolCallRecord toolCall = new ToolCallRecord();
        toolCall.setToolCallId("tool-call-count-1");
        toolCall.setRunId(run.getRunId());
        toolCall.setSessionId(session.getSessionId());
        toolCall.setSourceKey(session.getSourceKey());
        toolCall.setToolName("todo");
        toolCall.setStatus("completed");
        toolCall.setStartedAt(run.getStartedAt());
        toolCall.setFinishedAt(System.currentTimeMillis());
        env.agentRunRepository.saveToolCall(toolCall);

        run.setStatus("success");
        run.setPhase("completed");
        run.setFinishedAt(System.currentTimeMillis());
        env.agentRunRepository.saveRun(run);

        assertThat(env.agentRunRepository.findRun(run.getRunId()).getToolCallCount()).isEqualTo(1);
    }

    @Test
    void shouldExcludeDeniedToolCallsFromRunToolCallCount() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord session = env.sessionRepository.bindNewSession("MEMORY:run-denied-count:user");
        AgentRunRecord run = new AgentRunRecord();
        run.setRunId("run-tool-denied-count-1");
        run.setSessionId(session.getSessionId());
        run.setSourceKey(session.getSourceKey());
        run.setStatus("running");
        run.setStartedAt(System.currentTimeMillis());
        run.setLastActivityAt(run.getStartedAt());
        env.agentRunRepository.saveRun(run);

        ToolCallRecord completed = toolCall(run, session, "tool-call-denied-count-1", "todo", "completed");
        env.agentRunRepository.saveToolCall(completed);

        ToolCallRecord denied = toolCall(run, session, "tool-call-denied-count-2", "todo", "denied");
        env.agentRunRepository.saveToolCall(denied);

        run.setStatus("success");
        run.setPhase("completed");
        run.setFinishedAt(System.currentTimeMillis());
        env.agentRunRepository.saveRun(run);

        assertThat(env.agentRunRepository.findRun(run.getRunId()).getToolCallCount()).isEqualTo(1);
    }

    @Test
    void shouldClearSessionScopedSecurityStateWhenBranching() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord session = env.sessionRepository.bindNewSession("MEMORY:secure-branch:user");
        SqliteAgentSession agentSession = new SqliteAgentSession(session, env.sessionRepository);
        agentSession.getContext().put("ordinary_context", "keep-me");
        env.dangerousCommandApprovalService.enableSessionAutoApproval(agentSession);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf workspace/cache");
        env.dangerousCommandApprovalService.approve(
                agentSession, DangerousCommandApprovalService.ApprovalScope.SESSION, "tester");

        SessionRecord clone =
                env.sessionRepository.cloneSession(
                        "MEMORY:secure-branch:user", session.getSessionId(), "safe-review");
        SqliteAgentSession clonedSession = new SqliteAgentSession(clone, env.sessionRepository);

        assertThat(clonedSession.getContext().get("ordinary_context")).isEqualTo("keep-me");
        assertThat(env.dangerousCommandApprovalService.isSessionAutoApprovalEnabled(clonedSession))
                .isFalse();
        assertThat(env.dangerousCommandApprovalService.getPendingApproval(clonedSession)).isNull();
        assertThat(
                        env.dangerousCommandApprovalService.isSessionApproved(
                                clonedSession, "recursive_delete"))
                .isFalse();
    }

    @Test
    void shouldFindResumeCandidatesByUniqueIdPrefixOrExactTitle() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord alpha = env.sessionRepository.bindNewSession("MEMORY:resume-a:user");
        alpha.setTitle("客户周报");
        env.sessionRepository.save(alpha);
        SessionRecord beta = env.sessionRepository.bindNewSession("MEMORY:resume-b:user");
        beta.setTitle("客户日报");
        env.sessionRepository.save(beta);

        List<SessionRecord> byPrefix =
                env.sessionRepository.findResumeCandidates(alpha.getSessionId().substring(0, 8), 3);
        List<SessionRecord> byTitle = env.sessionRepository.findResumeCandidates("客户日报", 3);
        List<SessionRecord> partialTitle = env.sessionRepository.findResumeCandidates("客户", 3);

        assertThat(byPrefix).hasSize(1);
        assertThat(byPrefix.get(0).getSessionId()).isEqualTo(alpha.getSessionId());
        assertThat(byTitle).hasSize(1);
        assertThat(byTitle.get(0).getSessionId()).isEqualTo(beta.getSessionId());
        assertThat(partialTitle).isEmpty();
    }

    @Test
    void shouldReturnMultipleResumeCandidatesForAmbiguousExactTitle() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord first = env.sessionRepository.bindNewSession("MEMORY:resume-shared-a:user");
        first.setTitle("共享标题");
        env.sessionRepository.save(first);
        SessionRecord second = env.sessionRepository.bindNewSession("MEMORY:resume-shared-b:user");
        second.setTitle("共享标题");
        env.sessionRepository.save(second);

        List<SessionRecord> candidates = env.sessionRepository.findResumeCandidates("共享标题", 3);

        assertThat(candidates).hasSize(2);
        assertThat(candidates)
                .extracting(SessionRecord::getSessionId)
                .contains(first.getSessionId(), second.getSessionId());
    }

    @Test
    void shouldListSessionLineageFromRootAndResolveLatestDescendantPath() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord root =
                env.sessionRepository.bindNewSession("MEMORY:lineage-storage-root:user");
        root.setTitle("root");
        root.setCreatedAt(100L);
        root.setUpdatedAt(100L);
        env.sessionRepository.save(root);

        SessionRecord oldChild =
                env.sessionRepository.cloneSession(
                        "MEMORY:lineage-storage-old:user", root.getSessionId(), "old");
        oldChild.setTitle("old");
        oldChild.setCreatedAt(200L);
        oldChild.setUpdatedAt(200L);
        env.sessionRepository.save(oldChild);

        SessionRecord newChild =
                env.sessionRepository.cloneSession(
                        "MEMORY:lineage-storage-new:user", root.getSessionId(), "new");
        newChild.setTitle("new");
        newChild.setCreatedAt(300L);
        newChild.setUpdatedAt(300L);
        env.sessionRepository.save(newChild);

        SessionRecord grandchild =
                env.sessionRepository.cloneSession(
                        "MEMORY:lineage-storage-grand:user", newChild.getSessionId(), "grand");
        grandchild.setTitle("grand");
        grandchild.setCreatedAt(400L);
        grandchild.setUpdatedAt(400L);
        env.sessionRepository.save(grandchild);

        List<SessionRecord> lineage = env.sessionRepository.listLineage(newChild.getSessionId());
        List<String> latestPath = env.sessionRepository.latestDescendantPath(root.getSessionId());

        assertThat(lineage)
                .extracting(SessionRecord::getSessionId)
                .contains(
                        root.getSessionId(),
                        oldChild.getSessionId(),
                        newChild.getSessionId(),
                        grandchild.getSessionId());
        assertThat(env.sessionRepository.resolveRootSessionId(grandchild.getSessionId()))
                .isEqualTo(root.getSessionId());
        assertThat(latestPath)
                .containsExactly(
                        root.getSessionId(), newChild.getSessionId(), grandchild.getSessionId());
    }

    private void assertStoragePragmas(Connection connection) throws Exception {
        try {
            assertThat(pragmaInt(connection, "secure_delete")).isEqualTo(1);
            assertThat(pragmaInt(connection, "cell_size_check")).isEqualTo(1);
            assertThat(pragmaInt(connection, "synchronous")).isEqualTo(2);
        } finally {
            connection.close();
        }
    }

    private int pragmaInt(Connection connection, String name) throws Exception {
        Statement statement = connection.createStatement();
        try {
            ResultSet resultSet = statement.executeQuery("pragma " + name);
            try {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getInt(1);
            } finally {
                resultSet.close();
            }
        } finally {
            statement.close();
        }
    }

    /**
     * 构造工具调用记录，复用当前运行和会话的审计归属字段。
     *
     * @param run 当前运行记录。
     * @param session 当前会话记录。
     * @param toolCallId 工具调用标识。
     * @param toolName 工具名称。
     * @param status 工具调用状态。
     * @return 返回可落库的工具调用记录。
     */
    private ToolCallRecord toolCall(
            AgentRunRecord run,
            SessionRecord session,
            String toolCallId,
            String toolName,
            String status) {
        ToolCallRecord toolCall = new ToolCallRecord();
        toolCall.setToolCallId(toolCallId);
        toolCall.setRunId(run.getRunId());
        toolCall.setSessionId(session.getSessionId());
        toolCall.setSourceKey(session.getSourceKey());
        toolCall.setToolName(toolName);
        toolCall.setStatus(status);
        toolCall.setStartedAt(run.getStartedAt());
        toolCall.setFinishedAt(System.currentTimeMillis());
        return toolCall;
    }

    private void dropSessionSearchIndex(SqliteDatabase database) throws Exception {
        Connection connection = database.openConnection();
        try {
            Statement statement = connection.createStatement();
            try {
                statement.execute("drop table if exists sessions_fts");
            } finally {
                statement.close();
            }
        } finally {
            connection.close();
        }
    }
}
