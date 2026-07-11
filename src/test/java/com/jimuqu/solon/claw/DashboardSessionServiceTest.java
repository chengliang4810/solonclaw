package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.support.MessageSupport;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.support.constants.CompressionConstants;
import com.jimuqu.solon.claw.web.DashboardSessionService;
import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Arrays;
import java.util.Collections;
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
    void shouldExposeSummaryMetadataWithSessionMessages() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord session = env.sessionRepository.bindNewSession("MEMORY:dash-detail:user");
        session.setTitle("Detail title");
        session.setCreatedAt(100L);
        session.setUpdatedAt(200L);
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(ChatMessage.ofUser("inspect session detail"))));
        env.sessionRepository.save(session);

        DashboardSessionService service = new DashboardSessionService(env.sessionRepository);
        Map<String, Object> detail = service.getSessionMessages(session.getSessionId());

        assertThat(detail)
                .containsEntry("id", session.getSessionId())
                .containsEntry("source", "local")
                .containsEntry("title", "Detail title")
                .containsEntry("started_at", 100L)
                .containsEntry("last_active", 200L)
                .containsEntry("message_count", 1)
                .containsEntry("preview", "inspect session detail");
    }

    /** 验证 Dashboard 会话接口从持久化层读取中文消息和压缩摘要时不产生乱码。 */
    @Test
    void shouldExposeChineseMessagesAndCompressedSummaryWithoutEncodingCorruption()
            throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord session = env.sessionRepository.bindNewSession("MEMORY:dash-chinese:user");
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofUser("长期回归 Loop：检查会话恢复"),
                                ChatMessage.ofAssistant("日志检索已通过，下一轮验证 UI 渲染。"))));
        session.setCompressedSummary("摘要：长期任务目标仍然是验证状态连续。");
        env.sessionRepository.save(session);

        DashboardSessionService service = new DashboardSessionService(env.sessionRepository);
        Map<String, Object> detail = service.getSessionMessages(session.getSessionId());

        assertThat(detail.get("compressed_summary")).isEqualTo("摘要：长期任务目标仍然是验证状态连续。");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) detail.get("messages");
        assertThat(messages.get(0).get("content")).isEqualTo("长期回归 Loop：检查会话恢复");
        assertThat(messages.get(1).get("content")).isEqualTo("日志检索已通过，下一轮验证 UI 渲染。");
        String detailText = String.valueOf(detail);
        assertThat(detailText).doesNotContain("闀挎湡").doesNotContain("�");
    }

    /** 验证 Dashboard 不把已进入独立压缩摘要的当前摘要残留展示为当前对话消息。 */
    @Test
    void shouldHideCurrentSummaryArtifactsFromMessages() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord session =
                env.sessionRepository.bindNewSession("MEMORY:dash-summary-artifact:user");
        session.setCompressedSummary(
                CompressionConstants.SUMMARY_PREFIX + "\nGoal\n验证会话恢复。\n\nProgress\n- 已生成当前摘要。");
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofAssistant(
                                        CompressionConstants.SUMMARY_PREFIX
                                                + "\nGoal\n验证会话恢复。\n\nProgress\n- 已生成当前摘要。"),
                                ChatMessage.ofUser("继续验证会话恢复"),
                                ChatMessage.ofAssistant("下一步检查日志。"))));
        env.sessionRepository.save(session);

        DashboardSessionService service = new DashboardSessionService(env.sessionRepository);
        Map<String, Object> detail = service.getSessionMessages(session.getSessionId());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) detail.get("messages");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).get("content")).isEqualTo("继续验证会话恢复");
        assertThat(messages.get(1).get("content")).isEqualTo("下一步检查日志。");
        assertThat(String.valueOf(messages)).doesNotContain(CompressionConstants.SUMMARY_PREFIX);
    }

    @Test
    void shouldExposeToolMessagesWithEscapedJsonContent() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord session = env.sessionRepository.bindNewSession("MEMORY:dash-tool-json:user");
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofUser("创建 todo"),
                                ChatMessage.ofTool(
                                        "{\"status\":\"success\",\"preview\":\"{\\\"total\\\":3}\"}",
                                        "todo",
                                        "call_todo"))));
        env.sessionRepository.save(session);

        DashboardSessionService service = new DashboardSessionService(env.sessionRepository);
        Map<String, Object> detail = service.getSessionMessages(session.getSessionId());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) detail.get("messages");
        assertThat(messages).hasSize(2);
        Map<String, Object> tool = messages.get(1);
        assertThat(tool.get("role")).isEqualTo("tool");
        assertThat(tool.get("tool_name")).isEqualTo("todo");
        assertThat(tool.get("tool_call_id")).isEqualTo("call_todo");
        assertThat(String.valueOf(tool.get("content"))).contains("\"status\":\"success\"");
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
        String treeText = String.valueOf(service.sessionTree(root.getSessionId()));
        String latestText = String.valueOf(service.latestDescendant(root.getSessionId()));
        String missingText = String.valueOf(service.recap("missing-ghp_dashmissingid12345", 10));

        assertThat(listText).contains("session-ghp_***");
        assertThat(detailText).contains("session_id=session-ghp_***").contains("token=***");
        assertThat(treeText).contains("parent_session_id=session-ghp_***");
        assertThat(latestText)
                .contains("requested_session_id=session-ghp_***")
                .contains("session_id=session-ghp_***");
        assertThat(missingText).contains("session_id=missing-ghp_***");
        assertThat(listText + detailText + treeText + latestText + missingText)
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
        File checkpointDir =
                new File(env.appConfig.getRuntime().getCacheDir(), "checkpoints/dashboard");
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

    /** 验证列表参数按对标契约过滤归档、来源、目录、消息数、子会话和排序。 */
    @Test
    void shouldApplyReferenceCompatibleSessionListParameters() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord oldRecent = env.sessionRepository.bindNewSession("MEMORY:list-old:user");
        oldRecent.setTitle("old recent");
        oldRecent.setCreatedAt(100L);
        oldRecent.setUpdatedAt(900L);
        oldRecent.setMetadataJson("{\"cwd\":\"/repo/app\"}");
        oldRecent.setSystemPromptSnapshot("system snapshot");
        oldRecent.setNdjson(MessageSupport.toNdjson(Arrays.asList(ChatMessage.ofUser("old"))));
        env.sessionRepository.save(oldRecent);

        SessionRecord newer = env.sessionRepository.bindNewSession("FEISHU:list-new:user");
        newer.setTitle("newer created");
        newer.setCreatedAt(500L);
        newer.setUpdatedAt(500L);
        newer.setMetadataJson("{\"cwd\":\"/repo/other\"}");
        newer.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofUser("first"), ChatMessage.ofAssistant("second"))));
        env.sessionRepository.save(newer);

        SessionRecord archived = env.sessionRepository.bindNewSession("MEMORY:list-archived:user");
        archived.setTitle("archived");
        archived.setCreatedAt(300L);
        archived.setUpdatedAt(300L);
        archived.setMetadataJson("{\"archived\":true,\"cwd\":\"/repo/app/sub\"}");
        archived.setNdjson(MessageSupport.toNdjson(Arrays.asList(ChatMessage.ofUser("hidden"))));
        env.sessionRepository.save(archived);

        SessionRecord child =
                env.sessionRepository.cloneSession(
                        "MEMORY:list-child:user", newer.getSessionId(), "child");
        child.setTitle("child");
        child.setCreatedAt(700L);
        child.setUpdatedAt(700L);
        child.setNdjson(MessageSupport.toNdjson(Arrays.asList(ChatMessage.ofUser("child"))));
        env.sessionRepository.save(child);

        DashboardSessionService service = new DashboardSessionService(env.sessionRepository);

        assertThat(sessionTitles(service.getSessions(20, 0)))
                .containsExactly("child", "newer created", "old recent");
        DashboardSessionService.SessionListOptions recent =
                new DashboardSessionService.SessionListOptions(
                        20,
                        0,
                        0,
                        "exclude",
                        "recent",
                        null,
                        Collections.<String>emptyList(),
                        null,
                        false);
        assertThat(sessionTitles(service.getSessions(recent)))
                .containsExactly("old recent", "child", "newer created");

        DashboardSessionService.SessionListOptions feishu =
                new DashboardSessionService.SessionListOptions(
                        20,
                        0,
                        2,
                        "exclude",
                        "created",
                        "feishu",
                        Collections.<String>emptyList(),
                        null,
                        false);
        assertThat(sessionTitles(service.getSessions(feishu))).containsExactly("newer created");

        DashboardSessionService.SessionListOptions cwdAndArchived =
                new DashboardSessionService.SessionListOptions(
                        20,
                        0,
                        0,
                        "include",
                        "created",
                        null,
                        Collections.singletonList("feishu"),
                        "/repo/app",
                        true);
        Map<String, Object> cwdPage = service.getSessions(cwdAndArchived);
        assertThat(sessionTitles(cwdPage)).containsExactly("archived", "old recent");
        assertThat(sessions(cwdPage).get(0)).containsEntry("archived", Boolean.TRUE);
        assertThat(sessions(cwdPage).get(1))
                .containsEntry("system_prompt", "system snapshot")
                .containsKey("model_config");
    }

    /** 验证消息分页、详情读取、归档更新和缺失会话 404 异常契约。 */
    @Test
    void shouldPageMessagesAndUpdateTitleOrArchivedState() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord session = env.sessionRepository.bindNewSession("MEMORY:message-page:user");
        session.setTitle("before");
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofUser("one"),
                                ChatMessage.ofAssistant("two"),
                                ChatMessage.ofUser("three"))));
        env.sessionRepository.save(session);
        DashboardSessionService service = new DashboardSessionService(env.sessionRepository);

        Map<String, Object> page = service.getSessionMessages(session.getSessionId(), 1, 1);
        assertThat(messages(page)).extracting(item -> item.get("content")).containsExactly("two");
        Map<String, Object> expectedPagination = new java.util.LinkedHashMap<String, Object>();
        expectedPagination.put("limit", Integer.valueOf(1));
        expectedPagination.put("offset", Integer.valueOf(1));
        expectedPagination.put("returned", Integer.valueOf(1));
        assertThat(page.get("pagination")).isEqualTo(expectedPagination);
        assertThat(service.getSessionDetail(session.getSessionId()))
                .containsEntry("session_id", session.getSessionId())
                .containsEntry("title", "before");

        Map<String, Object> update = new java.util.LinkedHashMap<String, Object>();
        update.put("title", "  renamed\n session  ");
        update.put("archived", Boolean.TRUE);
        assertThat(service.updateSession(session.getSessionId(), update))
                .containsEntry("ok", Boolean.TRUE)
                .containsEntry("title", "renamed session")
                .containsEntry("archived", Boolean.TRUE);

        DashboardSessionService.SessionListOptions archivedOnly =
                new DashboardSessionService.SessionListOptions(
                        20,
                        0,
                        0,
                        "only",
                        "created",
                        null,
                        Collections.<String>emptyList(),
                        null,
                        false);
        assertThat(sessionTitles(service.getSessions(archivedOnly)))
                .containsExactly("renamed session");
        assertThatThrownBy(() -> service.getSessionMessages("missing", null, 0))
                .isInstanceOf(DashboardSessionService.SessionNotFoundException.class);
        assertThatThrownBy(() -> service.latestDescendant("missing"))
                .isInstanceOf(DashboardSessionService.SessionNotFoundException.class);
        assertThat(service.deleteSession("missing"))
                .containsEntry("ok", Boolean.TRUE)
                .containsEntry("already_absent", Boolean.TRUE);
    }

    /** 验证消息读取跟随普通延续链，但不会误入显式分支或委托子会话。 */
    @Test
    void shouldFollowResumeContinuationWithoutEnteringBranchOrDelegation() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord root = new SessionRecord();
        root.setSessionId("resume-root");
        root.setSourceKey("MEMORY:resume-root:user");
        root.setTitle("root");
        root.setCreatedAt(100L);
        root.setUpdatedAt(100L);
        root.setNdjson(MessageSupport.toNdjson(Arrays.asList(ChatMessage.ofUser("root text"))));
        env.sessionRepository.save(root);

        SessionRecord continuation = new SessionRecord();
        continuation.setSessionId("resume-continuation");
        continuation.setSourceKey("MEMORY:resume-continuation:user");
        continuation.setParentSessionId(root.getSessionId());
        continuation.setCreatedAt(200L);
        continuation.setUpdatedAt(200L);
        continuation.setNdjson(
                MessageSupport.toNdjson(Arrays.asList(ChatMessage.ofUser("continued text"))));
        env.sessionRepository.save(continuation);

        SessionRecord branch = new SessionRecord();
        branch.setSessionId("resume-branch");
        branch.setSourceKey("MEMORY:resume-branch:user");
        branch.setParentSessionId(root.getSessionId());
        branch.setBranchName("experiment");
        branch.setTitle("branch");
        branch.setCreatedAt(300L);
        branch.setUpdatedAt(300L);
        branch.setNdjson(MessageSupport.toNdjson(Arrays.asList(ChatMessage.ofUser("branch text"))));
        env.sessionRepository.save(branch);

        SessionRecord delegated = new SessionRecord();
        delegated.setSessionId("resume-delegated");
        delegated.setSourceKey("MEMORY:resume:delegate-subagent:user");
        delegated.setParentSessionId(continuation.getSessionId());
        delegated.setCreatedAt(400L);
        delegated.setUpdatedAt(400L);
        delegated.setNdjson(
                MessageSupport.toNdjson(Arrays.asList(ChatMessage.ofUser("delegated text"))));
        env.sessionRepository.save(delegated);

        DashboardSessionService service = new DashboardSessionService(env.sessionRepository);
        Map<String, Object> detail = service.getSessionMessages(root.getSessionId());

        assertThat(detail.get("session_id")).isEqualTo(continuation.getSessionId());
        assertThat(messages(detail))
                .extracting(item -> item.get("content"))
                .containsExactly("continued text");
        assertThat(sessionTitles(service.getSessions(20, 0)))
                .contains("root", "branch")
                .doesNotContain("continued text", "delegated text");
    }

    /** 从会话页提取会话列表。 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> sessions(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("sessions");
    }

    /** 从会话页提取标题列表。 */
    private List<Object> sessionTitles(Map<String, Object> page) {
        List<Object> titles = new java.util.ArrayList<Object>();
        for (Map<String, Object> item : sessions(page)) {
            titles.add(item.get("title"));
        }
        return titles;
    }

    /** 从消息详情提取消息列表。 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> messages(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("messages");
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
                            "insert into checkpoints (checkpoint_id, source_key, session_id,"
                                    + " checkpoint_dir, manifest_path, created_at, restored_at) values"
                                    + " (?, ?, ?, ?, ?, ?, ?)");
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
