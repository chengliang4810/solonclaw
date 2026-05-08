package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.CliRuntime;
import com.jimuqu.solon.claw.cli.TerminalHistoryViewer;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.support.MessageSupport;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.message.ChatMessage;

public class TerminalHistoryViewerTest {
    @Test
    void shouldRenderCurrentSessionHistory() throws Exception {
        SessionRecord session = session();
        TerminalHistoryViewer viewer =
                new TerminalHistoryViewer(
                        new BoundSessionRepository(session),
                        new CliRuntime(null, null));

        String text = viewer.render("work", "/history");

        assertThat(viewer.isHistoryCommand("/history")).isTrue();
        assertThat(text)
                .contains("当前会话历史")
                .contains("session=session-history-0001")
                .contains("用户: 你好")
                .contains("助手: 已收到")
                .contains("使用：/history <条数>");
    }

    @Test
    void shouldLimitHistoryPreview() throws Exception {
        SessionRecord session = session();
        TerminalHistoryViewer viewer =
                new TerminalHistoryViewer(
                        new BoundSessionRepository(session),
                        new CliRuntime(null, null));

        String text = viewer.render("work", "/history 1");

        assertThat(text).contains("助手: 已收到").doesNotContain("用户: 你好");
    }

    private SessionRecord session() throws Exception {
        SessionRecord record = new SessionRecord();
        record.setSessionId("session-history-0001");
        record.setSourceKey("MEMORY:cli:work");
        record.setTitle("终端历史");
        record.setNdjson(
                MessageSupport.toNdjson(
                        java.util.Arrays.asList(
                                ChatMessage.ofSystem("system"),
                                ChatMessage.ofUser("你好"),
                                ChatMessage.ofAssistant("已收到"))));
        return record;
    }

    private static class BoundSessionRepository
            extends TerminalSessionBrowserTest.FakeSessionRepository {
        private final SessionRecord bound;

        private BoundSessionRepository(SessionRecord bound) {
            super(Collections.singletonList(bound));
            this.bound = bound;
        }

        @Override
        public SessionRecord getBoundSession(String sourceKey) {
            if (bound.getSourceKey().equals(sourceKey)) {
                return bound;
            }
            return null;
        }
    }
}
