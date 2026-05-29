package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.support.MessageSupport;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.web.DashboardSessionService;
import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
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
    void shouldRedactSecretsFromDashboardSessionMetadata() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord root = env.sessionRepository.bindNewSession("MEMORY:dash-secret-root:user");
        root.setTitle("token=ghp_rootdashboardsecret12345");
        root.setBranchName("main-token=ghp_rootbranchsecret12345");
        root.setLastResolvedProvider("provider-token=ghp_rootprovidersecret12345");
        root.setLastResolvedModel("model-token=ghp_rootmodelsecret12345");
        root.setActiveAgentName("agent-token=ghp_rootagentsecret12345");
        root.setCompressedSummary("summary token=ghp_rootsummarysecret12345");
        root.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofUser(
                                        "Authorization: Bearer ghp_rootpreviewsecret12345"))));
        env.sessionRepository.save(root);

        SessionRecord child =
                env.sessionRepository.cloneSession(
                        "MEMORY:dash-secret-child:user",
                        root.getSessionId(),
                        "branch-token=ghp_childbranchsecret12345");
        child.setTitle("child token=ghp_childtitlesecret12345");
        env.sessionRepository.save(child);

        DashboardSessionService service = new DashboardSessionService(env.sessionRepository);
        Map<String, Object> list = service.getSessions(10, 0);
        String listText = String.valueOf(list);
        assertThat(listText)
                .contains("token=***")
                .contains("Authorization: Bearer ***")
                .doesNotContain("ghp_rootdashboardsecret12345")
                .doesNotContain("ghp_rootbranchsecret12345")
                .doesNotContain("ghp_rootprovidersecret12345")
                .doesNotContain("ghp_rootmodelsecret12345")
                .doesNotContain("ghp_rootagentsecret12345")
                .doesNotContain("ghp_rootsummarysecret12345")
                .doesNotContain("ghp_rootpreviewsecret12345");

        Map<String, Object> detail = service.getSessionMessages(root.getSessionId());
        String detailText = String.valueOf(detail);
        assertThat(detailText)
                .contains("Authorization: Bearer ***")
                .doesNotContain("ghp_rootbranchsecret12345")
                .doesNotContain("ghp_rootpreviewsecret12345");

        Map<String, Object> tree = service.sessionTree(child.getSessionId());
        String treeText = String.valueOf(tree);
        assertThat(treeText)
                .contains("branch-token=***")
                .doesNotContain("ghp_childbranchsecret12345")
                .doesNotContain("ghp_childtitlesecret12345");

        Map<String, Object> search = service.searchSessions("Authorization");
        String searchText = String.valueOf(search);
        assertThat(searchText)
                .contains(">>>Authorization<<<: Bearer ***")
                .doesNotContain("ghp_rootpreviewsecret12345");
    }

    @Test
    void shouldRedactSecretLikeSessionIdentifiersFromDashboardResponses() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord root = new SessionRecord();
        root.setSessionId("session-ghp_dashsessionid12345");
        root.setSourceKey("MEMORY:dash-id-root:user");
        root.setBranchName("main");
        root.setTitle("root");
        root.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(ChatMessage.ofUser("needle token=ghp_dashmessage12345"))));
        root.setCreatedAt(100L);
        root.setUpdatedAt(100L);
        env.sessionRepository.save(root);

        SessionRecord child = new SessionRecord();
        child.setSessionId("session-ghp_dashchildid12345");
        child.setSourceKey("MEMORY:dash-id-child:user");
        child.setParentSessionId(root.getSessionId());
        child.setBranchName("child");
        child.setTitle("child");
        child.setNdjson("");
        child.setCreatedAt(200L);
        child.setUpdatedAt(200L);
        env.sessionRepository.save(child);

        DashboardSessionService service = new DashboardSessionService(env.sessionRepository);

        String listText = String.valueOf(service.getSessions(10, 0));
        String detailText = String.valueOf(service.getSessionMessages(root.getSessionId()));
        String searchText = String.valueOf(service.searchSessions("needle"));
        String treeText = String.valueOf(service.sessionTree(root.getSessionId()));
        String latestText = String.valueOf(service.latestDescendant(root.getSessionId()));
        String missingText = String.valueOf(service.recap("missing-ghp_dashmissingid12345", 10));

        assertThat(listText).contains("session-ghp_***");
        assertThat(detailText).contains("session_id=session-ghp_***").contains("token=***");
        assertThat(searchText).contains("session_id=session-ghp_***").contains("token=***");
        assertThat(treeText).contains("parent_session_id=session-ghp_***");
        assertThat(latestText)
                .contains("requested_session_id=session-ghp_***")
                .contains("session_id=session-ghp_***");
        assertThat(missingText).contains("session_id=missing-ghp_***");
        assertThat(listText + detailText + searchText + treeText + latestText + missingText)
                .doesNotContain("dashsessionid12345")
                .doesNotContain("dashchildid12345")
                .doesNotContain("dashmessage12345")
                .doesNotContain("dashmissingid12345");
    }

    @Test
    void shouldRedactSecretsFromDashboardCheckpointList() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String sourceKey = "MEMORY:dash-checkpoint:ghp_dashboardcheckpointsource";
        SessionRecord session = env.sessionRepository.bindNewSession(sourceKey);
        File checkpointDir = new File(env.appConfig.getRuntime().getCacheDir(), "checkpoints/dashboard");
        File manifest = new File(checkpointDir, "manifest.json");
        insertCheckpoint(
                env,
                "dashboard-checkpoint",
                sourceKey,
                "session-ghp_dashboardcheckpointsession",
                checkpointDir,
                manifest);

        DashboardSessionService service =
                new DashboardSessionService(env.sessionRepository, env.checkpointService);
        Map<String, Object> checkpoints = service.checkpoints(session.getSessionId());
        String checkpointText = String.valueOf(checkpoints);

        assertThat(checkpointText)
                .contains("source_key=MEMORY:dash-checkpoint:***")
                .contains("session_id=session-ghp_***")
                .doesNotContain("ghp_dashboardcheckpointsource")
                .doesNotContain("ghp_dashboardcheckpointsession");
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

    private static void insertCheckpoint(
            TestEnvironment env,
            String checkpointId,
            String sourceKey,
            String sessionId,
            File checkpointDir,
            File manifest)
            throws Exception {
        checkpointDir.mkdirs();
        java.nio.file.Files.write(
                manifest.toPath(),
                "{\"files\":[],\"skipped\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Connection connection = env.sqliteDatabase.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "insert into checkpoints (checkpoint_id, source_key, session_id, checkpoint_dir, manifest_path, created_at, restored_at) values (?, ?, ?, ?, ?, ?, ?)");
            statement.setString(1, checkpointId);
            statement.setString(2, sourceKey);
            statement.setString(3, sessionId);
            statement.setString(4, checkpointDir.getAbsolutePath());
            statement.setString(5, manifest.getAbsolutePath());
            statement.setLong(6, System.currentTimeMillis());
            statement.setLong(7, 0L);
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
    }
}
