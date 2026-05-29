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
        assertThat(browser.isBrowserCommand("/session show 1")).isTrue();
        assertThat(browser.isBrowserCommand("/session inspect session-alpha-0001")).isTrue();
        assertThat(text)
                .contains("最近会话")
                .contains("客户周报")
                .contains("branch=main")
                .contains("tokens=42")
                .contains("/session show <编号>")
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

    @Test
    void shouldRenderSessionDetailFromLastChoice() {
        TerminalSessionBrowser browser =
                new TerminalSessionBrowser(new FakeSessionRepository(sessions()));

        browser.render("/sessions");
        String text = browser.render("/session show 1");

        assertThat(text)
                .contains("会话详情")
                .contains("id: session-alpha-0001")
                .contains("title: 客户周报")
                .contains("branch: main")
                .contains("source: cli:user")
                .contains("parent: root-session")
                .contains("agent: writer")
                .contains("model: provider=local model=qwen")
                .contains("messages: 2")
                .contains("tokens: last=9 cumulative=42 input=11 output=22 reasoning=3")
                .contains("summary: 已生成周报摘要")
                .contains("建议：/resume session-alpha-0001")
                .contains("/goal status")
                .contains("/compact");
        assertThat(browser.resolveCommand("/session show 1")).isEmpty();
    }

    @Test
    void shouldRenderSessionDetailByIdOrTitle() {
        TerminalSessionBrowser browser =
                new TerminalSessionBrowser(new FakeSessionRepository(sessions()));

        assertThat(browser.render("/session inspect session-beta-0002"))
                .contains("研发计划")
                .contains("messages: 1");
        assertThat(browser.render("/session show 客户周报"))
                .contains("session-alpha-0001")
                .contains("已生成周报摘要");
        assertThat(browser.render("/session show missing"))
                .contains("没有找到匹配的会话");
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
        record.setParentSessionId(id.equals("session-alpha-0001") ? "root-session" : "");
        record.setActiveAgentName(id.equals("session-alpha-0001") ? "writer" : "");
        record.setLastResolvedProvider("local");
        record.setLastResolvedModel(id.equals("session-alpha-0001") ? "qwen" : "deepseek");
        record.setLastTotalTokens(9L);
        record.setCumulativeInputTokens(11L);
        record.setCumulativeOutputTokens(22L);
        record.setCumulativeReasoningTokens(3L);
        record.setCompressedSummary(id.equals("session-alpha-0001") ? "已生成周报摘要" : "");
        record.setNdjson(
                id.equals("session-alpha-0001")
                        ? "{\"role\":\"user\"}\n{\"role\":\"assistant\"}\n"
                        : "{\"role\":\"user\"}\n");
        record.setCreatedAt(1699990000000L);
        record.setUpdatedAt(1700000000000L);
        return record;
    }

    static class FakeSessionRepository implements SessionRepository {
        private final List<SessionRecord> records;

        FakeSessionRepository(List<SessionRecord> records) {
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
            List<SessionRecord> result = new ArrayList<SessionRecord>();
            for (SessionRecord record : records) {
                if (record.getSessionId().startsWith(reference)
                        || (record.getTitle() != null && record.getTitle().equals(reference))) {
                    result.add(record);
                    if (result.size() >= limit) {
                        break;
                    }
                }
            }
            return result;
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
