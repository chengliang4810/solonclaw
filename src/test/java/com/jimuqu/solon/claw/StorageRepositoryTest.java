package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.model.SessionRecord;
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
    void shouldClearSessionScopedSecurityStateWhenBranching() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord session = env.sessionRepository.bindNewSession("MEMORY:secure-branch:user");
        SqliteAgentSession agentSession = new SqliteAgentSession(session, env.sessionRepository);
        agentSession.getContext().put("ordinary_context", "keep-me");
        env.dangerousCommandApprovalService.enableSessionYolo(agentSession);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf runtime/cache");
        env.dangerousCommandApprovalService.approve(
                agentSession, DangerousCommandApprovalService.ApprovalScope.SESSION, "tester");

        SessionRecord clone =
                env.sessionRepository.cloneSession(
                        "MEMORY:secure-branch:user", session.getSessionId(), "safe-review");
        SqliteAgentSession clonedSession =
                new SqliteAgentSession(clone, env.sessionRepository);

        assertThat(clonedSession.getContext().get("ordinary_context")).isEqualTo("keep-me");
        assertThat(env.dangerousCommandApprovalService.isSessionYoloEnabled(clonedSession))
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
        List<SessionRecord> byTitle =
                env.sessionRepository.findResumeCandidates("客户日报", 3);
        List<SessionRecord> partialTitle =
                env.sessionRepository.findResumeCandidates("客户", 3);

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

        List<SessionRecord> candidates =
                env.sessionRepository.findResumeCandidates("共享标题", 3);

        assertThat(candidates).hasSize(2);
        assertThat(candidates)
                .extracting(SessionRecord::getSessionId)
                .contains(first.getSessionId(), second.getSessionId());
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
}
