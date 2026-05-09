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
                .contains("/history show <编号>")
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

    @Test
    void shouldRenderFullHistoryEntryAndRedactSecrets() throws Exception {
        SessionRecord session = session();
        TerminalHistoryViewer viewer =
                new TerminalHistoryViewer(
                        new BoundSessionRepository(session),
                        new CliRuntime(null, null));

        String text = viewer.render("work", "/history show 1");

        assertThat(viewer.isHistoryCommand("/history show 1")).isTrue();
        assertThat(text)
                .contains("历史条目详情")
                .contains("session=session-history-0001")
                .contains("index=1  role=用户")
                .contains("你好")
                .contains("api_key=***")
                .doesNotContain("sk-1234567890abcdef");
        assertThat(viewer.render("work", "/history show")).contains("使用：/history show <编号>");
        assertThat(viewer.render("work", "/history inspect 99")).contains("当前可用范围：1-2");
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
                                ChatMessage.ofUser("你好\napi_key=sk-1234567890abcdef"),
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
