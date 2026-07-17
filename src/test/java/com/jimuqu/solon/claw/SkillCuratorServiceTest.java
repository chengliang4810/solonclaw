package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.context.SkillCuratorService;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.service.LlmGateway;
import com.jimuqu.solon.claw.support.MessageSupport;
import com.jimuqu.solon.claw.support.TestEnvironment;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;

public class SkillCuratorServiceTest {
    @Test
    void shouldMarkAgentCreatedSkillsAndWriteReportsWithoutDeleting() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getCurator().setEnabled(true);
        env.appConfig.getCurator().setStaleAfterDays(1);
        env.appConfig.getCurator().setArchiveAfterDays(2);
        File staleSkill = createSkill(env, "old-skill", false);
        File pinnedSkill = createSkill(env, "pinned-skill", true);
        long old = System.currentTimeMillis() - 3L * 24L * 60L * 60L * 1000L;
        touch(staleSkill, old);
        touch(pinnedSkill, old);

        SkillCuratorService service = new SkillCuratorService(env.appConfig, env.localSkillService);
        Map<String, Object> report = service.runOnce(true);

        assertThat(report.get("status")).isEqualTo("ok");
        assertThat(String.valueOf(report))
                .contains("curator://state")
                .contains("skill://old-skill")
                .doesNotContain(env.appConfig.getRuntime().getHome());
        assertThat(new File(env.appConfig.getRuntime().getSkillsDir(), ".curator_state")).isFile();
        assertThat(new File(staleSkill, "SKILL.md")).isFile();
        assertThat(FileUtil.loopFiles(new File(env.appConfig.getRuntime().getLogsDir(), "curator")))
                .extracting(File::getName)
                .contains("run.json", "REPORT.md");
    }

    @Test
    void shouldPauseAndResumeCurator() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SkillCuratorService service = new SkillCuratorService(env.appConfig, env.localSkillService);

        service.pause();
        assertThat(service.runOnce(false).get("status")).isEqualTo("paused");
        service.resume();
        assertThat(service.runOnce(true).get("status")).isEqualTo("ok");
    }

    /** 近期调用的旧技能应按最近活动时间保留为 active，而不是按文件时间误判陈旧。 */
    @Test
    void shouldKeepRecentlyUsedOldSkillActive() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getCurator().setEnabled(true);
        env.appConfig.getCurator().setStaleAfterDays(1);
        env.appConfig.getCurator().setArchiveAfterDays(2);
        File skill = createSkill(env, "recently-used-skill", false);
        touch(skill, System.currentTimeMillis() - 3L * 24L * 60L * 60L * 1000L);
        env.localSkillService.bumpUsage("recently-used-skill", "call");

        Map<String, Object> report =
                new SkillCuratorService(env.appConfig, env.localSkillService).runOnce(true);

        assertThat(item(report, "recently-used-skill").get("status")).isEqualTo("active");
        assertThat(item(report, "recently-used-skill").get("ageDays")).isEqualTo(0L);
    }

    /** 首次整理没有历史状态的技能时，对外 previousStatus 应保持 active。 */
    @Test
    void shouldDefaultMissingPreviousStatusToActive() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getCurator().setEnabled(true);
        createSkill(env, "first-review-skill", false);

        Map<String, Object> report =
                new SkillCuratorService(env.appConfig, env.localSkillService).runOnce(true);

        assertThat(item(report, "first-review-skill").get("previousStatus")).isEqualTo("active");
    }

    /** AI 评估必须同时接收真实技能正文、用量数据和实际用户/助手会话。 */
    @Test
    void shouldEvaluateSkillWithRealContentUsageAndConversationEvidence() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        createSkill(env, "evidence-skill", false);
        SessionRecord session = session(env, "real-session", "这个技能的输出遗漏边界检查", "我会补充空输入和超长输入处理");
        env.localSkillService.bumpUsage("evidence-skill", "call", session.getSessionId(), 1);
        RecordingGateway gateway =
                new RecordingGateway(
                        "{\"skill_name\":\"evidence-skill\",\"verdict\":\"improve\","
                                + "\"confidence\":0.91,\"scores\":{\"usefulness\":0.8,\"quality\":0.6,\"currency\":0.9},"
                                + "\"evidence_refs\":[\"C1M1\"],\"issues\":[\"缺少边界处理\"],"
                                + "\"recommendations\":[\"补充空输入和超长输入测试\"]}");
        SkillCuratorService service =
                new SkillCuratorService(
                        env.appConfig, env.localSkillService, env.sessionRepository, gateway);

        Map<String, Object> report = service.runOnce(true);

        Map<String, Object> result = item(report, "evidence-skill");
        assertThat(String.valueOf(result.get("evaluation")))
                .contains("mode=ai")
                .contains("confidence=0.91")
                .contains("C1M1");
        assertThat(gateway.lastUserMessage)
                .contains("# Demo")
                .contains("callCount\":1")
                .contains("这个技能的输出遗漏边界检查")
                .contains("我会补充空输入和超长输入处理");
        service.shutdown();
    }

    /** 模型伪造不存在的证据引用时必须拒绝结果并保留确定性报告。 */
    @Test
    void shouldFallbackWhenModelFabricatesConversationEvidence() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        createSkill(env, "invalid-reference-skill", false);
        SessionRecord session = session(env, "real-session", "请检查技能", "已检查");
        env.localSkillService.bumpUsage(
                "invalid-reference-skill", "call", session.getSessionId(), 1);
        RecordingGateway gateway =
                new RecordingGateway(
                        "{\"skill_name\":\"invalid-reference-skill\",\"verdict\":\"archive_candidate\","
                                + "\"confidence\":0.99,\"scores\":{\"usefulness\":0.1,\"quality\":0.1,\"currency\":0.1},"
                                + "\"evidence_refs\":[\"C9M9\"],\"issues\":[],\"recommendations\":[\"归档\"]}");
        SkillCuratorService service =
                new SkillCuratorService(
                        env.appConfig, env.localSkillService, env.sessionRepository, gateway);

        Map<String, Object> report = service.runOnce(true);

        assertThat(String.valueOf(item(report, "invalid-reference-skill").get("evaluation")))
                .contains("mode=deterministic_fallback")
                .contains("未提供的会话证据")
                .doesNotContain("verdict=archive_candidate");
        service.shutdown();
    }

    /** 慢模型评估期间的真实技能调用不应被状态锁阻塞或在最终合并时丢失。 */
    @Test
    void shouldPreserveConcurrentUsageWhileAiEvaluationIsRunning() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        createSkill(env, "concurrent-usage-skill", false);
        SessionRecord session = session(env, "concurrent-session", "请评估这个技能", "开始评估");
        env.localSkillService.bumpUsage(
                "concurrent-usage-skill", "call", session.getSessionId(), 1);
        BlockingGateway gateway = new BlockingGateway("concurrent-usage-skill");
        SkillCuratorService service =
                new SkillCuratorService(
                        env.appConfig, env.localSkillService, env.sessionRepository, gateway);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Map<String, Object>> run = executor.submit(() -> service.runOnce(true));
            assertThat(gateway.entered.await(2, TimeUnit.SECONDS)).isTrue();
            Future<?> usage =
                    executor.submit(
                            () ->
                                    env.localSkillService.bumpUsage(
                                            "concurrent-usage-skill",
                                            "call",
                                            session.getSessionId(),
                                            1));
            usage.get(1, TimeUnit.SECONDS);
            gateway.release.countDown();
            run.get(2, TimeUnit.SECONDS);
        } finally {
            gateway.release.countDown();
            executor.shutdownNow();
            service.shutdown();
        }

        Object parsed =
                ONode.deserialize(
                        FileUtil.readUtf8String(
                                new File(
                                        env.appConfig.getRuntime().getSkillsDir(),
                                        ".curator_state")),
                        Object.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) parsed;
        @SuppressWarnings("unchecked")
        Map<String, Object> skills = (Map<String, Object>) state.get("skills");
        @SuppressWarnings("unchecked")
        Map<String, Object> record = (Map<String, Object>) skills.get("concurrent-usage-skill");
        assertThat(((Number) record.get("callCount")).longValue()).isEqualTo(2L);
    }

    /** 技能会话引用必须按最近顺序去重并严格限制为十条。 */
    @Test
    void shouldBoundRecentSkillSessionEvidence() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        createSkill(env, "bounded-evidence-skill", false);
        for (int index = 0; index < 12; index++) {
            env.localSkillService.bumpUsage(
                    "bounded-evidence-skill", "call", "session-" + index, 1);
        }

        Object parsed =
                ONode.deserialize(
                        FileUtil.readUtf8String(
                                new File(
                                        env.appConfig.getRuntime().getSkillsDir(),
                                        ".curator_state")),
                        Object.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) parsed;
        @SuppressWarnings("unchecked")
        Map<String, Object> skills = (Map<String, Object>) state.get("skills");
        @SuppressWarnings("unchecked")
        Map<String, Object> record = (Map<String, Object>) skills.get("bounded-evidence-skill");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> evidence =
                (List<Map<String, Object>>) record.get("recentSessionEvidence");
        assertThat(evidence).hasSize(10);
        assertThat(evidence.get(0).get("sessionId")).isEqualTo("session-2");
        assertThat(evidence.get(9).get("sessionId")).isEqualTo("session-11");
    }

    /** 证据窗口必须绑定技能执行轮次，不能采集同一会话后续无关对话。 */
    @Test
    void shouldUseConversationAroundSkillInvocationBoundary() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        createSkill(env, "anchored-evidence-skill", false);
        SessionRecord session =
                env.sessionRepository.bindNewSession("MEMORY:chat:anchored-session:user");
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofUser("请用这个技能检查边界"),
                                new AssistantMessage("技能检查完成并补充了边界"),
                                ChatMessage.ofUser("现在讨论完全无关的旅行安排"),
                                new AssistantMessage("可以安排周末出行"))));
        env.sessionRepository.save(session);
        env.localSkillService.bumpUsage(
                "anchored-evidence-skill", "call", session.getSessionId(), 1);
        RecordingGateway gateway =
                new RecordingGateway(
                        "{\"skill_name\":\"anchored-evidence-skill\",\"verdict\":\"keep\","
                                + "\"confidence\":0.9,\"scores\":{\"usefulness\":0.9,\"quality\":0.9,\"currency\":0.9},"
                                + "\"evidence_refs\":[\"C1M1\"],\"issues\":[],\"recommendations\":[]}");
        SkillCuratorService service =
                new SkillCuratorService(
                        env.appConfig, env.localSkillService, env.sessionRepository, gateway);

        service.runOnce(true);

        assertThat(gateway.lastUserMessage)
                .contains("请用这个技能检查边界")
                .contains("技能检查完成并补充了边界")
                .doesNotContain("旅行安排")
                .doesNotContain("周末出行");
        service.shutdown();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> item(Map<String, Object> report, String name) {
        for (Object value : (Iterable<?>) report.get("items")) {
            Map<String, Object> item = (Map<String, Object>) value;
            if (name.equals(item.get("name"))) {
                return item;
            }
        }
        throw new AssertionError("Missing curator item: " + name);
    }

    private File createSkill(TestEnvironment env, String name, boolean pinned) {
        File dir = new File(env.appConfig.getRuntime().getSkillsDir(), name);
        FileUtil.mkdir(dir);
        String frontmatter =
                "---\nname: "
                        + name
                        + "\ndescription: demo\n"
                        + (pinned ? "pinned: true\n" : "")
                        + "---\n\n# Demo\n";
        FileUtil.writeUtf8String(frontmatter, new File(dir, "SKILL.md"));
        return dir;
    }

    /** 创建包含一轮真实用户与助手消息的测试会话。 */
    private SessionRecord session(TestEnvironment env, String id, String user, String assistant)
            throws Exception {
        SessionRecord session = env.sessionRepository.bindNewSession("MEMORY:chat:" + id + ":user");
        session.setUpdatedAt(System.currentTimeMillis());
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(ChatMessage.ofUser(user), new AssistantMessage(assistant))));
        env.sessionRepository.save(session);
        return session;
    }

    private void touch(File dir, long timestamp) {
        for (File file : FileUtil.loopFiles(dir)) {
            file.setLastModified(timestamp);
        }
        dir.setLastModified(timestamp);
    }

    /** 返回固定 JSON 并记录模型输入的测试网关。 */
    private static final class RecordingGateway implements LlmGateway {
        /** 固定模型回复。 */
        private final String response;

        /** 最近一次用户提示。 */
        private String lastUserMessage;

        /** 创建固定回复模型网关。 */
        private RecordingGateway(String response) {
            this.response = response;
        }

        /** 返回固定技能评估 JSON。 */
        @Override
        public LlmResult chat(
                SessionRecord session,
                String systemPrompt,
                String userMessage,
                List<Object> toolObjects) {
            lastUserMessage = userMessage;
            LlmResult result = new LlmResult();
            result.setAssistantMessage(new AssistantMessage(response));
            result.setProvider("test-provider");
            result.setModel("test-model");
            return result;
        }

        /** 测试不使用恢复调用。 */
        @Override
        public LlmResult resume(
                SessionRecord session, String systemPrompt, List<Object> toolObjects) {
            throw new UnsupportedOperationException("test gateway does not resume");
        }
    }

    /** 在测试闩锁释放前阻塞模型回复，用于验证整理期间的并发用量写入。 */
    private static final class BlockingGateway implements LlmGateway {
        /** 模型调用已进入的通知。 */
        private final CountDownLatch entered = new CountDownLatch(1);

        /** 允许模型返回的通知。 */
        private final CountDownLatch release = new CountDownLatch(1);

        /** 固定返回结果中的技能名。 */
        private final String skillName;

        /** 创建阻塞测试网关。 */
        private BlockingGateway(String skillName) {
            this.skillName = skillName;
        }

        /** 等待测试线程完成并发用量写入后返回有效 JSON。 */
        @Override
        public LlmResult chat(
                SessionRecord session,
                String systemPrompt,
                String userMessage,
                List<Object> toolObjects)
                throws Exception {
            entered.countDown();
            if (!release.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test release timeout");
            }
            LlmResult result = new LlmResult();
            result.setAssistantMessage(
                    new AssistantMessage(
                            "{\"skill_name\":\""
                                    + skillName
                                    + "\",\"verdict\":\"keep\",\"confidence\":0.9,"
                                    + "\"scores\":{\"usefulness\":0.9,\"quality\":0.9,\"currency\":0.9},"
                                    + "\"evidence_refs\":[\"C1M1\"],\"issues\":[],\"recommendations\":[]}"));
            return result;
        }

        /** 测试不使用恢复调用。 */
        @Override
        public LlmResult resume(
                SessionRecord session, String systemPrompt, List<Object> toolObjects) {
            throw new UnsupportedOperationException("test gateway does not resume");
        }
    }
}
