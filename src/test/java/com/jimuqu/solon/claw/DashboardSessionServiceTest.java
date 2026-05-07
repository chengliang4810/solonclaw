package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.support.MessageSupport;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.web.DashboardSessionService;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.message.ChatMessage;

public class DashboardSessionServiceTest {
    @Test
    void shouldExposeAssistantReasoningAndCompressionMetadata() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord session = env.sessionRepository.bindNewSession("MEMORY:dash:user");
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofUser("问题"),
                                ChatMessage.ofAssistant("<think>先分析路径</think>\n最终答复"))));
        session.setCompressedSummary("Summary\n已压缩");
        session.setLastCompressionAt(123L);
        session.setLastCompressionInputTokens(456);
        env.sessionRepository.save(session);

        DashboardSessionService service = new DashboardSessionService(env.sessionRepository);
        Map<String, Object> detail = service.getSessionMessages(session.getSessionId());

        assertThat(detail.get("compressed_summary")).isEqualTo("Summary\n已压缩");
        assertThat(detail.get("last_compression_at")).isEqualTo(123L);
        assertThat(detail.get("last_compression_input_tokens")).isEqualTo(456);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) detail.get("messages");
        Map<String, Object> assistant = messages.get(1);
        assertThat(assistant.get("content")).isEqualTo("最终答复");
        assertThat(assistant.get("reasoning")).isEqualTo("先分析路径");
    }

    @Test
    void shouldBuildSessionTreeFromParentLinksAcrossSourceKeys() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord root = env.sessionRepository.bindNewSession("MEMORY:lineage:root");
        root.setTitle("root");
        env.sessionRepository.save(root);

        SessionRecord child =
                env.sessionRepository.cloneSession(
                        "MEMORY:lineage:child", root.getSessionId(), "child");
        child.setTitle("child");
        env.sessionRepository.save(child);

        SessionRecord grandchild =
                env.sessionRepository.cloneSession(
                        "MEMORY:lineage:grandchild", child.getSessionId(), "grandchild");
        grandchild.setTitle("grandchild");
        env.sessionRepository.save(grandchild);

        DashboardSessionService service = new DashboardSessionService(env.sessionRepository);
        Map<String, Object> tree = service.sessionTree(child.getSessionId());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) tree.get("nodes");
        assertThat(nodes)
                .extracting(node -> node.get("id"))
                .contains(root.getSessionId(), child.getSessionId(), grandchild.getSessionId());
    }

    @Test
    void shouldResolveLatestDescendantThroughNewestChildPath() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord root = env.sessionRepository.bindNewSession("MEMORY:lineage-root:user");
        root.setTitle("root");
        root.setCreatedAt(100L);
        root.setUpdatedAt(100L);
        env.sessionRepository.save(root);

        SessionRecord oldChild =
                env.sessionRepository.cloneSession(
                        "MEMORY:lineage-old:user", root.getSessionId(), "old");
        oldChild.setCreatedAt(200L);
        oldChild.setUpdatedAt(200L);
        env.sessionRepository.save(oldChild);

        SessionRecord newChild =
                env.sessionRepository.cloneSession(
                        "MEMORY:lineage-new:user", root.getSessionId(), "new");
        newChild.setCreatedAt(300L);
        newChild.setUpdatedAt(300L);
        env.sessionRepository.save(newChild);

        SessionRecord grandchild =
                env.sessionRepository.cloneSession(
                        "MEMORY:lineage-grand:user", newChild.getSessionId(), "grand");
        grandchild.setCreatedAt(400L);
        grandchild.setUpdatedAt(400L);
        env.sessionRepository.save(grandchild);

        DashboardSessionService service = new DashboardSessionService(env.sessionRepository);
        Map<String, Object> latest = service.latestDescendant(root.getSessionId());

        assertThat(latest.get("requested_session_id")).isEqualTo(root.getSessionId());
        assertThat(latest.get("session_id")).isEqualTo(grandchild.getSessionId());
        assertThat(latest.get("changed")).isEqualTo(Boolean.TRUE);
        @SuppressWarnings("unchecked")
        List<String> path = (List<String>) latest.get("path");
        assertThat(path)
                .containsExactly(
                        root.getSessionId(), newChild.getSessionId(), grandchild.getSessionId());

        Map<String, Object> leaf = service.latestDescendant(grandchild.getSessionId());
        assertThat(leaf.get("session_id")).isEqualTo(grandchild.getSessionId());
        assertThat(leaf.get("changed")).isEqualTo(Boolean.FALSE);
    }
}
