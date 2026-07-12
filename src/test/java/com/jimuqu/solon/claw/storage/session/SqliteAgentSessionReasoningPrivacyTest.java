package com.jimuqu.solon.claw.storage.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.model.SessionRecord;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.message.AssistantMessage;

/** 验证会话持久化不会保存供应商混入助手消息的私有推理内容。 */
class SqliteAgentSessionReasoningPrivacyTest {
    /** 正文与原始载荷中的推理均应删除，同时保留正式答复。 */
    @Test
    void stripsPrivateReasoningBeforeWritingNdjson() {
        SessionRecord record = new SessionRecord();
        record.setSessionId("reasoning-privacy");
        Map<String, Object> raw = new LinkedHashMap<String, Object>();
        raw.put("thinking", "raw-private-reasoning");
        AssistantMessage assistant =
                new AssistantMessage(
                        "<think>private-reasoning</think>visible-answer",
                        true,
                        raw,
                        null,
                        null,
                        null);

        SqliteAgentSession session = new SqliteAgentSession(record);
        session.addMessage(Collections.singletonList(assistant));

        assertThat(record.getNdjson())
                .contains("visible-answer")
                .doesNotContain("private-reasoning")
                .doesNotContain("raw-private-reasoning")
                .doesNotContain("thinking");
    }
}
