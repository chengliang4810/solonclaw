package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.TerminalSessionBrowser;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

public class TerminalSessionBrowserTest {
    @Test
    void shouldRenderRecentSessionsAndResolvePickedSession() {
        TerminalSessionBrowser browser =
                new TerminalSessionBrowser(new FakeSessionRepository(sessions()));

        String text = browser.render("/sessions");

        assertThat(browser.isBrowserCommand("/sessions")).isTrue();
        assertThat(browser.isBrowserCommand("/session pick 1")).isTrue();
        assertThat(text)
                .contains("最近会话")
                .contains("客户周报")
                .contains("branch=main")
                .contains("tokens=42")
                .contains("/session pick <编号>");
        assertThat(browser.resolveCommand("/session pick 1"))
                .isEqualTo("/resume session-alpha-0001");
    }

    @Test
    void shouldSearchSessionsBeforePicking() {
        TerminalSessionBrowser browser =
                new TerminalSessionBrowser(new FakeSessionRepository(sessions()));

        String text = browser.render("/sessions 周报");

        assertThat(text).contains("客户周报").doesNotContain("研发计划");
        assertThat(browser.resolveCommand("/session pick 1"))
                .isEqualTo("/resume session-alpha-0001");
        assertThat(browser.resolveCommand("/session pick 9")).isEmpty();
    }

    private List<SessionRecord> sessions() {
        List<SessionRecord> records = new ArrayList<SessionRecord>();
        records.add(session("session-alpha-0001", "客户周报", "main", "cli:user", 42L));
        records.add(session("session-beta-0002", "研发计划", "feature", "tui:user", 7L));
        return records;
    }

    private SessionRecord session(
            String id, String title, String branch, String sourceKey, long totalTokens) {
        SessionRecord record = new SessionRecord();
        record.setSessionId(id);
        record.setTitle(title);
        record.setBranchName(branch);
        record.setSourceKey(sourceKey);
        record.setCumulativeTotalTokens(totalTokens);
        record.setUpdatedAt(1700000000000L);
        return record;
    }

    private static class FakeSessionRepository implements SessionRepository {
        private final List<SessionRecord> records;

        private FakeSessionRepository(List<SessionRecord> records) {
            this.records = records;
        }

        @Override
        public SessionRecord getBoundSession(String sourceKey) {
            return null;
        }

        @Override
        public SessionRecord bindNewSession(String sourceKey) {
            return null;
        }

        @Override
        public void bindSource(String sourceKey, String sessionId) {}

        @Override
        public SessionRecord cloneSession(String sourceKey, String sourceSessionId, String branchName) {
            return null;
        }

        @Override
        public SessionRecord findById(String sessionId) {
            for (SessionRecord record : records) {
                if (record.getSessionId().equals(sessionId)) {
                    return record;
                }
            }
            return null;
        }

        @Override
        public SessionRecord findBySourceAndBranch(String sourceKey, String branchName) {
            return null;
        }

        @Override
        public List<SessionRecord> findResumeCandidates(String reference, int limit) {
            return Collections.emptyList();
        }

        @Override
        public void save(SessionRecord sessionRecord) {}

        @Override
        public List<SessionRecord> search(String keyword, int limit) {
            List<SessionRecord> result = new ArrayList<SessionRecord>();
            for (SessionRecord record : records) {
                if (record.getTitle() != null && record.getTitle().contains(keyword)) {
                    result.add(record);
                }
            }
            return result;
        }

        @Override
        public List<SessionRecord> listRecent(int limit) {
            return records;
        }

        @Override
        public List<SessionRecord> listRecent(int limit, int offset) {
            return records;
        }

        @Override
        public List<SessionRecord> listPendingAgentSessions(long updatedAfterMillis, int limit) {
            return Collections.emptyList();
        }

        @Override
        public int countAll() {
            return records.size();
        }

        @Override
        public void delete(String sessionId) {}

        @Override
        public void setModelOverride(String sessionId, String modelOverride) {}

        @Override
        public void setActiveAgentName(String sessionId, String agentName) {}

        @Override
        public void clearActiveAgentName(String agentName) {}

        @Override
        public void setGoalState(String sessionId, String goalStateJson) {}

        @Override
        public void setLastLearningAt(String sessionId, long lastLearningAt) {}
    }
}
